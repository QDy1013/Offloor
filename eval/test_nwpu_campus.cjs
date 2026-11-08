// Real Chromium DOM tests; all pages and network responses below are synthetic.
// npm install --no-save playwright (or point PLAYWRIGHT_MODULE at an existing installation).
// Run: node eval/test_nwpu_campus.cjs [fixture-output.json]
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const api = require('../android/app/assets/nwpu-campus.js');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
let checks = 0;
function check(name, fn) { fn(); checks++; console.log('PASS ' + name); }
const source = fs.readFileSync(path.join(__dirname, '../android/app/assets/nwpu-campus.js'), 'utf8');
check('trust only school HTTPS origins with default port and no credentials', () => {
    for (const url of ['http://jwxt.nwpu.edu.cn/', 'https://jwxt.nwpu.edu.cn.evil.test/', 'https://nwpu.edu.cn:8443/', 'https://a:b@nwpu.edu.cn/']) assert.equal(api.allowed(url), false);
    assert.equal(api.allowed('https://ic.nwpu.edu.cn/'), true);
});
check('ticket URLs and write actions are not collected or discovered', () => {
    for (const url of ['/submit', '/student/delete/123', '/pay', '/x?ticket=PRIVATE', '/x?%74oken=PRIVATE', 'javascript:alert(1)', '//evil.test/x']) assert.equal(api.safeUrl(url, 'https://jwxt.nwpu.edu.cn/'), '');
    assert.equal(api.safeUrl('/student/for-std/plan?id=123', 'https://jwxt.nwpu.edu.cn/'), 'https://jwxt.nwpu.edu.cn/student/for-std/plan?id=123');
});
check('profile uses the already confirmed identity and selected basic fields', () => {
    const p = api.profile({student: {id: 123, code: 'SYNTHETIC001', nameZh: '合成同学', grade: 2026, major: {nameZh: '软件工程'}, idCard: 'PRIVATE', parent: {name: 'PRIVATE'}}}, '123');
    assert.equal(p.fields.find(x => x.label === '专业').value, '软件工程'); assert.equal(JSON.stringify(p).includes('PRIVATE'), false);
    assert.throws(() => api.profile({student: {id: 999, name: 'Wrong person'}}, '123'));
    assert.throws(() => api.profile({student: {id: 123}}, '123'));
});
check('electricity form separates selection from read-only balance query', () => {
    assert.equal(new URLSearchParams(api.electricForm(0, {})).get('type'), 'select');
    const p = new URLSearchParams(api.electricForm(3, {campus: 'A&B', building: '楼 1', room: '101'}));
    assert.equal(p.get('type'), 'IEC'); assert.equal(p.get('campus'), 'A&B'); assert.equal(p.get('room'), '101');
    assert.throws(() => api.electricForm(3, {campus: 'a'})); assert.throws(() => api.electricForm(4, {}));
});
check('electricity validates the observed school schema, negative balance remains real', () => {
    assert.deepEqual(api.electricNormalize({map: {data: [{name: '合成校区', value: 'C'}]}}, 0), [{name: '合成校区', value: 'C'}]);
    const m = api.electricNormalize({map: {data: {campus: '合成校区', building: 'A', room: '101'}, showData: {'当前剩余电量': '-1.5'}}}, 3);
    assert.equal(m.fields[1].value, '-1.5 度');
    for (const v of [null, '', 'NaN', '12度', Infinity, {}]) assert.throws(() => api.electricNormalize({map: {showData: {'当前剩余电量': v}}}, 3));
});

