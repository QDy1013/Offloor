// Synthetic first-party CAS server in real Chromium. No real school credentials or sessions.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const campus=fs.readFileSync(path.join(__dirname,'../android/app/assets/nwpu-campus.js'),'utf8');
const sso=fs.readFileSync(path.join(__dirname,'../android/app/assets/nwpu-sso.js'),'utf8');
let count=0;
function check(name,fn) { fn(); count++; console.log('PASS '+name); }
(async () => {
    const browser=await chromium.launch({headless:true,...(process.env.CAMPUS_BROWSER?{executablePath:process.env.CAMPUS_BROWSER}:{})});
    try {
        const context=await browser.newContext();
        const visits=[]; let centralForms=0;
        await context.route('**/*',async route => {
            const request=route.request(),u=new URL(request.url()); visits.push(u.hostname+u.pathname);
            // Chromium routing does not re-intercept the rest of an HTTP redirect chain.
            // Use first-party navigation redirects to keep every synthetic request offline.
            const redirect=target=>route.fulfill({contentType:'text/html',body:'<script>location.replace('+JSON.stringify(target)+')</script>'});
            if (u.hostname==='uis.nwpu.edu.cn' && u.pathname==='/cas/login') {
                if ((request.headers().cookie || '').includes('TGC=SYNTHETIC')) {
                    const target=new URL(u.searchParams.get('service')); target.searchParams.set('ticket','ST-SYNTHETIC');
                    return redirect(target.href);
                }
                centralForms++;
                return route.fulfill({contentType:'text/html',body:'<form id="fm1"><input id="username"><input id="password" type="password" value="PRIVATE"></form>'});
            }
            if (u.pathname==='/sso-entry') {
                const callback='https://'+u.hostname+'/callback';
                return redirect('https://uis.nwpu.edu.cn/cas/login?service='+encodeURIComponent(callback));
            }
            if (u.pathname==='/callback') {
                assert.equal(u.searchParams.get('ticket'),'ST-SYNTHETIC');
                return route.fulfill({contentType:'text/html',headers:{'set-cookie':'SESSION=SYNTHETIC; Secure; SameSite=Lax; Path=/'},body:'<script>location.replace('+JSON.stringify('https://'+u.hostname+'/home')+')</script>'});
            }
            return route.fulfill({contentType:'text/html',body:'<main id="app"><h1>本人信息</h1><dl><dt>姓名</dt><dd>合成同学</dd></dl></main>'});
        });
        const page=await context.newPage();
        const load=async (url,html) => {
            await page.goto(url); if (html!==undefined) await page.setContent(html);
            await page.addScriptTag({content:campus+'\n'+sso});
            return page.evaluate(() => NwpuSso.inspect());
        };
        let r=await load('https://uis.nwpu.edu.cn/cas/login?service=https%3A%2F%2Fecampus.nwpu.edu.cn','<div>正在跳转</div>');
        check('transient CAS page waits instead of asking for service credentials',() => assert.equal(r.phase,'redirecting'));
        r=await load('https://uis.nwpu.edu.cn/cas/login?service=https%3A%2F%2Fecampus.nwpu.edu.cn');
        check('only actual central form is identified as shared-login form',() => { assert.equal(r.phase,'central_login'); assert.equal(JSON.stringify(r).includes('PRIVATE'),false); });
        await page.setContent('<form id="fm1"><input id="password" type="password"></form><div class="geetest_panel">安全验证</div>');
        r=await page.evaluate(() => NwpuSso.inspect());
        check('visible school challenge pauses the handoff',() => assert.equal(r.phase,'challenge'));
        r=await load('https://zizhu.nwpu.edu.cn/login','<input type="password"><a href="https://uis.nwpu.edu.cn/cas/login?service=https%3A%2F%2Fzizhu.nwpu.edu.cn%2Fcallback">统一身份认证登录</a>');
        check('local service form follows its official SSO link first',() => { assert.equal(r.phase,'sso_link'); assert.ok(r.url.startsWith('https://uis.nwpu.edu.cn/cas/login?')); });
        r=await load('https://zizhu.nwpu.edu.cn/login','<input type="password">');
        check('form without observed SSO link is unresolved entry, not a new account requirement',() => assert.equal(r.phase,'service_login'));
        r=await load('https://ecampus.nwpu.edu.cn/main.html','<main id="app"><a href="/main.html">翱翔门户</a></main>');
        check('portal logo is not mistaken for a login action',() => assert.equal(r.phase,'ready'));
        await page.setContent('<main id="app"><div data-url="https://xgpt.nwpu.edu.cn/">学生工作平台</div></main>');
        await page.evaluate(() => { document.getElementById('app').__vue__={$data:{apps:[{appName:'一卡通',appUrl:'https://yktapp.nwpu.edu.cn/sso-entry'},{appName:'座位预约',appUrl:'https://ic.nwpu.edu.cn/home?ticket=PRIVATE'}],access_token:'PRIVATE'}}; });
        r=await page.evaluate(() => NwpuSso.inspect());
        check('portal app catalog yields only observed safe service URLs',() => { assert.equal(r.routes.card,'https://yktapp.nwpu.edu.cn/sso-entry'); assert.equal(r.routes.affairs,'https://xgpt.nwpu.edu.cn/'); assert.equal(r.routes.seats,undefined); assert.equal(JSON.stringify(r).includes('PRIVATE'),false); });
        await page.setContent('<button onclick="window.hit=(window.hit||0)+1">统一身份认证登录</button>');
        const once=await page.evaluate(() => [NwpuSso.click('sso','one-action'),window.hit]);
        await page.addScriptTag({content:sso});
        const twice=await page.evaluate(() => [NwpuSso.click('sso','one-action'),window.hit]);
        check('normal login button is clicked once across repeated script injection',() => { assert.deepEqual(once,[true,1]); assert.deepEqual(twice,[false,1]); });
        await context.addCookies([{name:'TGC',value:'SYNTHETIC',url:'https://uis.nwpu.edu.cn/',secure:true,sameSite:'Lax'}]);
        centralForms=0;
        await page.goto('https://yktapp.nwpu.edu.cn/sso-entry'); await page.waitForURL('https://yktapp.nwpu.edu.cn/home'); await page.addScriptTag({content:campus+'\n'+sso});
        const first=await page.evaluate(() => NwpuSso.inspect());
        const secondPage=await context.newPage(); await secondPage.goto('https://xgpt.nwpu.edu.cn/sso-entry'); await secondPage.waitForURL('https://xgpt.nwpu.edu.cn/home'); await secondPage.addScriptTag({content:campus+'\n'+sso});
        const second=await secondPage.evaluate(() => NwpuSso.inspect());
        check('one CAS session signs into two independent services and browser pages',() => { assert.equal(first.phase,'ready'); assert.equal(second.phase,'ready'); assert.equal(centralForms,0); });
        const cookies=await context.cookies();
        check('each service receives its own session while central login is shared',() => {
            assert.ok(cookies.some(c=>c.domain==='yktapp.nwpu.edu.cn' && c.name==='SESSION'));
            assert.ok(cookies.some(c=>c.domain==='xgpt.nwpu.edu.cn' && c.name==='SESSION'));
        });
        check('CAS tickets remain transient browser navigation data',() => { assert.equal(JSON.stringify([first,second]).includes('ST-SYNTHETIC'),false); assert.ok(visits.includes('uis.nwpu.edu.cn/cas/login')); });
        await page.goto('https://ecampus.nwpu.edu.cn/main.html'); await page.addScriptTag({content:campus+'\n'+sso});
        await page.setContent('<span onclick="setTimeout(()=>window.open(\'https://yktapp.nwpu.edu.cn/sso-entry\'),20)">一卡通</span>');
        await page.evaluate(() => NwpuSso.click('card','portal-open'));
        await page.waitForURL('https://yktapp.nwpu.edu.cn/home');
        check('normal async app handler opens ticket handoff in the same first-party view',() => assert.equal(page.url(),'https://yktapp.nwpu.edu.cn/home'));
        console.log(count+' checks passed');
    } finally { await browser.close(); }
})().catch(e=>{console.error(e);process.exitCode=1;});
