// Inspect a saved, public school HTML page in real Chromium with all networking disabled.
// Usage: node eval/inspect_campus_public.cjs notices.html output.json
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
    const browser=await chromium.launch({headless:true,...(process.env.CAMPUS_BROWSER?{executablePath:process.env.CAMPUS_BROWSER}:{})});
    try {
        const page=await browser.newPage(); await page.route('**/*',route=>route.abort());
        await page.setContent(fs.readFileSync(process.argv[2],'utf8'));
        await page.addScriptTag({content:fs.readFileSync(path.join(__dirname,'../android/app/assets/nwpu-campus.js'),'utf8')});
        const modules=await page.evaluate(() => ({
            notices:NwpuCampus.extract(document,'https://xygg.nwpu.edu.cn/xygg/xngg.htm','notices',true),
            calendar:NwpuCampus.extract(document,'https://xygg.nwpu.edu.cn/xygg/xngg.htm','calendar',true)
        }));
        if (modules.notices.state!=='ok' || !modules.notices.links.length || modules.calendar.state!=='ok') throw new Error('Actual public page not recognized');
        const snapshot={version:1,school:'西北工业大学',checkedAt:new Date().toISOString(),modules};
        fs.writeFileSync(process.argv[3],JSON.stringify(snapshot,null,2));
        console.log(JSON.stringify({notices:modules.notices.links.length,calendar:modules.calendar.links.length,states:[modules.notices.state,modules.calendar.state]}));
    } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