(async () => {
    const options = {headless: true};
    if (process.env.CAMPUS_BROWSER) options.executablePath = process.env.CAMPUS_BROWSER;
    const browser = await chromium.launch(options);
    try {
        const page = await browser.newPage();
        const requests = [];
        let home = '<a href="/student/for-std/observed-plan">我的培养方案</a><a href="/student/for-std/observed-progress">培养方案完成情况</a><a href="/submit">已选课程</a>';
        const table = '<h1>培养方案</h1><table><thead><tr><th>模块</th><th>要求学分</th><th>已修学分</th></tr></thead><tbody><tr><td>合成课程模块</td><td>20</td><td>12</td></tr></tbody></table>';
        await page.route('**/*', async route => {
            const u = new URL(route.request().url()); requests.push({url: u.href, method: route.request().method()});
            let body = '<html><body></body></html>', contentType = 'text/html';
            if (u.pathname.includes('/getStdInfo')) { contentType = 'application/json'; body = JSON.stringify({student: {id: 123, code: 'SYNTHETIC001', nameZh: '合成同学', grade: 2026, major: {nameZh: '软件工程'}}}); }
            else if (u.pathname === '/student/home') body = home;
            else if (u.pathname === '/student/for-std/observed-plan') body = table;
            else if (u.pathname === '/student/for-std/observed-progress') { await route.fulfill({status: 503, body: 'PRIVATE error response'}); return; }
            await route.fulfill({status: 200, contentType, body});
        });
        await page.goto('https://jwxt.nwpu.edu.cn/student/for-std/course-table/123');
        await page.addScriptTag({content: source});
        const extract = async (html, url, kind, live = false) => page.evaluate(({html,url,kind,live}) => {
            if (live) { document.body.innerHTML=html; return NwpuCampus.extract(document,url,kind,true); }
            return NwpuCampus.extract(new DOMParser().parseFromString(html,'text/html'),url,kind,false);
        }, {html,url,kind,live});
        const parsed = await extract(table, 'https://jwxt.nwpu.edu.cn/student/for-std/observed-plan', 'program');
        check('real DOM extracts headings, columns and data rows', () => {
            assert.equal(parsed.state,'ok'); assert.deepEqual(parsed.tables[0].columns,['模块','要求学分','已修学分']); assert.deepEqual(parsed.tables[0].rows,[['合成课程模块','20','12']]);
        });
        const login = await extract('<form><input type="password" value="PRIVATE"><table><tr><td>abc</td></tr></table></form>', 'https://zizhu.nwpu.edu.cn/', 'network');
        check('login form cannot be mistaken for a profile or an empty successful page', () => { assert.equal(login.state,'login'); assert.equal(JSON.stringify(login).includes('PRIVATE'),false); });
        const blank = await extract('<div id="app">正在加载</div>','https://jwxt.nwpu.edu.cn/student/for-std/observed-plan','program');
        check('dynamic loading placeholder stays unavailable', () => assert.equal(blank.state,'unavailable'));
        const portal = await extract(table,'https://ecampus.nwpu.edu.cn/main.html','sports');
        check('portal/dashboard content is not mislabelled as a different service', () => assert.equal(portal.state,'unavailable'));
        const wrong = await extract(table,'https://jwxt.nwpu.edu.cn/student/for-std/observed-plan','network');
        check('a service cannot collect from the wrong site', () => assert.equal(wrong.state,'unavailable'));
        const privacy = await extract('<dl><dt>姓名</dt><dd>合成同学</dd><dt>身份证号</dt><dd>PRIVATE</dd></dl><table><tr><th>课程</th><th>家庭住址</th></tr><tr><td>数学</td><td>PRIVATE</td></tr></table>', 'https://jwxt.nwpu.edu.cn/student/for-std/observed-plan','program');
        check('sensitive labels and table columns never enter snapshots', () => assert.equal(JSON.stringify(privacy).includes('PRIVATE'),false));
        const hidden = await extract('<table style="display:none"><tr><td>PRIVATE</td></tr></table>'+table,'https://jwxt.nwpu.edu.cn/student/for-std/observed-plan','program',true);
        check('live extraction excludes hidden DOM tables', () => assert.equal(JSON.stringify(hidden).includes('PRIVATE'),false));
        const scriptCell = await extract('<table><tr><th>课程</th></tr><tr><td>合成数学<script>window.secret="PRIVATE"</script><span hidden>PRIVATE</span></td></tr></table>', 'https://jwxt.nwpu.edu.cn/student/for-std/observed-plan','program');
        check('offline parsing never extracts inline scripts or hidden cell content', () => { assert.equal(scriptCell.tables[0].rows[0][0],'合成数学'); assert.equal(JSON.stringify(scriptCell).includes('PRIVATE'),false); });
        const urls = await page.evaluate(() => NwpuCampus.discover(new DOMParser().parseFromString('<a href="/student/for-std/plan">我的培养方案</a><a href="/delete">已选课程</a><a href="https://evil.test/">一卡通</a>','text/html'),'https://jwxt.nwpu.edu.cn/'));
        check('discover uses actual labelled read links and rejects destructive or external links', () => assert.deepEqual(urls,{program:'https://jwxt.nwpu.edu.cn/student/for-std/plan'}));
        // Restore the page so all requests go through the signed-in origin.
        await page.goto('https://jwxt.nwpu.edu.cn/student/for-std/course-table/123'); await page.addScriptTag({content:source});
        await page.evaluate(() => NwpuCampus.start('test-token','123'));
        await page.waitForFunction(() => ['ok','error'].includes(window.__louCampusResult.status));
        const result = await page.evaluate(() => window.__louCampusResult);
        check('full read discovers real plan URL and isolates another module failure', () => {
            assert.equal(result.status,'ok'); assert.equal(result.data.modules.profile.state,'ok'); assert.equal(result.data.modules.program.state,'ok');
            assert.equal(result.data.modules.completion.state,'network'); assert.equal(result.data.modules.selection.state,'unavailable'); assert.equal(JSON.stringify(result).includes('PRIVATE'),false);
            assert.equal(requests.every(r => r.method==='GET'),true); assert.equal(requests.some(r => r.url.includes('/submit')),false);
        });
        const maxRows = await extract('<table><tr><th>课程</th><th>学分</th></tr>'+Array.from({length:205},(_,i)=>`<tr><td>合成课${i}</td><td>1</td></tr>`).join('')+'</table>','https://jwxt.nwpu.edu.cn/student/for-std/observed-plan','program');
        check('a fully loaded table is no longer silently cut at 200 records', () => { assert.equal(maxRows.tables[0].rows.length,205); assert.equal(maxRows.tables[0].truncated,false); });
        const notices = await extract('<nav><a href="/info/1.htm">导航通知公告</a></nav><a href="/info/2.htm">2026年合成校历与校车时刻表</a><a href="/info/3.htm?ticket=PRIVATE">私人通知</a>','https://xygg.nwpu.edu.cn/xygg/xngg.htm','calendar');
        check('public info retains official links and drops nav or ticket links', () => { assert.equal(notices.links.length,1); assert.equal(notices.links[0].url,'https://xygg.nwpu.edu.cn/info/2.htm'); });
        if (process.argv[2]) fs.writeFileSync(process.argv[2],JSON.stringify(result.data));
        console.log(checks+' checks passed');
    } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode=1; });
