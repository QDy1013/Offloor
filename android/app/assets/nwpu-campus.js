/* Campus adapters. Read only: use the signed-in page's own origin; never export cookies,
 * passwords, arbitrary JSON or hidden inputs. Only follow read pages actually linked by
 * the school. Endpoint provenance and field-test status: docs/校园数据接入.md. */
(function (root) {
    'use strict';
    const SCHOOL = '西北工业大学';
    const targets = {
        program: /^(我的培养方案|培养方案内容|培养方案)$/,
        completion: /^(我的培养方案完成情况|培养方案完成情况|计划完成情况)$/,
        selection: /^(我的选课|选课结果|已选课程|本学期选课结果)$/,
        evaluation: /^(我的评教|学生评教|评教任务|评教)$/,
        deadlines: /^(我的评教|学生评教|评教任务|选课安排|选课时间|我的选课|选课结果)$/,
        card: /^(一卡通|校园卡|一卡通服务)$/,
        electric: /^(电费|宿舍电费|电费查询|公寓电费)$/,
        network: /^(网络自助|网费|校园网|网络服务)$/,
        library: /^(我的图书馆|借阅查询|当前借阅|图书借阅|图书馆)$/,
        seats: /^(空间预约|座位预约|图书馆座位预约)$/,
        classroom: /^(空闲教室查询|空闲教室|自习教室查询)$/,
        sports: /^(体育场馆个人预约|体育场馆预约|场馆预约)$/,
        affairs: /^(学生工作平台|学工系统|学工服务|综合测评)$/,
        housing: /^(智慧公寓|住宿信息|学生公寓|我的宿舍)$/,
        bus: /^(校车查询|校车服务|校车时刻|校车时刻表)$/,
        calendar: /^(校历|学年校历|西北工业大学校历、校车时刻表)$/
    };
    const clean = v => typeof v === 'string' || typeof v === 'number' ? String(v).replace(/\s+/g, ' ').trim() : '';
    const cut = (v, max = 800) => clean(v).slice(0, max);
    const sensitive = /密码|口令|验证码|身份证|证件号码|家庭|家长|监护人|银行卡|支付码|付款码|密钥|token|cookie|session|ticket|secret|password/i;
    function nodeText(el, live) {
        if (!el) return '';
        if (live && typeof el.innerText === 'string') return el.innerText;
        const copy = el.cloneNode(true);
        for (const e of copy.querySelectorAll('script,style,template,noscript,input,textarea,[hidden],[aria-hidden="true"],[style*="display:none"],[style*="display: none"]')) e.remove();
        return copy.textContent || '';
    }
    function allowed(value) {
        try {
            const u = new URL(value);
            return u.protocol === 'https:' && !u.username && !u.password && (!u.port || u.port === '443') &&
                (u.hostname === 'nwpu.edu.cn' || u.hostname.endsWith('.nwpu.edu.cn'));
        } catch (_) { return false; }
    }
    function safeUrl(value, base) {
        try {
            const u = new URL(value, base);
            if (!allowed(u.href) || /(?:^|\/)(?:logout|delete|remove|submit|save|pay|payment|recharge)(?:\/|$)/i.test(u.pathname)) return '';
            // Authentication tickets and action links must never be persisted or followed by the collector.
            if ([...u.searchParams.keys()].some(k => sensitive.test(k) || /action|operation|method/i.test(k))) return '';
            if (sensitive.test(decodeURIComponent(u.hash))) return '';
            if (!u.hash.startsWith('#/')) u.hash = '';
            return u.href;
        } catch (_) { return ''; }
    }
    function source(value) { try { const u = new URL(value); return allowed(value) ? u.origin + u.pathname : ''; } catch (_) { return ''; } }
    function visible(el, live) {
        if (!live || typeof el.getClientRects !== 'function') return true;
        return el.getClientRects().length > 0 && (!root.getComputedStyle || root.getComputedStyle(el).visibility !== 'hidden');
    }
    function isLogin(doc, url, live) {
        if (/\/cas\/login|\/student\/login|\/sso-login(?:\?|$)/.test(url)) return true;
        return Array.from(doc.querySelectorAll('input[type="password"]')).some(e => visible(e, live));
    }
    function discover(doc, base) {
        const found = {};
        for (const a of Array.from(doc.querySelectorAll('a[href], iframe[src][title]'))) {
            const label = clean(a.tagName === 'IFRAME' ? a.getAttribute('title') : nodeText(a, false)), href = a.getAttribute('href') || a.getAttribute('src') || '';
            if (!label || href.startsWith('#') || /^javascript:/i.test(href)) continue;
            const url = safeUrl(href, base); if (!url) continue;
            for (const [id, re] of Object.entries(targets)) if (!found[id] && re.test(label)) found[id] = url;
        }
        return found;
    }
    function empty(state, url) { return {state, source: source(url), fields: [], tables: [], links: []}; }
    function extract(doc, url, kind, live = false) {
        if (!allowed(url)) return empty('unsupported', '');
        if (isLogin(doc, url, live)) return empty('login', url);
        const host = new URL(url).hostname;
        const required = {profile: 'jwxt.nwpu.edu.cn', program: 'jwxt.nwpu.edu.cn', completion: 'jwxt.nwpu.edu.cn', selection: 'jwxt.nwpu.edu.cn', evaluation: 'jwxt.nwpu.edu.cn',
            card: 'yktapp.nwpu.edu.cn', electric: 'yktapp.nwpu.edu.cn', network: 'zizhu.nwpu.edu.cn', seats: 'ic.nwpu.edu.cn', classroom: 'idle-classroom.nwpu.edu.cn', deadlines: 'jwxt.nwpu.edu.cn'};
        if (kind === 'classroom' && host === 'jwxt.nwpu.edu.cn' && /^\/student\/for-std\/room-free(?:\/|$)/.test(new URL(url).pathname)) delete required.classroom;
        if (required[kind] && host !== required[kind]) return empty('unavailable', url);
        // Portal tiles and library news are entry points, not personal records. Wait until the
        // user (or a labelled link) has opened the actual service page before collecting data.
        if ((host === 'ecampus.nwpu.edu.cn' && /^\/(?:main\.html)?$/.test(new URL(url).pathname)) || host === 'uis.nwpu.edu.cn') return empty('unavailable', url);
        if (['program', 'completion', 'selection', 'evaluation'].includes(kind) && /\/student\/home\/?$/.test(new URL(url).pathname)) return empty('unavailable', url);
        if (kind === 'library' && !/借阅|应还|到期|续借/.test(Array.from(doc.querySelectorAll('h1,h2,h3,.panel-title,.page-title,th')).map(e => e.textContent).join(' '))) return empty('unavailable', url);
        const out = empty('unavailable', url), seen = new Set();
        const add = (label, value) => {
            label = cut(label, 80).replace(/[:：]\s*$/, ''); value = cut(value);
            if (!label || !value || sensitive.test(label) || label === value || seen.has(label + '\0' + value)) return;
            seen.add(label + '\0' + value); if (out.fields.length < 2000) out.fields.push({label, value});
        };
        for (const dt of doc.querySelectorAll('dt')) if (dt.nextElementSibling && dt.nextElementSibling.tagName === 'DD' && visible(dt, live)) add(nodeText(dt, live), nodeText(dt.nextElementSibling, live));
        for (const label of doc.querySelectorAll('.control-label, .el-form-item__label, .el-descriptions-item__label, .ant-descriptions-item-label, .form-label, .layui-form-label, .el-statistic__head, .ant-statistic-title, label[for]')) {
            if (!visible(label, live)) continue;
            if (/^(学年|学期|年度|年份|状态|交易类型|记录类型|查询范围)[:：]?$/.test(clean(nodeText(label, live)))) continue;
            const bound = label.getAttribute('for') && (doc.getElementById ? doc : doc.ownerDocument).getElementById(label.getAttribute('for'));
            if (bound && !/^(password|hidden)$/i.test(bound.type) && visible(bound, live)) {
                if (bound.readOnly || bound.disabled) add(nodeText(label, live), bound.tagName === 'SELECT' ? bound.options[bound.selectedIndex] && bound.options[bound.selectedIndex].text : bound.value);
                continue;
            }
            const value = label.nextElementSibling;
            if (value && !value.querySelector('input[type="password"]')) {
                const input = value.querySelector('input:not([type="hidden"]):not([type="password"]), textarea');
                if (!input || input.readOnly || input.disabled) add(nodeText(label, live), input ? input.value : nodeText(value, live));
            }
        }
        for (const label of doc.querySelectorAll('.info-label,.detail-label,.item-label')) {
            const value = label.nextElementSibling;
            if (visible(label, live) && value && visible(value, live) && !label.closest('nav,header,footer')) add(nodeText(label, live), nodeText(value, live));
        }
        for (const table of doc.querySelectorAll('table')) {
            if (!visible(table, live) || table.closest('nav, header, footer, .sidebar, .navbar, .menu') || table.querySelector('table')) continue;
            const rows = Array.from(table.querySelectorAll('tr')).filter(r => visible(r, live)).map(tr =>
                Array.from(tr.children).filter(c => /^(TD|TH)$/.test(c.tagName)).map(c => cut(nodeText(c, live), 600))
            ).filter(r => r.length && r.some(Boolean));
            if (!rows.length) continue;
            // Label/value tables are common in student profiles and network self service.
            const firstRow=table.querySelector('tr');
            const listHeader=!!table.querySelector('thead') || (firstRow && Array.from(firstRow.children).length>1 && Array.from(firstRow.children).every(c=>c.tagName==='TH'));
            const kv = !listHeader && rows.length > 0 && rows.every(r => r.length === 2 && r[0].length < 45) &&
                rows.some(r => /姓名|学号|年级|专业|余额|流量|套餐|状态|宿舍|电量|学分/.test(r[0]));
            if (kv) { rows.forEach(r => add(r[0], r[1])); continue; }
            const headerRow = table.querySelector('thead tr') || table.querySelector('tr');
            const hasHeader = !!(headerRow && headerRow.querySelector('th'));
            const component = table.closest('.el-table,.ant-table');
            const sharedHeaders = component && component.querySelector('.el-table__header thead tr,.ant-table-thead tr');
            let columns = hasHeader ? rows.shift() : sharedHeaders ? Array.from(sharedHeaders.querySelectorAll('th')).map(e => cut(nodeText(e, live), 600)) : rows[0].map((_, i) => '第' + (i + 1) + '列');
            // Never expose hidden credential fields or private family/identity columns.
            const keep = columns.map((c, i) => sensitive.test(c) ? -1 : i).filter(i => i >= 0).slice(0, 30);
            if (!keep.length) continue;
            const data = rows.filter(r => !r.some(cell => sensitive.test(cell))).slice(0, 10000).map(r => keep.map(i => r[i] || ''));
            if (!data.length || !data.some(r => r.some(Boolean))) continue;
            if (out.tables.length < 80) out.tables.push({title: cut(table.caption && table.caption.textContent || '', 100), columns: keep.map(i => columns[i]), rows: data, truncated: rows.length > 10000});
        }
        for (const grid of doc.querySelectorAll('[role="grid"],[role="table"]')) {
            if (grid.tagName === 'TABLE' || grid.querySelector('table') || !visible(grid, live)) continue;
            let columns = Array.from(grid.querySelectorAll('[role="columnheader"]')).filter(e => visible(e, live)).map(e => cut(nodeText(e, live), 600));
            const rows = Array.from(grid.querySelectorAll('[role="row"]')).filter(e => visible(e, live)).map(row => Array.from(row.querySelectorAll('[role="gridcell"],[role="cell"]')).filter(e => visible(e, live)).map(e => cut(nodeText(e, live), 600))).filter(row => row.length);
            if (!rows.length) continue;
            if (!columns.length) columns = rows[0].map((_, i) => '第' + (i + 1) + '列');
            const keep = columns.map((c, i) => sensitive.test(c) ? -1 : i).filter(i => i >= 0).slice(0, 30);
            if (keep.length && out.tables.length < 80) out.tables.push({title: '', columns: keep.map(i => columns[i]), rows: rows.slice(0, 10000).map(row => keep.map(i => row[i] || '')), truncated: rows.length > 10000});
        }
        // Text-only balance cards: require a labelled value, never collect entire body text.
        const text = (live && doc.body && doc.body.innerText || '').slice(0, 120000);
        const metrics = /((?:账户|帐户|校园卡|一卡通|可用|当前|剩余)?(?:总?余额|剩余电量|已用流量|剩余流量|使用流量|基础套餐|当前套餐))\s*[:：]\s*([^\n]{1,90})/g;
        let m; while ((m = metrics.exec(text))) add(m[1], m[2]);
        for (const a of doc.querySelectorAll('a[href]')) {
            if (!visible(a, live) || a.closest('nav,header,footer,.sidebar,.navbar,.menu')) continue;
            const href = safeUrl(a.getAttribute('href'), url), title = cut(nodeText(a, live), 180);
            if (title && /\.(?:pdf|docx?|xlsx?)(?:[?#]|$)/i.test(href) && !out.links.some(l => l.url === href) && out.links.length < 400) out.links.push({title, url: href});
        }
        const article = doc.querySelector('article,.article-content,.detail-content,.notice-content');
        if (article && visible(article, live)) {
            const body = cut(nodeText(article, live), 40000);
            for (let i = 0; i < body.length && out.fields.length < 80; i += 800) add(i ? '正文（续' + (i / 800 + 1) + '）' : '正文', body.slice(i, i + 800));
        }
        if (['notices', 'calendar', 'bus'].includes(kind)) {
            for (const a of doc.querySelectorAll('a[href]')) {
                if (!visible(a, live) || a.closest('nav, header, footer, .sidebar, .navbar, .menu')) continue;
                const title = cut(a.textContent, 180), href = a.getAttribute('href') || '';
                if (!title || title.length < 6 || !/通知|公告|校历|校车|班车|安排|讲座|报名|学术/.test(title)) continue;
                const u = safeUrl(href, url); if (!u || out.links.some(l => l.url === u)) continue;
                if (kind === 'calendar' && !/校历|校车|班车|放假/.test(title)) continue;
                if (kind === 'bus' && !/校车|班车/.test(title)) continue;
                if (out.links.length < 40) out.links.push({title, url: u});
            }
        }
        const count = out.fields.length + out.tables.reduce((n, t) => n + t.rows.length, 0) + out.links.length;
        if (kind === 'electric' && !out.fields.some(f => /电量|电费/.test(f.label)) && !out.tables.some(t => t.columns.some(c => /电量|电费/.test(c)))) return empty('binding', url);
        if (count) out.state = 'ok';
        else if (live && Array.from(doc.querySelectorAll('.el-table__empty-text,.ant-empty-description,.dataTables_empty,.no-data,.empty-data')).some(e => visible(e, true) && /暂无(?:数据|记录)|无(?:数据|记录)|没有(?:数据|记录)/.test(nodeText(e, true)))) out.state = 'empty';
        return out;
    }
    function profile(json, student) {
        const s = json && json.student;
        if (!s || String(s.id) !== String(student)) throw new Error('identity');
        const fields = [], add = (label, value) => { value = cut(value, 200); if (value) fields.push({label, value}); };
        const name = x => x && typeof x === 'object' ? x.nameZh || x.name || '' : '';
        add('姓名', s.nameZh || s.name); add('学号', s.code); add('年级', s.grade);
        add('学院', name(s.department)); add('专业', name(s.major)); add('班级', name(s.adminclass || s.adminClass));
        add('校区', name(s.campus)); add('学籍状态', name(s.stdStatus));
        if (!fields.length) throw new Error('schema');
        return {state: 'ok', source: 'https://jwxt.nwpu.edu.cn/student/for-std/student-portrait', fields, tables: [], links: []};
    }
    async function get(path, kind = 'html') {
        const base = root.location.href, target = new URL(path, base);
        if (target.origin !== root.location.origin || !safeUrl(target.href, base)) throw new Error('origin');
        const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 10000);
        try {
            const r = await root.fetch(target.href, {method: 'GET', credentials: 'same-origin', signal: controller.signal, headers: {'Accept': kind === 'json' ? 'application/json' : 'text/html'}});
            if (!r.ok || !r.url || new URL(r.url).origin !== target.origin || /\/login(?:\?|$)/.test(r.url)) throw new Error('login');
            const body = await r.text(); if (body.length > 2500000) throw new Error('size');
            return kind === 'json' ? JSON.parse(body) : new root.DOMParser().parseFromString(body, 'text/html');
        } finally { clearTimeout(timer); }
    }
    function start(token, student) {
        const data = {version: 1, school: SCHOOL, identity: String(student), modules: {}, routes: {}};
        root.__louCampusResult = {token, status: 'loading', data};
        (async () => {
            if (!/^\d{1,20}$/.test(String(student)) || root.location.origin !== 'https://jwxt.nwpu.edu.cn') throw new Error('origin');
            const publish = () => { root.__louCampusResult = {token, status: 'partial', data: JSON.parse(JSON.stringify(data))}; };
            const p = (async () => {
                try { data.modules.profile = profile(await get('/student/for-std/student-portrait/getStdInfo?bizTypeAssoc=2&cultivateTypeAssoc=1', 'json'), student); }
                catch (_) { data.modules.profile = empty('unavailable', 'https://jwxt.nwpu.edu.cn/student/for-std/student-portrait'); }
                publish();
            })();
            Object.assign(data.routes, discover(root.document, root.location.href));
            try { Object.assign(data.routes, discover(await get('/student/home'), 'https://jwxt.nwpu.edu.cn/student/home')); } catch (_) { }
            await Promise.all(['program', 'completion', 'selection', 'evaluation'].map(async id => {
                const url = data.routes[id];
                if (!url || new URL(url).origin !== root.location.origin) { data.modules[id] = empty('unavailable', 'https://jwxt.nwpu.edu.cn/student/home'); publish(); return; }
                try { data.modules[id] = extract(await get(url), url, id); } catch (_) { data.modules[id] = empty('network', url); }
                publish();
            }));
            await p; root.__louCampusResult = {token, status: 'ok', data};
        })().catch(() => { root.__louCampusResult = {token, status: 'error'}; });
    }
    // Confirmed portal widgets expose balances without a second service login. Restrict
    // extraction to the labelled widget, so another card's balance cannot be attributed.
    function portal(doc = root.document, url = root.location.href) {
        const modules = {};
        if (!allowed(url) || new URL(url).hostname !== 'ecampus.nwpu.edu.cn' || !/^\/(?:main\.html)?$/.test(new URL(url).pathname) || isLogin(doc,url,true)) return modules;
        for (const widget of doc.querySelectorAll('.hotdata-ul-li')) {
            if (!visible(widget,true)) continue;
            const heading = widget.querySelector('.hotdata-ul-li-top .left');
            const name = clean(heading && nodeText(heading,true));
            const id = name === '网费' ? 'network' : /^(校园卡|一卡通)$/.test(name) ? 'card' : '';
            if (!id) continue;
            const body = nodeText(widget,true);
            const matches = [...body.matchAll(/((?:账户|帐户|校园卡|一卡通|可用|总)?余额)\s*[:：]\s*[￥¥]?\s*(-?\d+(?:\.\d{1,2})?)\s*(?:元|人民币|RMB)?/g)];
            if (matches.length !== 1) continue;
            modules[id] = {state:'ok', source:source(url), fields:[{label:matches[0][1],value:matches[0][2]+' 元'}],tables:[],links:[]};
        }
        // The official HotData widget renders some card details in a collapsed panel.
        // Read only its confirmed balance/status fields, never the surrounding Vue state.
        for (const widget of doc.querySelectorAll('.hotdata-ul-li')) {
            let p=widget;
            for (let i=0;p && i<8;i++,p=p.parentElement) {
                const vm=p.__vue__; if (!vm || !vm.$options || vm.$options.name!=='HotData') continue;
                const card=vm.allData && vm.allData.schoolCard;
                const balance=card && (typeof card.balance==='number' || typeof card.balance==='string') ? String(card.balance).trim() : '';
                if (!card || card.SystemUpgrading || !/^-?\d+(?:\.\d{1,2})?$/.test(balance)) break;
                const fields=[{label:'校园卡余额',value:balance+' 元'}];
                if (typeof card.cardStatus==='string' && clean(card.cardStatus)) fields.push({label:'卡片状态',value:cut(card.cardStatus,100)});
                modules.card={state:'ok',source:source(url),fields,tables:[],links:[]}; break;
            }
            if (modules.card) break;
        }
        return modules;
    }
    function capture(kind, doc = root.document) {
        const url = root.location.href; let data = extract(doc, url, kind, true);
        if (kind === 'classroom' && data.state==='ok') {
            data.tables=data.tables.filter(t=>t.columns.some(c=>/教室|房间|场地|教室编号/.test(c)));
            if (!data.tables.length) data=empty('unavailable',url);
        }
        if (kind === 'deadlines') data = deadlineData(data);
        if (kind === 'bus') data = busData(data, doc);
        if (root.__louCampusFocus && kind === 'housing') {
            data.fields = data.fields.filter(f => /住宿校区|宿舍校区|校区|宿舍楼|楼栋|楼号|公寓楼|房间|宿舍号|寝室/.test(f.label)); data.tables = []; data.links = [];
            if (data.state === 'ok' && !data.fields.length) data.state = 'unavailable';
        }
        return {version: 1, school: SCHOOL, modules: {[kind]: data}, routes: discover(root.document, url)};
    }
    function identity(token, student) {
        if (root.location.origin !== 'https://jwxt.nwpu.edu.cn' || !/^\d{1,20}$/.test(String(student))) { root.__louCampusResult = {token, status: 'error'}; return; }
        root.__louCampusResult = {token, status: 'ok', data: {version: 1, school: SCHOOL, identity: String(student), modules: {}, routes: discover(root.document, root.location.href)}};
    }
    function deadlineTime(value) {
        const match = clean(value).match(/(20\d{2})[-年/.](\d{1,2})[-月/.](\d{1,2})日?(?:\s*[T ]\s*(\d{1,2})[:：](\d{2}))?/);
        if (!match) return null;
        const y=+match[1], month=+match[2], day=+match[3], hour=match[4]===undefined?23:+match[4], minute=match[5]===undefined?59:+match[5];
        const check=new Date(Date.UTC(y,month-1,day));
        if (month<1 || month>12 || day<1 || check.getUTCDate()!==day || hour>23 || minute>59) return null;
        return Date.UTC(y,month-1,day,hour-8,minute);
    }
    function deadlineData(raw) {
        if (raw.state !== 'ok') return raw;
        const out=empty('unavailable',raw.source), rows=[], seen=new Set(); let recognized=false;
        for (const table of raw.tables) {
            const column=table.columns.findIndex(c=>/截止|结束|最晚|到期/.test(c)); if (column<0) continue;
            recognized=true;
            for (const row of table.rows) {
                if (/已完成|已评价|已评教|已提交|已取消|已结束/.test(row.join(' '))) continue;
                const when=deadlineTime(row[column]); if (when===null || when<Date.now()) continue;
                const nameColumn=table.columns.findIndex(c=>/事项|任务名称|课程名称|项目名称|名称|^课程$/.test(c) && !/代码|编号/.test(c));
                const name=(nameColumn>=0?row[nameColumn]:'') || row.find((value,i)=>i!==column && value && !/^\d+$/.test(value) && !/未完成|未评价|未评教|待处理|未提交|进行中/.test(value)) || table.title || '待处理事项';
                const key=name+'|'+row[column]; if (!seen.has(key)) { seen.add(key); rows.push([cut(name,300),row[column]]); }
            }
        }
        const names=raw.fields.filter(f=>/事项|任务名称|课程名称|项目名称/.test(f.label));
        for (const f of raw.fields) if (/截止|结束|最晚|到期/.test(f.label) && deadlineTime(f.value)!==null && deadlineTime(f.value)>=Date.now()) {
            const name=f.group || (names.length===1?names[0].value:'') || (/评教|选课/.test(f.label)?f.label:'');
            if (name) rows.push([cut(name,300),f.value]);
        }
        rows.sort((a,b)=>deadlineTime(a[1])-deadlineTime(b[1]));
        if (rows.length) { out.state='ok'; out.tables.push({title:'待办截止',columns:['事项','截止时间'],rows,truncated:false}); }
        else if (recognized) out.state='empty';
        return out;
    }
    function busData(raw, doc) {
        if (raw.state !== 'ok') return raw;
        const heading=Array.from(doc.querySelectorAll('h1,h2,.article-title,.page-title')).map(e=>nodeText(e,true)).join(' '), isBus=/校车|班车/.test(heading);
        raw.fields=raw.fields.filter(f=>/发车|班次|乘车|候车|上车|下车|站点|线路|运行|运营/.test(f.label));
        raw.tables=raw.tables.filter(t=>t.columns.some(c=>/发车|出发|班次|站点|乘车|线路|校区/.test(c)) || (isBus && t.rows.some(row=>row.some(v=>/(?:[01]?\d|2[0-3])[:：][0-5]\d/.test(v)))));
        raw.links=raw.links.filter(l=>/校车|班车/.test(l.title) || (isBus && /时刻|班次|安排/.test(l.title)));
        if (!raw.fields.length && !raw.tables.length && !raw.links.length) raw.state='unavailable'; return raw;
    }
    function profileStart(token, student) {
        root.__louProfileResult = {token, status: 'loading'};
        (async () => {
            if (root.location.origin !== 'https://jwxt.nwpu.edu.cn' || !/^\d{1,20}$/.test(String(student))) throw new Error('origin');
            const data = {version: 1, school: SCHOOL, identity: String(student), modules: {profile: profile(await get('/student/for-std/student-portrait/getStdInfo?bizTypeAssoc=2&cultivateTypeAssoc=1', 'json'), student)}};
            root.__louProfileResult = {token, status: 'ok', data};
        })().catch(() => { root.__louProfileResult = {token, status: 'error'}; });
    }
    function electricForm(level, binding) {
        if (![0, 1, 2, 3].includes(level)) throw new Error('level');
        const b = binding || {}, p = new URLSearchParams({feeitemid: '182', type: level === 3 ? 'IEC' : 'select', level: String(level)});
        for (const [i, key] of ['campus', 'building', 'room'].entries()) if (level > i) {
            if (!clean(b[key]) || clean(b[key]).length > 120) throw new Error('binding'); p.set(key, clean(b[key]));
        }
        return p.toString();
    }
    function electricNormalize(json, level) {
        const map = json && json.map; if (!map) throw new Error('schema');
        if (level < 3) {
            if (!Array.isArray(map.data) || map.data.length > 5000) throw new Error('schema');
            return map.data.map(x => { const name = cut(x.name, 120), value = cut(x.value, 120); if (!name || !value) throw new Error('schema'); return {name, value}; });
        }
        const value = map.showData && map.showData['当前剩余电量'];
        const n = typeof value === 'number' ? value : typeof value === 'string' && /^-?\d+(?:\.\d+)?$/.test(value.trim()) ? +value : NaN;
        if (!Number.isFinite(n) || Math.abs(n) > 1000000) throw new Error('schema');
        return {state: 'ok', source: 'https://yktapp.nwpu.edu.cn/jfdt/charge/feeitem/getThirdData',
            fields: [{label: '当前剩余电量', value: n + ' 度'}], tables: [], links: []};
    }
    function electricStart(token, level, binding) {
        root.__louElectricResult = {token, status: 'loading'};
        (async () => {
            if (root.location.origin !== 'https://yktapp.nwpu.edu.cn') throw new Error('origin');
            const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 12000);
            try {
                const r = await root.fetch('/jfdt/charge/feeitem/getThirdData', {method: 'POST', credentials: 'same-origin', redirect: 'error', signal: controller.signal,
                    headers: {'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'}, body: electricForm(level, binding)});
                if (!r.ok) throw new Error('http'); const body = await r.text(); if (body.length > 2000000) throw new Error('size');
                root.__louElectricResult = {token, status: 'ok', data: electricNormalize(JSON.parse(body), level)};
            } finally { clearTimeout(timer); }
        })().catch(() => { root.__louElectricResult = {token, status: 'error'}; });
    }
    // 一卡通“交电费”页面：页面会按本账号默认（上次缴费）的宿舍显示剩余电量，不用逐级选校区/楼栋/房间。
    // 打开时要带一卡通令牌（由 CampusBatch 先走一卡通统一登录入口取得），否则返回 401。
    // 只取电量这个数，不读、不存宿舍信息。先找页面组件里的数据，找不到再看页面文字。
    const ELECTRIC_PAGE = 'https://yktapp.nwpu.edu.cn/jfdt/charge/feeitem/toAppitem?feeitemid=182&type=app&loginFrom=h5&appId=36';
    const ELECTRIC_KEY = /当前剩余电量|剩余电量|电量余额/;
    function electricNumber(v) {
        if (typeof v === 'number') return Number.isFinite(v) ? v : NaN;
        if (typeof v !== 'string') return NaN;
        const s = v.replace(/[,，\s]/g, '').replace(/(度|kWh|KWH)$/i, '');
        return /^-?\d+(?:\.\d+)?$/.test(s) ? +s : NaN;
    }
    function electricScan(obj, depth, seen) {
        if (!obj || typeof obj !== 'object' || depth > 6 || seen.has(obj)) return NaN;
        seen.add(obj);
        // 形如 {name:'当前剩余电量', value:'12.3'} 的条目
        const label = obj.name || obj.label || obj.title || obj.key;
        if (typeof label === 'string' && ELECTRIC_KEY.test(label)) {
            const n = electricNumber(obj.value !== undefined ? obj.value : obj.val);
            if (Number.isFinite(n)) return n;
        }
        const keys = Object.keys(obj).slice(0, 300);
        for (const k of keys) {
            let v; try { v = obj[k]; } catch (e) { continue; }
            if (ELECTRIC_KEY.test(k)) { const n = electricNumber(v); if (Number.isFinite(n)) return n; }
        }
        for (const k of keys) {
            if (k.charAt(0) === '_' || k.charAt(0) === '$') continue;
            let v; try { v = obj[k]; } catch (e) { continue; }
            if (v && typeof v === 'object') { const n = electricScan(v, depth + 1, seen); if (Number.isFinite(n)) return n; }
        }
        return NaN;
    }
    function electricPage() {
        if (root.location.origin !== 'https://yktapp.nwpu.edu.cn') return {state: 'elsewhere'};
        const ok = n => Math.abs(n) > 1000000 ? {state: 'loading'} : {state: 'ok', source: ELECTRIC_PAGE,
            fields: [{label: '当前剩余电量', value: n + ' 度'}], tables: [], links: []};
        const doc = root.document, rootEl = doc && doc.querySelector('#app');
        const vm = rootEl && (rootEl.__vue__ || (rootEl.__vue_app__ && rootEl.__vue_app__._instance && rootEl.__vue_app__._instance.proxy));
        if (vm) {
            const seen = new Set(), queue = [vm];
            for (let visited = 0; queue.length && visited < 200; visited++) {
                const c = queue.shift();
                let n = NaN; try { n = electricScan(c.$data || c, 0, seen); } catch (e) { }
                if (Number.isFinite(n)) return ok(n);
                try { (c.$children || []).forEach(x => queue.push(x)); } catch (e) { }
            }
        }
        const text = (doc && doc.body && doc.body.innerText) || '';
        const m = text.match(/(?:当前)?剩余电量[^\d\-\n]{0,12}(-?\d+(?:\.\d+)?)/);
        if (m) return ok(+m[1]);
        return {state: 'loading'};
    }
    const api = {allowed, safeUrl, source, discover, extract, profile, profileStart, start, identity, capture, portal, deadlineTime, deadlineData, busData, electricForm, electricNormalize, electricStart, electricPage, ELECTRIC_PAGE};
    if (typeof module !== 'undefined' && module.exports) module.exports = api; else root.NwpuCampus = api;
})(typeof window === 'undefined' ? globalThis : window);
