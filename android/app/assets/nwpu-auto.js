/* Read-only page driver. A signed-in batch navigates personal read pages, runs queries and
 * reads pagination automatically. It never submits applications, reservations or payments. */
(function (root) {
    'use strict';
    const text = v => typeof v === 'string' ? v.replace(/\s+/g, ' ').trim() : '';
    const sections = {
        profile: ['学籍信息', '我的信息', '个人信息', '基本信息'], program: ['我的培养方案', '培养方案内容', '培养方案'],
        completion: ['培养方案完成情况', '计划完成情况', '学业完成情况'], selection: ['已选课程', '选课结果', '我的选课'],
        evaluation: ['我的评教', '评教任务', '我的评价'], affairs: ['个人基本信息', '我的综合测评', '我的综测', '测评结果', '我的申请', '申请记录', '综合测评'],
        housing: ['我的宿舍', '住宿信息', '公寓信息', '智慧公寓'], card: ['我的账户', '账户信息', '账户余额', '余额查询', '消费明细', '交易明细', '账单查询'],
        network: ['账户信息', '我的账户', '上网信息', '流量查询', '余额查询'], library: ['我的图书馆', '我的借阅', '当前借阅', '借阅信息'],
        seats: ['我的预约', '预约记录', '我的预约记录'], classroom: ['空闲教室查询', '空闲教室'], sports: ['我的预约', '预约记录', '我的预约记录']
    };
    const state = root.__louAutoDriver || (root.__louAutoDriver = {pending: 0, started: Date.now()});
    function install() {
        if (state.installed) return; state.installed = true;
        if (typeof root.fetch === 'function') {
            const original = root.fetch;
            root.fetch = function () { state.pending++; return Promise.resolve().then(() => original.apply(this, arguments)).finally(() => { state.pending = Math.max(0, state.pending - 1); }); };
        }
        if (root.XMLHttpRequest) {
            const original = root.XMLHttpRequest.prototype.send;
            root.XMLHttpRequest.prototype.send = function () {
                state.pending++; let done = false;
                const finish = () => { if (!done) { done = true; state.pending = Math.max(0, state.pending - 1); } };
                this.addEventListener('loadend', finish, {once: true});
                try { return original.apply(this, arguments); } catch (e) { finish(); throw e; }
            };
        }
    }
    function visible(e) { return !!e && (!e.getClientRects || e.getClientRects().length > 0) && (!root.getComputedStyle || root.getComputedStyle(e).visibility !== 'hidden'); }
    function label(e) { return text(e.innerText || e.textContent || e.value || e.getAttribute('aria-label') || e.getAttribute('title')); }
    function disabled(e) { return e.disabled || e.getAttribute('aria-disabled') === 'true' || /(?:^|\s)(?:disabled|is-disabled|ant-pagination-disabled)(?:\s|$)/.test(e.className || ''); }
    function pageKey() {
        const u = new URL(root.location.href), page = root.document.querySelector('.el-pager .active,.ant-pagination-item-active,[aria-current="page"],.pagination .active');
        const rows = Array.from(root.document.querySelectorAll('tbody tr')).filter(visible).map(e => label(e)).join('|').slice(0, 12000);
        let hash = 2166136261; for (let i = 0; i < rows.length; i++) hash = Math.imul(hash ^ rows.charCodeAt(i), 16777619);
        return (root.NwpuCampus.safeUrl(u.href, u.href) || u.origin + u.pathname) + '|page:' + text(page && page.textContent).slice(0, 30) + ':' + (hash >>> 0);
    }
    function candidates(kind) {
        const doc = root.document, u = new URL(root.location.href), base = u.origin + u.pathname + u.hash, out = [];
        const allowed = sections[kind] || [];
        const elements = Array.from(doc.querySelectorAll('a[href],button,input[type="button"],input[type="submit"],[role="button"],.el-menu-item,.ant-menu-item,.el-tabs__item,.ant-tabs-tab,.nav-item,[onclick]')).filter(visible);
        // Default queries run once on each read page, after its own page controls are ready.
        for (const e of elements) if (/^(查询|搜索|查找)$/.test(label(e)) && !disabled(e)) out.push({key: base + '|query:' + label(e), label: label(e), element: e});
        for (const name of allowed) for (const e of elements) {
            if (label(e) !== name || disabled(e) || e.getAttribute('aria-selected') === 'true' || /(?:^|\s)(?:is-active|active|ant-menu-item-selected)(?:\s|$)/.test(e.className || '')) continue;
            const href = e.getAttribute('href'), inert = !href || href === '#' || /^javascript:\s*(?:void\(0\)|;?)\s*;?$/i.test(href);
            const target = !inert && root.NwpuCampus.safeUrl(href, root.location.href);
            if (!inert && !target) continue;
            if (target && target === root.location.href) continue;
            const key = target ? 'page:' + target : base + '|section:' + name;
            if (!out.some(a => a.key === key)) out.push({key, label: name, element: e});
        }
        const frames = Array.from(doc.querySelectorAll('iframe[src]')).filter(visible);
        if (frames.length === 1) {
            try {
                const src = new URL(frames[0].getAttribute('src'), root.location.href);
                if (root.NwpuCampus.safeUrl(src.href, root.location.href) && src.href !== root.location.href && src.hostname !== 'uis.nwpu.edu.cn') out.push({key: 'frame:' + src.href, label: '个人信息页', frame: frames[0]});
            } catch (_) { }
        }
        const next = Array.from(doc.querySelectorAll('.el-pagination .btn-next,.ant-pagination-next,.pagination [rel="next"],a[rel="next"],[aria-label="下一页"],[title="下一页"]')).find(e => visible(e) && !disabled(e));
        if (next) out.push({key: pageKey() + '|next', label: '下一页', element: next});
        return out;
    }
    function inspect(kind, visited) {
        install();
        if (!root.NwpuCampus.allowed(root.location.href)) return {pending: 0, actions: [], settling: false};
        const loading = Array.from(root.document.querySelectorAll('.el-loading-mask,.ant-spin-spinning,[aria-busy="true"]')).some(visible);
        const seen = new Set(visited || []), snapshot = root.NwpuCampus.capture(kind);
        return {pending: state.pending, settling: loading || state.pending > 0 || Date.now() - state.started < 1000,
            pageKey: pageKey(), snapshot, actions: candidates(kind).filter(a => !seen.has(a.key)).map(a => ({key: a.key, label: a.label}))};
    }
    function act(kind, key) {
        if (!root.NwpuCampus.allowed(root.location.href)) return false;
        const action = candidates(kind).find(a => a.key === key); if (!action) return false;
        if (action.frame) {
            // Ticket-bearing iframe addresses remain transient browser navigation, not exported data.
            root.location.assign(new URL(action.frame.getAttribute('src'), root.location.href).href); return true;
        }
        if (root.NwpuSso && root.NwpuSso.activateElement) return root.NwpuSso.activateElement(action.element);
        action.element.click(); return true;
    }
    const api = {install, inspect, act, pageKey};
    if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.NwpuAuto = api;
})(typeof window === 'undefined' ? globalThis : window);
