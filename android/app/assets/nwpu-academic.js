/* NWPU exams / grades / GPA adapter. Runs in the already signed-in jwxt page, same-origin only.
 * Endpoint evidence: qllokirin/nonebot-plugin-npu, nwpu_query.py (get_exams, get_grades, get_rank).
 * No native bridge, no cookies, no raw response bodies or identifiers are returned to Android.
 * Each part (exams, grades, gpa) is independent: a failed part is listed in `failed` and the app
 * keeps the previously saved copy of that part instead of overwriting it with nothing. */
(function (root) {
    'use strict';
    const SCHOOL = '西北工业大学';
    function text(v) { return typeof v === 'string' ? v.replace(/\s+/g, ' ').trim() : ''; }
    function bounded(s, max, label) {
        if (s.length > max) throw new Error(label + '过长');
        return s;
    }
    function stripTags(s) {
        return text(String(s).replace(/<[^>]*>/g, ' ').replace(/&nbsp;/g, ' ').replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&'));
    }
    function number(v) {
        if (typeof v === 'number' && Number.isFinite(v)) return v;
        if (typeof v === 'string' && /^-?\d+(?:\.\d+)?$/.test(v.trim())) return +v.trim();
        return null;
    }

    /* ---------- exams ---------- */
    // "2026-01-08 14:00~16:00" (also tolerates "-", "—", "至" separators). Unparseable stays raw only.
    function examTime(raw) {
        const m = raw.match(/(\d{4})-(\d{1,2})-(\d{1,2})\s*(\d{1,2}):(\d{2})\s*(?:~|-|—|–|至)\s*(\d{1,2}):(\d{2})/);
        if (!m) return {date: '', start: '', end: ''};
        const p = n => String(n).padStart(2, '0');
        const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3]));
        if (d.getUTCMonth() !== +m[2] - 1 || d.getUTCDate() !== +m[3] || +m[4] > 23 || +m[6] > 23 || +m[5] > 59 || +m[7] > 59)
            return {date: '', start: '', end: ''};
        return {date: m[1] + '-' + p(m[2]) + '-' + p(m[3]), start: p(m[4]) + ':' + m[5], end: p(m[6]) + ':' + m[7]};
    }
    function normalizeExams(rows) {
        if (!Array.isArray(rows)) throw new Error('考试安排格式发生变化');
        if (rows.length > 500) throw new Error('考试安排数据过大');
        const out = [], seen = new Set();
        rows.forEach(r => {
            if (r.finished !== 'true' && r.finished !== 'false') throw new Error('考试状态格式发生变化');
            const course = bounded(text(r.course), 200, '考试课程');
            if (!course) throw new Error('考试课程缺失');
            const time = bounded(text(r.time), 120, '考试时间');
            const exam = Object.assign({course, time, location: bounded(text(r.location), 300, '考试地点'),
                status: bounded(text(r.status), 100, '考试状态'), finished: r.finished === 'true'}, examTime(time));
            const key = JSON.stringify(exam);
            if (!seen.has(key)) { seen.add(key); out.push(exam); }
        });
        out.sort((a, b) => (a.finished - b.finished) || (a.date || '9999').localeCompare(b.date || '9999') ||
            a.start.localeCompare(b.start) || a.course.localeCompare(b.course));
        return out;
    }
    // Mirrors the evidence parser: <tr data-finished>, first <td> holds div.time + location spans,
    // second <td> holds the course in a span, third <td> the status.
    function examRows(doc) {
        if (doc.querySelector('#fm1, input[type="password"]')) throw new Error('登录已失效');
        const trs = Array.from(doc.querySelectorAll('tr[data-finished]'));
        if (!trs.length && !doc.querySelector('table')) throw new Error('没有找到考试安排表');
        return trs.map(tr => {
            const tds = tr.querySelectorAll('td');
            const timeEl = tr.querySelector('div.time');
            const time = timeEl ? timeEl.textContent : '';
            const spans = tds[0] ? Array.from(tds[0].querySelectorAll('span')).map(s => text(s.textContent)) : [];
            const location = spans.filter(s => s && s !== text(time)).join(', ');
            const courseEl = tds[1] && (tds[1].querySelector('span') || tds[1]);
            return {finished: tr.getAttribute('data-finished'), time, location,
                course: courseEl ? courseEl.textContent : '', status: tds[2] ? tds[2].textContent : ''};
        });
    }

    /* ---------- grades ---------- */
    function semesterOptions(html) {
        const out = [], seen = new Set(), re = /<option[^>]*value="(\d+)"[^>]*>([\s\S]*?)<\/option>/g;
        let m;
        while ((m = re.exec(String(html))) && out.length < 30) {
            if (seen.has(m[1])) continue;
            seen.add(m[1]); out.push({id: m[1], name: stripTags(m[2]).slice(0, 100) || m[1]});
        }
        return out;
    }
    function normalizeGrades(json, semesterId, semesterName) {
        const map = json && json.semesterId2studentGrades;
        if (!map || typeof map !== 'object') throw new Error('成绩格式发生变化');
        const list = map[semesterId] == null ? [] : map[semesterId];
        if (!Array.isArray(list)) throw new Error('成绩格式发生变化');
        if (list.length > 300) throw new Error('成绩数据过大');
        return list.map(g => {
            const c = g && g.course || {};
            const name = bounded(text(c.nameZh || c.name), 200, '课程名称');
            if (!name) throw new Error('成绩课程名称缺失');
            const detailHtml = typeof g.gradeDetail === 'string' ? g.gradeDetail : '';
            const parts = [];
            (detailHtml.match(/>([^<>]+)<\/span>/g) || []).forEach(s => { const t = stripTags(s.slice(1)); if (t) parts.push(t); });
            (detailHtml.match(/(?:缓考成绩|补考成绩)[:：]\s*\d+(?:\.\d+)?/g) || []).forEach(s => { if (!parts.includes(s)) parts.push(s); });
            if (typeof g.fillAGrace === 'string' && text(g.fillAGrace)) parts.push(text(g.fillAGrace));
            const score = g.gaGrade == null ? '' : bounded(text(String(g.gaGrade)), 30, '成绩');
            return {semesterId: String(semesterId), semester: text(semesterName) || String(semesterId), name,
                code: bounded(text(c.code), 60, '课程代码'), type: bounded(text(g.courseType && g.courseType.nameZh), 60, '课程类别'),
                score, gp: number(g.gp), credits: number(c.credits), detail: bounded(parts.join('；'), 500, '成绩明细')};
        });
    }

    /* ---------- GPA ---------- */
    function normalizeGpa(json) {
        if (json === null) return null;            // school returns null when no GPA is published yet
        if (!json || typeof json !== 'object') throw new Error('绩点格式发生变化');
        const gpa = number(json.gpa);
        if (json.gpa == null) return null;
        if (gpa === null || gpa < 0 || gpa > 5) throw new Error('绩点格式发生变化');
        return Math.round(gpa * 1000) / 1000;
    }

    /* ---------- network ---------- */
    function allowed(location) {
        return location.protocol === 'https:' && location.hostname === 'jwxt.nwpu.edu.cn' && (!location.port || location.port === '443');
    }
    async function get(path, kind) {
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 20000);
        try {
            const response = await root.fetch(path, {method: 'GET', credentials: 'same-origin', redirect: 'follow', signal: controller.signal,
                headers: {'Accept': kind === 'json' ? 'application/json' : 'text/html', 'X-Requested-With': 'XMLHttpRequest'}});
            if (!response.ok) throw new Error('HTTP ' + response.status);
            if (response.url) {
                const u = new URL(response.url, root.location.href);
                if (!allowed(u) || /\/(?:login|sso-login)$/.test(u.pathname)) throw new Error('登录已失效');
            }
            const body = await response.text();
            if (body.length > 3000000) throw new Error('响应过大');
            return kind === 'json' ? JSON.parse(body) : body;
        } finally { clearTimeout(timer); }
    }
    function parseHtml(html) {
        if (typeof root.DOMParser !== 'function') throw new Error('无法解析页面');
        return new root.DOMParser().parseFromString(html, 'text/html');
    }
    async function exams(student) {
        let doc = parseHtml(await get('/student/for-std/exam-arrange', 'html'));
        // Several identities: the landing page is a chooser, so open the already-confirmed identity.
        if (!doc.querySelector('tr[data-finished]') && doc.querySelectorAll('button.footer.btn.btn-primary[value]').length)
            doc = parseHtml(await get('/student/for-std/exam-arrange/info/' + student, 'html'));
        return normalizeExams(examRows(doc));
    }
    async function grades(student, progress) {
        const sems = semesterOptions(await get('/student/for-std/grade/sheet/semester-index/' + student, 'html'));
        if (!sems.length) throw new Error('没有读到成绩学期');
        const results = new Map(), failed = []; let finished = 0;
        const current = () => sems.flatMap(s => results.get(s.id) || []);
        for (let i = 0; i < sems.length; i += 4) {           // small batches, gentle on the school server
            await Promise.all(sems.slice(i, i + 4).map(async s => {
                try { results.set(s.id, normalizeGrades(await get('/student/for-std/grade/sheet/info/' + student + '?semester=' + s.id, 'json'), s.id, s.name)); }
                catch (_) { failed.push(s.id); }
                finished++; progress(current(), Array.from(results.keys()), finished, sems.length);
            }));
        }
        if (!results.size) throw new Error('没有读到成绩');
        return {rows: current(), read: Array.from(results.keys()), failed};
    }
    function start(token, student) {
        root.__louAcademicResult = {token, status: 'loading'};
        (async function () {
            // Parts not yet finished count as failed, so a "partial" snapshot is always safe to save.
            const data = {version: 1, school: SCHOOL, exams: null, grades: null, gpa: null, failed: ['exams', 'gpa', 'grades']};
            if (!allowed(root.location) || !/^\d+$/.test(String(student))) {
                root.__louAcademicResult = {token, status: 'error', message: '请先登录教务系统'}; return;
            }
            const sid = String(student);
            let revision = 0;
            const publish = () => { root.__louAcademicResult = {token, revision: ++revision, status: 'partial', data: JSON.parse(JSON.stringify(data))}; };
            const done = (part, value) => {
                data[part] = value; data.failed = data.failed.filter(p => p !== part);
                publish();
            };
            await Promise.all([
                exams(sid).then(v => done('exams', v), () => {}),
                grades(sid, (rows, read, finished, total) => { data.grades = rows; data.gradeSemestersRead = read; data.gradeProgress = {finished, total}; publish(); })
                    .then(v => { data.gradeSemestersRead = v.read; data.gradeSemestersFailed = v.failed; if (!v.failed.length) done('grades', v.rows); else { data.grades = v.rows; publish(); } }, () => {}),
                get('/student/for-std/student-portrait/getMyGrades?studentAssoc=' + sid + '&semesterAssoc=', 'json')
                    .then(j => done('gpa', normalizeGpa(j)), () => {}),
            ]);
            root.__louAcademicResult = data.failed.length === 3 && !(data.gradeSemestersRead || []).length
                ? {token, status: 'error', message: '考试和成绩暂时没有读到'}
                : {token, status: 'ok', data};
        })().catch(() => { root.__louAcademicResult = {token, status: 'error', message: '考试和成绩暂时没有读到'}; });
    }
    const api = {normalizeExams, examRows, examTime, semesterOptions, normalizeGrades, normalizeGpa, allowed, start};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.NwpuAcademic = api;
})(typeof window === 'undefined' ? globalThis : window);
