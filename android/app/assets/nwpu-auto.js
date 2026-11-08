/* Automatic personal read pages: history filters, records, details and pagination.
 * Every action is an observed page control. No application/payment/booking is submitted. */
(function (root) {
    'use strict';
    const text = v => typeof v === 'string' ? v.replace(/\s+/g, ' ').trim() : '';
    const sections = {
        profile: ['学籍信息', '我的信息', '个人信息', '基本信息'], program: ['我的培养方案', '培养方案内容', '培养方案'],
        completion: ['培养方案完成情况', '计划完成情况', '学业完成情况'], selection: ['已选课程', '选课结果', '我的选课'],
        evaluation: ['我的评教', '评教任务', '我的评价'], affairs: ['个人基本信息', '我的综合测评', '我的综测', '测评结果', '我的申请', '申请记录', '综合测评'],
        housing: ['我的宿舍', '住宿信息', '公寓信息', '智慧公寓'], card: ['我的账户', '账户信息', '账户余额', '余额查询', '消费明细', '交易明细', '账单查询', '充值记录'],
        network: ['账户信息', '我的账户', '上网信息', '流量查询', '余额查询', '缴费记录', '充值记录', '上网记录'], library: ['我的图书馆', '我的借阅', '当前借阅', '借阅信息', '借阅历史', '历史借阅', '欠费信息'],
        seats: ['我的预约', '预约记录', '我的预约记录', '历史预约'], classroom: ['空闲教室查询', '空闲教室'], sports: ['我的预约', '预约记录', '我的预约记录', '历史预约']
    };
    const state = root.__louAutoDriver || (root.__louAutoDriver = {pending: 0, started: Date.now(), detail: ''});
    const focused = {
        network:['我的账户','账户信息','余额查询','上网信息','流量查询'], card:['我的账户','账户信息','账户余额','余额查询','消费明细','交易明细','账单查询'],
        electric:['电费查询','宿舍电费'], housing:['我的宿舍','住宿信息','公寓信息','智慧公寓'], classroom:['空闲教室查询','空闲教室'],
        bus:['校车查询','校车服务','校车时刻','校车时刻表','西北工业大学校历、校车时刻表'],
        deadlines:['我的评教','学生评教','评教任务','我的选课','选课安排','选课时间','选课结果','时间安排']
    };
    function focus() { state.focus=true; root.__louCampusFocus=true; }
    function install() {
        if (state.installed) return; state.installed = true;
        state.changed = Date.now();
        if (root.MutationObserver && root.document) {
            state.observer = new root.MutationObserver(() => { state.changed = Date.now(); });
            state.observer.observe(root.document, {subtree: true, childList: true, characterData: true});
        }
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
    function label(e) { return text(e && (e.innerText || e.textContent || e.value || e.getAttribute('aria-label') || e.getAttribute('title'))); }
    function disabled(e) { return e.disabled || e.getAttribute('aria-disabled') === 'true' || /(?:^|\s)(?:disabled|is-disabled|ant-pagination-disabled)(?:\s|$)/.test(e.className || ''); }
    function baseUrl() { return root.NwpuCampus.safeUrl(root.location.href, root.location.href) || root.location.origin + root.location.pathname; }
    function dialog() { return Array.from(root.document.querySelectorAll('[role="dialog"],.el-dialog,.ant-modal,.layui-layer-page')).find(visible); }
    function scope() { return dialog() || root.document; }
    function pageKey() {
        const doc = scope(), page = doc.querySelector('.el-pager .active,.ant-pagination-item-active,[aria-current="page"],.pagination .active');
        const rows = Array.from(doc.querySelectorAll('tbody tr')).filter(visible).map(label).join('|').slice(0, 12000);
        let hash = 2166136261; for (let i = 0; i < rows.length; i++) hash = Math.imul(hash ^ rows.charCodeAt(i), 16777619);
        return baseUrl() + '|page:' + text(page && page.textContent).slice(0, 30) + ':' + (hash >>> 0);
    }
    function writable(name) {
        return /^(?:新增|新建|编辑|修改|删除|提交|保存|确认|立即|我要|办理|签到|签退|取消|撤销|续借|借出|预订|支付|充值$|缴费$|预约$|申请$|报名$|评教$|退出|注销|重置)/.test(name);
    }
    function readLabel(name, kind) {
        if (!name || name.length > 50 || writable(name)) return false;
        if (state.focus) return (focused[kind] || []).includes(name);
        if ((sections[kind] || []).includes(name)) return true;
        return /^(?:我的|个人|本人).*(?:信息|中心|记录|明细|查询|结果|状态|账户|借阅|申请|预约|奖|补助|考勤|请假|体测|课|学分|缴费|事务)$/.test(name) ||
            /^(?:基本|学籍|住宿|联系|学业|体测|体质|奖助|奖学金|助学金|学费|缴费|借阅|预约|考勤|请假|申请|审批|消费|交易|充值|上网|流量|账户|余额|历史|历年|历次).*(?:信息|记录|明细|查询|结果|状态|账单|历史|进度|余额|列表)$/.test(name);
    }
    function safeTarget(e) {
        const href = e.getAttribute('href'), inert = !href || href === '#' || /^javascript:\s*(?:void\(0\)|;?)\s*;?$/i.test(href);
        return inert ? '' : root.NwpuCampus.safeUrl(href, root.location.href);
    }
    function validLink(e) {
        const href = e.getAttribute('href');
        return !href || href === '#' || /^javascript:\s*(?:void\(0\)|;?)\s*;?$/i.test(href) || !!safeTarget(e);
    }
    function filterName(e) {
        const doc = root.document, bound = e.id && doc.querySelector('label[for="' + root.CSS.escape(e.id) + '"]');
        const wrap = e.closest('.el-form-item,.ant-form-item,.form-group,.layui-form-item');
        return text(bound && bound.textContent || e.getAttribute('aria-label') || (wrap && wrap.querySelector('label,.el-form-item__label,.ant-form-item-label') || {}).textContent || e.name || e.id || '').replace(/[：:]/g, '');
    }
    function filters(doc) {
        return Array.from(doc.querySelectorAll('select')).filter(e => visible(e) && !disabled(e) && /学年|学期|年度|年份|记录类型|交易类型|账单类型|查询范围|状态|semester|year|recordType|tradeType|status/i.test(filterName(e)) && !/学生|身份|账号|账户|用户|student|identity|user/i.test(filterName(e)))
            .map((e, i) => {
                let options = Array.from(e.options).filter(o => !o.disabled && text(o.textContent) && !/^(请选择|选择|--|学年|学期)$/.test(text(o.textContent)));
                const all = options.find(o => /^(全部|所有|不限|全部学期|所有学期|全部学年|所有状态|全部状态)$/.test(text(o.textContent)));
                if (all) options = [all];
                return {element: e, key: e.name || e.id || 'filter' + i, name: filterName(e), options};
            }).filter(f => f.options.length);
    }
    function filterKey(list) { return list.map(f => f.key + '=' + f.element.value).join('&'); }
    function filterContext(list) { return list.map(f => text(f.element.options[f.element.selectedIndex] && f.element.options[f.element.selectedIndex].textContent)).filter(v => v && !/^(全部|所有|不限)/.test(v)).join(' · '); }
    function customFilters(doc) {
        return Array.from(doc.querySelectorAll('.el-select,.ant-select,.layui-form-select')).filter(e => visible(e) && !disabled(e) && /学年|学期|年度|年份|交易类型|记录类型|状态|semester|year|status/i.test(filterName(e)) && !/学生|身份|账号|账户|用户/.test(filterName(e))).map((e, i) => {
            const input = e.querySelector('input'), selection = e.querySelector('.ant-select-selection-item,.el-select__selected-item');
            return {element: e, key: filterName(e) || 'select' + i, value: text(input && input.value || selection && selection.textContent), click: input || e};
        });
    }
    function customKey(list) { return list.map(f => f.key + '=' + f.value).join('&'); }
    function viewKey() {
        const heading = root.document.querySelector('h1,.page-title,.panel-title,.el-breadcrumb,.ant-breadcrumb');
        return baseUrl() + '|view:' + text(state.view || (heading && heading.textContent)).slice(0, 120);
    }
    function filterActions(list, base, seen, queryName, suffix = '') {
        let combinations = [[]];
        for (const f of list) {
            const next = [];
            for (const prefix of combinations) for (const option of f.options) { if (next.length >= 1000) break; next.push(prefix.concat([{filter: f, value: option.value}])); }
            combinations = next;
        }
        return combinations.filter(combo => combo.some(v => v.filter.element.value !== v.value) && !seen.has(base + '|query:' + queryName + ':' + combo.map(v => v.filter.key + '=' + v.value).join('&') + suffix)).map(combo => ({type: 'filter', key: base + '|filters:' + combo.map(v => v.filter.key + '=' + v.value).join('&') + suffix, label: '读取其他历史条件', values: combo}));
    }
    function candidates(kind, seen = new Set()) {
        const doc = scope(), base = baseUrl(), out = [], list = filters(doc), custom = customFilters(doc), variant = filterKey(list) + customKey(custom);
        const elements = Array.from(doc.querySelectorAll('a[href],button,input[type="button"],input[type="submit"],[role="button"],.el-menu-item,.ant-menu-item,.el-tabs__item,.ant-tabs-tab,.nav-item,[onclick]')).filter(visible);
        const modal = dialog();
        if (modal) {
            const close = modal.querySelector('.el-dialog__headerbtn,.ant-modal-close,.layui-layer-close,[aria-label="Close"],[aria-label="关闭"]') || elements.find(e => /^(关闭|返回)$/.test(label(e)) && !disabled(e));
            if (close) out.push({type: 'close', key: base + '|close:' + state.detail, label: '返回记录列表', element: close});
            return out;
        }
        if (state.filterPopup) {
            const options = Array.from(root.document.querySelectorAll('.el-select-dropdown__item,.el-select-dropdown__option,.ant-select-item-option,.layui-form-select dd')).filter(e => visible(e) && !disabled(e));
            if (options.length) {
                let values = options.filter(e => label(e) && !/^(请选择|--)$/.test(label(e)));
                const all = values.find(e => /^(全部|所有|不限|全部学期|所有学期|全部状态|所有状态)$/.test(label(e))); if (all) values = [all];
                state.filterOptions = state.filterOptions || {}; state.filterOptions[viewKey() + '|' + state.filterPopup.name] = values.map(label);
                const choices = values.filter(e => label(e) !== state.filterPopup.current).map(e => ({type: 'chooseFilter', key: state.filterPopup.base + '|option:' + label(e), label: '读取' + label(e), element: e})).filter(a => !seen.has(a.key));
                return choices.length ? choices : [{type: 'closeFilter', key: state.filterPopup.base + '|close:' + state.filterPopup.current, label: '筛选条件读取完成', element: state.filterPopup.trigger}];
            }
            return [];
        }
        const query = elements.find(e => /^(查询|搜索|查找)$/.test(label(e)) && !disabled(e) && !e.closest('header,nav,.navbar,.sidebar'));
        if (query && list.some(f => f.options.length === 1 && /^(全部|所有|不限)/.test(text(f.options[0].textContent)) && f.element.value !== f.options[0].value)) out.push(...filterActions(list, viewKey(), seen, label(query), customKey(custom)));
        if (query && validLink(query)) out.push({type: 'query', key: viewKey() + '|query:' + label(query) + ':' + variant, label: label(query), element: query});
        for (const e of elements) {
            const name = label(e);
            if (disabled(e) || !/^(查看|详情|详细|查看详情|详细信息|记录详情|成绩详情|账单详情)$/.test(name) || !validLink(e)) continue;
            const row = e.closest('tr,.el-descriptions,.record-item,.list-item');
            if (!row) continue;
            const context = (row.querySelector('td') ? Array.from(row.querySelectorAll('td')).slice(0, 2).map(label).join(' · ') : label(row)).slice(0, 120);
            const target = safeTarget(e); if (target && /\.(?:pdf|docx?|xlsx?|zip)(?:[?#]|$)/i.test(target)) continue;
            const rowIndex = Array.from(row.parentElement.children).indexOf(row);
            out.push({type: 'detail', key: pageKey() + '|detail:' + (target || context + ':' + rowIndex), label: name, context, url: target, element: e});
        }
        for (const e of doc.querySelectorAll('.virtual-list,.rc-virtual-list-holder,.vue-recycle-scroller,.el-table-v2__body,.ant-table-tbody-virtual,[data-virtualized="true"]')) {
            if (visible(e) && e.clientHeight > 0 && e.scrollTop + e.clientHeight < e.scrollHeight - 3) out.push({type: 'scroll', key: pageKey() + '|scroll:' + variant + ':' + Math.round(e.scrollTop), label: '读取后续记录', element: e});
        }
        const next = Array.from(doc.querySelectorAll('.el-pagination .btn-next,.ant-pagination-next,.pagination [rel="next"],a[rel="next"],[aria-label="下一页"],[title="下一页"]')).find(e => visible(e) && !disabled(e) && validLink(e));
        if (next && (!state.focus || ['deadlines','classroom'].includes(kind))) out.push({type: 'next', key: pageKey() + '|next', label: '下一页', element: next});
        if (!state.focus && query && list.length) out.push(...filterActions(list, viewKey(), seen, label(query), customKey(custom)));
        if (!state.focus && query) for (const f of custom) {
            const other = custom.filter(v => v !== f), key = viewKey() + '|custom:' + f.key + ':' + filterKey(list) + customKey(other);
            const known = state.filterOptions && state.filterOptions[viewKey() + '|' + f.key];
            if (known && known.every(value => value === f.value || seen.has(key + '|option:' + value))) continue;
            out.push({type: 'openFilter', key: key + '|open:' + f.value, label: '读取其他' + f.key, element: f.click, filter: {name: f.key, current: f.value, base: key}});
        }
        for (const e of elements) {
            const name = label(e); if (!readLabel(name, kind) || disabled(e) || !validLink(e)) continue;
            if (e.getAttribute('aria-selected') === 'true' || /(?:^|\s)(?:is-active|active|ant-menu-item-selected)(?:\s|$)/.test(e.className || '')) continue;
            const target = safeTarget(e);
            if (target === root.location.href || (target && /\.(?:pdf|docx?|xlsx?|zip)(?:[?#]|$)/i.test(target))) continue;
            const key = target ? 'page:' + target : base + '|section:' + name;
            if (!out.some(a => a.key === key)) out.push({type: 'navigate', key, label: name, url: target, element: e});
        }
        for (const frame of Array.from(doc.querySelectorAll('iframe[src]')).filter(visible)) {
            try {
                const url = new URL(frame.getAttribute('src'), root.location.href).href;
                if (root.NwpuCampus.safeUrl(url, root.location.href) && url !== root.location.href && new URL(url).hostname !== 'uis.nwpu.edu.cn') out.push({type: 'navigate', key: 'frame:' + url, label: text(frame.title) || '个人信息页', url, frame});
            } catch (_) { }
        }
        return out;
    }
    function inspect(kind, visited, context) {
        install();
        if (!root.NwpuCampus.allowed(root.location.href)) return {pending: 0, actions: [], settling: false};
        const loading = Array.from(root.document.querySelectorAll('.el-loading-mask,.ant-spin-spinning,[aria-busy="true"]')).some(visible);
        const seen = new Set(visited || []), snapshot = root.NwpuCampus.capture(kind, scope()), m = snapshot.modules[kind];
        const group = text(context || state.detail || [filterContext(filters(scope())), customFilters(scope()).map(f => f.value).filter(v => v && !/^(全部|所有|不限)/.test(v)).join(' · ')].filter(Boolean).join(' · ')).slice(0, 180);
        if (group) {
            for (const f of m.fields) { f.group = group; f.record = (baseUrl() + (state.detailKey || '')).slice(0, 2500); }
            for (const t of m.tables) t.title = (group + (t.title ? ' · ' + t.title : '')).slice(0, 100);
        }
        const actions = candidates(kind, seen).filter(a => !seen.has(a.key));
        state.planned = actions;
        const query = Array.from(scope().querySelectorAll('button,input[type="button"],input[type="submit"],[role="button"]')).find(e => visible(e) && /^(查询|搜索|查找)$/.test(label(e)) && !disabled(e));
        const queryKey = query && viewKey() + '|query:' + label(query) + ':' + filterKey(filters(scope())) + customKey(customFilters(scope()));
        return {pending: state.pending, settling: loading || state.pending > 0 || root.document.readyState === 'loading' || Date.now() - state.changed < 160, pageKey: pageKey(), snapshot,
            preparing: !!query && !seen.has(queryKey),
            actions: actions.map(a => ({key: a.key, type: a.type, label: a.label, url: a.url || '', context: a.context || ''}))};
    }
    function act(kind, key) {
        if (!root.NwpuCampus.allowed(root.location.href)) return false;
        const action = (state.planned || []).find(a => a.key === key) || candidates(kind).find(a => a.key === key); if (!action) return false;
        if (action.type === 'filter') {
            for (const v of action.values) { if (v.filter.element.value === v.value) continue; v.filter.element.value = v.value; v.filter.element.dispatchEvent(new root.Event('change', {bubbles: true})); }
            return true;
        }
        if (action.type === 'scroll') { action.element.scrollTop += Math.max(20, Math.floor(action.element.clientHeight * 0.85)); action.element.dispatchEvent(new root.Event('scroll', {bubbles: true})); return true; }
        if (action.type === 'openFilter') state.filterPopup = Object.assign({trigger: action.element}, action.filter);
        if (action.type === 'chooseFilter' || action.type === 'closeFilter') state.filterPopup = null;
        if (action.type === 'detail' && !action.url) { state.detail = action.context; state.detailKey = action.key; }
        if (action.type === 'close' || action.type === 'navigate') { state.detail = ''; state.detailKey = ''; }
        if (action.type === 'navigate') state.view = action.label;
        if (action.frame) { root.location.assign(action.url); return true; }
        if (root.NwpuSso && root.NwpuSso.activateElement) return root.NwpuSso.activateElement(action.element);
        action.element.click(); return true;
    }
    const api = {install, focus, inspect, act, pageKey};
    if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.NwpuAuto = api;
})(typeof window === 'undefined' ? globalThis : window);
