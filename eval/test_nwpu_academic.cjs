// Run: node eval/test_nwpu_academic.cjs [out.json]. Fixtures are synthetic, never real student data.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const api = require('../android/app/assets/nwpu-academic.js');
let checks = 0;
function check(name, fn) { fn(); checks++; console.log('PASS ' + name); }

// Minimal fake DOM mirroring the exam-arrange table shape.
function el(tag, attrs, children, textContent) {
    const node = {tag, attrs: attrs || {}, children: children || [], getAttribute: k => node.attrs[k] ?? null};
    node.textContent = textContent ?? node.children.map(c => c.textContent).join(' ');
    const all = () => node.children.flatMap(c => [c, ...c.all()]);
    node.all = all;
    const match = (n, sel) => sel.split(',').map(s => s.trim()).some(s => {
        if (s === 'tr[data-finished]') return n.tag === 'tr' && 'data-finished' in n.attrs;
        if (s === 'div.time') return n.tag === 'div' && n.attrs.class === 'time';
        if (s === '#fm1') return n.attrs.id === 'fm1';
        if (s === 'input[type="password"]') return n.tag === 'input' && n.attrs.type === 'password';
        if (s === 'button.footer.btn.btn-primary[value]') return false;
        return n.tag === s;
    });
    node.querySelectorAll = sel => all().filter(n => match(n, sel));
    node.querySelector = sel => node.querySelectorAll(sel)[0] || null;
    return node;
}
function row(finished, time, place, course, status) {
    return el('tr', {'data-finished': finished}, [
        el('td', {}, [el('div', {class: 'time'}, [], time), el('span', {}, [], place[0]), el('span', {}, [], place[1])]),
        el('td', {}, [el('span', {}, [], course)]),
        el('td', {}, [], '  ' + status + '  '),
    ]);
}
const examDoc = () => el('html', {}, [el('table', {}, [
    row('false', '2026-01-12 14:00~16:00', ['长安校区', '教东A-101'], '线性代数', '未开始'),
    row('true', '2025-12-30 08:30~10:30', ['友谊校区', '教西B-201'], '大学物理', '已结束'),
    row('false', '时间待定', ['', ''], '军事理论', '待安排'),
])]);

check('exam rows read time, location, course, status', () => {
    const r = api.examRows(examDoc());
    assert.equal(r.length, 3);
    assert.deepEqual(r[0], {finished: 'false', time: '2026-01-12 14:00~16:00', location: '长安校区, 教东A-101', course: '线性代数', status: '  未开始  '});
});
check('exams normalized, parsed and ordered (pending first, by date)', () => {
    const e = api.normalizeExams(api.examRows(examDoc()));
    assert.deepEqual(e.map(x => x.course), ['线性代数', '军事理论', '大学物理']);
    assert.deepEqual([e[0].date, e[0].start, e[0].end, e[0].finished], ['2026-01-12', '14:00', '16:00', false]);
    assert.equal(e[2].finished, true); assert.equal(e[0].status, '未开始');
});
check('unparseable exam time stays raw, no fabricated date', () => {
    const e = api.normalizeExams(api.examRows(examDoc())).find(x => x.course === '军事理论');
    assert.deepEqual([e.time, e.date, e.start], ['时间待定', '', '']);
});
check('impossible dates are not fabricated', () => assert.equal(api.examTime('2026-02-30 08:00~10:00').date, ''));
check('empty exam table is a valid empty list', () => assert.deepEqual(api.normalizeExams(api.examRows(el('html', {}, [el('table')]))), []));
check('login page is not mistaken for "no exams"', () => {
    assert.throws(() => api.examRows(el('html', {}, [el('form', {id: 'fm1'}), el('table')])));
    assert.throws(() => api.examRows(el('html', {}, [el('div')])));
});
check('unknown finished flag rejected', () => assert.throws(() => api.normalizeExams([{finished: 'yes', course: 'A'}])));
check('duplicate exam rows collapse', () => {
    const r = api.examRows(examDoc()); r.push({...r[0]});
    assert.equal(api.normalizeExams(r).length, 3);
});

check('semester options from grade index', () => {
    const html = '<select><option value="321" selected>2025-2026秋</option><option value="300">2024-2025 <b>春</b></option><option value="321">dup</option><option value="x">bad</option></select>';
    assert.deepEqual(api.semesterOptions(html), [{id: '321', name: '2025-2026秋'}, {id: '300', name: '2024-2025 春'}]);
});
const gradeJson = () => ({semesterId2studentGrades: {321: [
    {course: {nameZh: '高等数学', code: 'MATH1001', credits: 5}, courseType: {nameZh: '必修'}, gaGrade: '92', gp: 4.1,
        gradeDetail: '<span>平时成绩:90</span><span>期末成绩:93</span> 补考成绩:61', fillAGrace: '', student: {name: 'PRIVATE'}},
    {course: {nameZh: '体育', credits: '1'}, courseType: {nameZh: '必修'}, gaGrade: '优秀', gp: null, gradeDetail: null},
]}});
check('grades normalized with detail and resit score', () => {
    const g = api.normalizeGrades(gradeJson(), '321', '2025-2026秋');
    assert.equal(g.length, 2);
    assert.deepEqual(g[0], {semesterId: '321', semester: '2025-2026秋', name: '高等数学', code: 'MATH1001', type: '必修', score: '92', gp: 4.1, credits: 5,
        detail: '平时成绩:90；期末成绩:93；补考成绩:61'});
    assert.deepEqual([g[1].score, g[1].gp, g[1].credits], ['优秀', null, 1]);
});
check('grades never export unrelated student fields', () => assert.equal(JSON.stringify(api.normalizeGrades(gradeJson(), '321', 'x')).includes('PRIVATE'), false));
check('semester without published grades is empty', () => assert.deepEqual(api.normalizeGrades({semesterId2studentGrades: {}}, '1', 'x'), []));
check('unknown grade schema rejected', () => { assert.throws(() => api.normalizeGrades({}, '1', 'x')); assert.throws(() => api.normalizeGrades({semesterId2studentGrades: {1: [{course: {}}]}}, '1', 'x')); });

