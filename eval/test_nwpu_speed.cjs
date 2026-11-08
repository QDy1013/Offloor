// Controlled browser benchmark, not a real phone or school-server speed claim.
// Optional arg: nwpu-auto.js from v0.23, for a serial 800 ms polling baseline.
const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path');
const {performance}=require('node:perf_hooks');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const asset=name=>fs.readFileSync(path.join(__dirname,'../android/app/assets',name),'utf8');
const base=asset('nwpu-campus.js')+'\n'+asset('nwpu-sso.js');
const modern=asset('nwpu-auto.js');
const html='<h1>个人记录</h1><button onclick="window.query()">查询</button><table><thead><tr><th>编号</th><th>数量</th></tr></thead><tbody id=rows></tbody></table><ul class=el-pager><li class=active id=number>1</li></ul><div class=el-pagination><button class=btn-next onclick="window.next()">下一页</button></div><script>window.p=1;window.render=()=>{rows.innerHTML="<tr><td>record-"+p+"</td><td>1</td></tr>";number.textContent=p;document.querySelector(".btn-next").disabled=p>=3;};window.query=async()=>{await fetch("/fixture/query");p=1;render();};window.next=()=>{p++;render();};</script>';
(async()=>{
    const browser=await chromium.launch({headless:true,...(process.env.CAMPUS_BROWSER?{executablePath:process.env.CAMPUS_BROWSER}:{})});
    try {
        const context=await browser.newContext();
        await context.route('**/*',async route=>{ if(route.request().url().endsWith('/fixture/query')) await new Promise(r=>setTimeout(r,120)); await route.fulfill({contentType:'text/html; charset=utf-8',body:route.request().url().endsWith('/fixture/query')?'ok':html}); });
        async function one(host,kind,driver,poll){
            const page=await context.newPage(); await page.goto('https://'+host+'/personal'); await page.addScriptTag({content:base+'\n'+driver});
            const visited=[], records=new Set(),trace=[]; let prior='',stable=0;
            for(let i=0;i<90;i++){
                const r=await page.evaluate(({kind,visited})=>NwpuAuto.inspect(kind,visited),{kind,visited});
                const m=r.snapshot.modules[kind], fingerprint=JSON.stringify(m);
                trace.push({i,state:m.state,rows:m.tables.flatMap(t=>t.rows),settling:r.settling,actions:r.actions.map(a=>a.label)});
                if(r.settling||fingerprint!==prior){prior=fingerprint;stable=0;}else stable++;
                if(!r.settling&&stable){
                    for(const t of m.tables) for(const row of t.rows) records.add(row[0]);
                    if(!r.actions.length) break;
                    const action=r.actions[0];visited.push(action.key);await page.evaluate(({kind,key})=>NwpuAuto.act(kind,key),{kind,key:action.key});stable=0;
                }
                await new Promise(r=>setTimeout(r,poll));
            }
            await page.close(); if(records.size!==3) console.error(JSON.stringify({host,trace})); assert.deepEqual([...records].sort(),['record-1','record-2','record-3']); return records.size;
        }
        const cases=[['yktapp.nwpu.edu.cn','card'],['zizhu.nwpu.edu.cn','network'],['ic.nwpu.edu.cn','seats']];
        let baseline;
        if(process.argv[2]){ const legacy=fs.readFileSync(process.argv[2],'utf8'),start=performance.now();for(const [host,kind] of cases) await one(host,kind,legacy,800);baseline=Math.round(performance.now()-start); }
        const start=performance.now(),counts=await Promise.all(cases.map(([host,kind])=>one(host,kind,modern,100))),elapsed=Math.round(performance.now()-start);
        assert.equal(counts.reduce((a,b)=>a+b,0),9); if(baseline) assert.ok(elapsed<baseline*0.8,'parallel flow must improve the controlled serial baseline');
        console.log(JSON.stringify({scenario:'offline Chromium; three sites, query plus three pages; 120 ms synthetic query delay',baseline_serial_ms:baseline,parallel_ms:elapsed,records:9,synthetic_only:true}));
    } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
