// Offline school-page fixtures in real Chromium; no account, cookie or school request.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const scripts = ['nwpu-campus.js', 'nwpu-sso.js', 'nwpu-auto.js'].map(f => fs.readFileSync(path.join(__dirname, '../android/app/assets', f), 'utf8')).join('\n');
let count = 0;
function check(name, fn) { fn(); count++; console.log('PASS ' + name); }
(async () => {
    const browser = await chromium.launch({headless: true, ...(process.env.CAMPUS_BROWSER ? {executablePath: process.env.CAMPUS_BROWSER} : {})});
    try {
        const context = await browser.newContext();
        await context.route('**/*', route => route.fulfill({contentType: 'text/html', body: '<main></main>'}));
        const page = await context.newPage();
        const load = async html => { await page.goto('https://yktapp.nwpu.edu.cn/personal'); await page.setContent(html); await page.addScriptTag({content: scripts}); await page.evaluate(() => NwpuAuto.install()); };
        await load(`<button onclick="document.getElementById('view').innerHTML='<button onclick=&quot;window.query();&quot;>查询</button><table><thead><tr><th>日期</th><th>金额</th></tr></thead><tbody id=rows></tbody></table><ul class=el-pager><li class=active id=number>1</li></ul><div class=el-pagination><button class=btn-next onclick=&quot;window.next();&quot;>下一页</button></div>'">消费明细</button><main id=view></main>
        <button onclick="window.bad=true">充值</button><button onclick="window.bad=true">提交</button><script>
        window.p=0; window.render=()=>{document.getElementById('rows').innerHTML='<tr><td>2026-11-0'+p+'</td><td>'+p+'</td></tr>';document.getElementById('number').textContent=p;document.querySelector('.btn-next').disabled=p>=3;};
        window.query=async()=>{window.queries=(window.queries||0)+1;await fetch('/fixture/read');window.p=1;render();}; window.next=()=>{p++;render();};</script>`);
        const visited = [], snapshots = []; let previous = '', stable = 0;
        // The app calls these same production entrypoints, automatically, without page clicks by a user.
        for (let i = 0; i < 90; i++) {
            const r = await page.evaluate(v => NwpuAuto.inspect('card', v), visited);
            const current = JSON.stringify(r.snapshot.modules.card);
            if (r.settling || current !== previous) { stable = 0; previous = current; }
            else stable++;
            if (!r.settling && stable > 0) {
                if (r.snapshot.modules.card.state === 'ok') snapshots.push(r.snapshot.modules.card);
                if (!r.actions.length) break;
                const a = r.actions[0]; visited.push(a.key);
                await page.evaluate(key => NwpuAuto.act('card', key), a.key); stable = 0;
            }
            await page.waitForTimeout(120);
        }
        check('personal section opens and default query runs without user actions', () => { assert.ok(visited.some(k => k.includes('section:消费明细'))); assert.equal(page.isClosed(), false); });
        check('read query executes once', () => assert.equal(visited.filter(k => k.includes('|query:')).length, 1));
        const rows = snapshots.flatMap(s => s.tables.flatMap(t => t.rows));
        check('all three pages are automatically captured including final page', () => assert.deepEqual([...new Set(rows.map(r => r[0]))].sort(), ['2026-11-01', '2026-11-02', '2026-11-03']));
        check('pagination actions are bounded and disabled final button is ignored', () => assert.equal(visited.filter(k => k.endsWith('|next')).length, 2));
        check('write actions are never candidates or clicked', () => { assert.ok(!visited.some(k => /提交|充值/.test(k))); });
        const effects = await page.evaluate(() => ({bad: !!window.bad, queries: window.queries}));
        check('page query handler ran once and write handlers did not', () => assert.deepEqual(effects, {bad: false, queries: 1}));

        await load('<dl><dt>余额</dt><dd>23元</dd></dl>');
        await page.evaluate(() => { window.fetch = () => new Promise(resolve => { window.finishRead = resolve; }); delete window.__louAutoDriver; });
        await page.addScriptTag({content: scripts});
        await page.evaluate(() => { NwpuAuto.install(); window.inflight = fetch('/fixture/slow'); });
        let r = await page.evaluate(() => NwpuAuto.inspect('card', []));
        check('in-flight read prevents premature completion', () => { assert.equal(r.pending, 1); assert.equal(r.settling, true); });
        await page.evaluate(async () => { finishRead({ok: true}); await inflight; });
        r = await page.evaluate(() => NwpuAuto.inspect('card', []));
        check('request counter settles without exporting response bodies', () => { assert.equal(r.pending, 0); assert.ok(!JSON.stringify(r).includes('fixture/slow')); });

        await load('<div class=el-table><table class=el-table__header><thead><tr><th>日期</th><th>金额</th></tr></thead></table><table class=el-table__body><tbody><tr><td>11月8日</td><td>3</td></tr></tbody></table></div>');
        r = await page.evaluate(() => NwpuAuto.inspect('card', []));
        check('split component headers are paired with body rows', () => assert.deepEqual(r.snapshot.modules.card.tables[0].columns, ['日期', '金额']));
        await load('<div class=el-table__empty-text>暂无记录</div>');
        r = await page.evaluate(() => NwpuAuto.inspect('card', []));
        check('explicit empty results are distinguished from failed or loading pages', () => assert.equal(r.snapshot.modules.card.state, 'empty'));
        await load('<button disabled>查询</button><a href="https://evil.test/">消费明细</a><a href="/pay">账户信息</a><a href="/personal?ticket=PRIVATE">账单查询</a>');
        r = await page.evaluate(() => NwpuAuto.inspect('card', []));
        check('disabled query, external, ticket and write links cannot be auto actions', () => assert.deepEqual(r.actions, []));
        await load('<iframe src="https://yktapp.nwpu.edu.cn/personal/details"></iframe>');
        r = await page.evaluate(() => NwpuAuto.inspect('card', []));
        check('single observed school read iframe opens automatically', () => assert.ok(r.actions.some(a => a.key.startsWith('frame:'))));
        await page.evaluate(k => NwpuAuto.act('card', k), r.actions[0].key);
        await page.waitForURL('https://yktapp.nwpu.edu.cn/personal/details');
        check('iframe navigation uses its actual address', () => assert.ok(page.url().endsWith('/personal/details')));
        await load('<table><tbody><tr><td id=record>1</td></tr></tbody></table><a rel=next href="#">下一页</a>');
        const before = await page.evaluate(() => NwpuAuto.pageKey());
        await page.evaluate(() => { document.getElementById('record').textContent = '2'; });
        const after = await page.evaluate(() => NwpuAuto.pageKey());
        check('pagination without a page indicator still tracks changed results', () => assert.notEqual(before, after));
        await load('<a href="javascript:void(0);" onclick="window.opened=true">消费明细</a>');
        r = await page.evaluate(() => NwpuAuto.inspect('card', []));
        await page.evaluate(k => NwpuAuto.act('card', k), r.actions[0].key);
        const opened = await page.evaluate(() => !!window.opened);
        check('inert SPA menu anchor uses its normal page handler automatically', () => assert.equal(opened, true));
        await browser.close(); console.log(count + ' checks passed');
    } finally { if (browser.isConnected()) await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