check('gpa: number, numeric string, null and invalid', () => {
    assert.equal(api.normalizeGpa({gpa: 3.8765}), 3.877); assert.equal(api.normalizeGpa({gpa: '3.5'}), 3.5);
    assert.equal(api.normalizeGpa(null), null); assert.equal(api.normalizeGpa({gpa: null}), null);
    assert.throws(() => api.normalizeGpa({gpa: 9})); assert.throws(() => api.normalizeGpa({gpa: 'abc'}));
});
check('exact HTTPS jwxt origin required', () => {
    for (const u of ['http://jwxt.nwpu.edu.cn/', 'https://jwxt.nwpu.edu.cn.evil.test/', 'https://jwxt.nwpu.edu.cn:8443/', 'https://uis.nwpu.edu.cn/']) assert.equal(api.allowed(new URL(u)), false);
    assert.equal(api.allowed(new URL('https://jwxt.nwpu.edu.cn/student/for-std/course-table')), true);
});

(async () => {
    global.location = new URL('https://jwxt.nwpu.edu.cn/student/for-std/course-table');
    global.DOMParser = class { parseFromString() { return examDoc(); } };
    const seen = [];
    const respond = (url, body) => ({ok: true, url: 'https://jwxt.nwpu.edu.cn' + url.split('?')[0], text: async () => typeof body === 'string' ? body : JSON.stringify(body)});
    global.fetch = async (url, options) => {
        seen.push(url);
        assert.equal(options.credentials, 'same-origin'); assert.equal(options.method, 'GET');
        if (url === '/student/for-std/exam-arrange') return respond(url, '<html></html>');
        if (url === '/student/for-std/grade/sheet/semester-index/123') return respond(url, '<option value="321">2025-2026秋</option>');
        if (url === '/student/for-std/grade/sheet/info/123?semester=321') return respond(url, gradeJson());
        if (url.startsWith('/student/for-std/student-portrait/getMyGrades?studentAssoc=123')) return respond(url, {gpa: 3.9});
        throw new Error('unexpected ' + url);
    };
    const wait = async () => { for (let i = 0; i < 50 && /^(loading|partial)$/.test(global.__louAcademicResult.status); i++) await new Promise(r => setImmediate(r)); };
    api.start('t1', '123'); await wait();
    const r = global.__louAcademicResult;
    check('signed-in read returns all three parts', () => {
        assert.equal(r.status, 'ok'); assert.equal(r.token, 't1'); assert.deepEqual(r.data.failed, []);
        assert.equal(r.data.exams.length, 3); assert.equal(r.data.grades.length, 2); assert.equal(r.data.gpa, 3.9);
        assert.equal(r.data.school, '西北工业大学');
    });
    check('only same-origin relative paths are requested', () => assert.ok(seen.every(u => u.startsWith('/student/for-std/'))));

    global.fetch = async url => url.includes('grade/sheet') ? {ok: false, status: 412} : respond(url, url.includes('getMyGrades') ? 'null' : '<html></html>');
    api.start('t2', '123'); await wait();
    check('a failed part is reported, others still returned', () => {
        const d = global.__louAcademicResult.data;
        assert.equal(global.__louAcademicResult.status, 'ok'); assert.deepEqual(d.failed, ['grades']); assert.equal(d.grades, null); assert.equal(d.gpa, null);
    });
    const terms = [];
    global.fetch = async url => {
        if (url.includes('semester-index')) return respond(url,'<option value="321">2026春</option><option value="322">2025秋</option><option value="323">2025春</option>');
        if (url.includes('grade/sheet/info')) { const term=new URL(url,'https://jwxt.nwpu.edu.cn').searchParams.get('semester'); terms.push(term); if (term==='321') return {ok:false,status:500}; const j=gradeJson(); j.semesterId2studentGrades[term]=j.semesterId2studentGrades['321']; delete j.semesterId2studentGrades['321']; return respond(url,term==='323'?{semesterId2studentGrades:{}}:j); }
        return respond(url,url.includes('getMyGrades')?{gpa:3.9}:'<html></html>');
    };
    api.start('history','123'); await wait();
    check('one failed semester cannot discard other readable historical semesters', () => {
        const d=global.__louAcademicResult.data;
        assert.deepEqual(terms.sort(),['321','322','323']); assert.deepEqual(d.failed,['grades']);
        assert.deepEqual(d.gradeSemestersRead.sort(),['322','323']); assert.equal(d.grades.length,2); assert.equal(d.gradeProgress.finished,3);
    });
    global.fetch = async url => ({ok: true, url: 'https://jwxt.nwpu.edu.cn/student/login', text: async () => '<html>LOGIN SECRET</html>'});
    api.start('t3', '123'); await wait();
    check('expired session: generic error, no body leaked', () => {
        assert.equal(global.__louAcademicResult.status, 'error');
        assert.equal(JSON.stringify(global.__louAcademicResult).includes('SECRET'), false);
    });
    api.start('t4', '12a'); await wait();
    check('non-numeric identity refused before any request', () => assert.equal(global.__louAcademicResult.status, 'error'));

    if (process.argv[2]) fs.writeFileSync(path.resolve(process.argv[2]), JSON.stringify(r.data), 'utf8');
    console.log(checks + ' checks passed');
})().catch(e => { console.error(e); process.exitCode = 1; });
