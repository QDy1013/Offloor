/* Shared CAS session handoff. Never read, retain or forward credentials. Transient CAS
 * redirects are different from a settled central login form or a service-specific landing page. */
(function (root) {
    'use strict';
    const text = v => typeof v === 'string' ? v.replace(/\s+/g, ' ').trim() : '';
    const labels = {
        profile: /^(学生画像|我的学籍|学籍信息)$/, program: /^(我的培养方案|培养方案)$/,
        completion: /^(培养方案完成情况|计划完成情况)$/, selection: /^(选课结果|已选课程)$/, evaluation: /^(学生评教|评教任务|评教)$/,
        card: /^(一卡通|一卡通服务|校园卡)$/, electric: /^(电费|宿舍电费|公寓电费|电费查询)$/,
        network: /^(网络自助|校园网|网费|网络服务|深澜计费自助服务)$/,
        library: /^(图书馆|我的图书馆|借阅查询|图书借阅)$/, seats: /^(空间预约|座位预约|图书馆座位预约)$/,
        classroom: /^(空闲教室|空闲教室查询|自习教室查询)$/, sports: /^(体育场馆个人预约|体育场馆预约|场馆预约)$/,
        bus: /^(校车查询|校车服务|校车时刻|校车时刻表)$/, deadlines: /^(我的评教|学生评教|评教任务|选课安排|选课时间)$/,
        affairs: /^(学生工作平台|学工系统|学工服务|综合测评)$/, housing: /^(智慧公寓|住宿信息|学生公寓|我的宿舍)$/
    };
    const central = /^(?:翱翔门户|统一身份认证|统一认证|统一登录|校园统一认证|CAS)(?:账号)?(?:登[录陆])?(?:入口)?$/i;
    function visible(e) {
        return !!e && (!e.getClientRects || e.getClientRects().length > 0) && (!root.getComputedStyle || root.getComputedStyle(e).visibility !== 'hidden');
    }
    function candidates(doc) { return Array.from(doc.querySelectorAll('a,button,[role="button"],[data-url],[data-href],.app-item,.application-item,.service-item,span,p')).filter(visible); }
    function label(e) { return text(e.getAttribute('title') || e.getAttribute('aria-label') || e.innerText || e.textContent); }
    function safe(value, base) { return root.NwpuCampus ? root.NwpuCampus.safeUrl(value, base) : ''; }
    function portalInfo(doc, url) {
        const out = root.NwpuCampus ? root.NwpuCampus.discover(doc, url) : {};
        const apps = [];
        if (new URL(url).hostname !== 'ecampus.nwpu.edu.cn' || !/^\/(?:main\.html)?$/.test(new URL(url).pathname)) return {routes: out, apps};
        const add = (name, href, icon = false) => {
            let u = safe(href, url);
            try { if (!u && icon && (!href || root.NwpuCampus.allowed(new URL(href, url).href))) u = 'https://ecampus.nwpu.edu.cn/main.html'; } catch (_) { }
            if (!u) return;
            let known = false;
            for (const [id, re] of Object.entries(labels)) if (re.test(text(name))) { known = true; if (!out[id] || (out[id] === 'https://ecampus.nwpu.edu.cn/main.html' && u !== out[id])) out[id] = u; }
            name = text(name);
            if (!known && name.length >= 2 && name.length <= 80 && !/^(首页|返回|退出|注销|登录|更多|应用中心|全部应用|全部服务|翱翔门户)$/.test(name) &&
                !/校历|校车|校园公告|成绩查询|课表查询|考试安排/.test(name) && new URL(u).hostname !== 'uis.nwpu.edu.cn' &&
                (icon || new URL(u).hostname !== 'ecampus.nwpu.edu.cn' || new URL(u).pathname !== '/main.html') &&
                !apps.some(a => a.title === name && a.url === u) && apps.length < 120) apps.push({title: name, url: u});
        };
        for (const e of candidates(doc)) {
            if (e.closest('header,footer,nav') && !e.closest('.app-item,.application-item,.service-item')) continue;
            add(label(e), e.getAttribute('href') || e.getAttribute('data-url') || e.getAttribute('data-href') || '', e.matches('.app-item,.application-item,.service-item'));
        }
        // The portal is a Vue app. Read only name/URL pairs already supplied for the current
        // user's app catalog; do not enumerate endpoints or export component state.
        const seen = new Set(), pending = [];
        for (const e of Array.from(doc.querySelectorAll('#app, .app-item, .application-item, .service-item')).slice(0, 100)) {
            const vm = e.__vue__; if (vm && vm.$data) pending.push([vm.$data, 0]);
        }
        let budget = 5000;
        while (pending.length && budget-- > 0) {
            const [obj, depth] = pending.shift();
            if (!obj || typeof obj !== 'object' || seen.has(obj) || depth > 6) continue; seen.add(obj);
            const name = obj.appName || obj.applicationName || obj.serviceName || obj.name || obj.title;
            const href = obj.appUrl || obj.accessUrl || obj.targetUrl || obj.linkUrl || obj.url || obj.href;
            if (typeof name === 'string' && (typeof href === 'string' || obj.appName || obj.applicationName || obj.serviceName)) add(name, typeof href === 'string' ? href : '', !!(obj.appName || obj.applicationName || obj.serviceName));
            for (const key of Object.keys(obj).slice(0, 100)) {
                if (/password|token|ticket|cookie|secret|credential|userInfo|profile/i.test(key)) continue;
                const value = obj[key]; if (value && typeof value === 'object') pending.push([value, depth + 1]);
            }
        }
        return {routes: out, apps};
    }
    function routes(doc, url) { return portalInfo(doc, url).routes; }
    function inspect(doc = root.document, url = root.location.href) {
        if (!root.NwpuCampus || !root.NwpuCampus.allowed(url)) return {phase: 'unavailable'};
        const u = new URL(url);
        const challenges = doc.querySelectorAll('.geetest_panel,.yidun_panel,.verifybox,.verify-box,.captcha-modal,#captcha_div');
        if ((u.hostname === 'uis.nwpu.edu.cn' && root.vm && root.vm.guardDialogVisible) || Array.from(challenges).some(visible)) return {phase: 'challenge'};
        if (u.hostname === 'uis.nwpu.edu.cn' && /^\/cas\/login\/?$/.test(u.pathname)) {
            const password = doc.querySelector('#fm1 input[type="password"],#password');
            return {phase: visible(password) ? 'central_login' : 'redirecting'};
        }
        if (/\/(?:sso-login|cas\/login)\/?$/.test(u.pathname)) return {phase: 'redirecting'};
        const hasPassword = Array.from(doc.querySelectorAll('input[type="password"]')).some(visible);
        const buttons = candidates(doc).filter(e => central.test(label(e)) && (hasPassword || /登录|登陆|统一|CAS/i.test(label(e))));
        for (const e of buttons) {
            const href = e.getAttribute('href') || e.getAttribute('data-url') || e.getAttribute('data-href');
            const target = href && safe(href, url);
            if (target && target !== url) return {phase: 'sso_link', url: target};
        }
        if (buttons.length) return {phase: 'sso_button'};
        if (hasPassword) return {phase: 'service_login'};
        if (/^(系统加载中[.。…]*|正在加载[.。…]*)$/.test(text(doc.body && doc.body.innerText))) return {phase: 'redirecting'};
        const portal = portalInfo(doc, url);
        return {phase: 'ready', routes: portal.routes, apps: portal.apps, busy: !!(root.__louAutoDriver && root.__louAutoDriver.pending > 0)};
    }
    const activated = root.__louSsoActivated || (root.__louSsoActivated = new Set());
    function click(kind, token) {
        const key = token + ':' + kind;
        if (activated.has(key) || !root.NwpuCampus || !root.NwpuCampus.allowed(root.location.href)) return false;
        const re = kind === 'sso' ? central : kind === 'catalog' ? /^(应用中心|应用系统|全部应用|全部服务)$/ : labels[kind];
        if (!re) return false;
        const e = candidates(root.document).find(e => re.test(label(e)) && (kind !== 'sso' || /登录|登陆|统一|CAS/i.test(label(e)))); if (!e) return false;
        activated.add(key);
        return activateElement(e);
    }
    function activateElement(e) {
        if (!e || !visible(e) || !root.NwpuCampus || !root.NwpuCampus.allowed(root.location.href)) return false;
        // Normal portal handlers may obtain an app ticket asynchronously and use window.open.
        // Keep that legitimate navigation in this first-party WebView; never export the ticket.
        if (!root.__louSsoOriginalOpen) root.__louSsoOriginalOpen = root.open;
        if (root.__louSsoOpenTimer) root.clearTimeout(root.__louSsoOpenTimer);
        root.open = function (value) {
            try { const u = new URL(value, root.location.href); if (root.NwpuCampus.allowed(u.href)) { root.location.assign(u.href); return null; } } catch (_) { }
            return null;
        };
        root.__louSsoOpenTimer = root.setTimeout(() => { root.open = root.__louSsoOriginalOpen; delete root.__louSsoOriginalOpen; }, 15000);
        e.click(); return true;
    }
    function clickService(name, token) {
        const key = token + ':named:' + name;
        if (activated.has(key)) return false;
        const e = candidates(root.document).find(e => label(e) === name); if (!e) return false;
        activated.add(key); return activateElement(e);
    }
    const api = {inspect, routes, click, clickService, activateElement};
    if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.NwpuSso = api;
})(typeof window === 'undefined' ? globalThis : window);
