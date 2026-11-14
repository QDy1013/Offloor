/* NWPU official IC frontend protocol, inspected 2026-11-12. No background booking. */
(function (root) {
    'use strict';
    if (root.NwpuStudyRoom) { if (typeof module !== 'undefined') module.exports = root.NwpuStudyRoom; return; }
    const ORIGIN = 'https://ic.nwpu.edu.cn';
    let busy = false, rooms = new Map(), bookings = new Map(), members = new Map();
    const clean = (v, max = 180) => String(v == null ? '' : v).replace(/<[^>]*>/g, '').replace(/[\u0000-\u001f]/g, ' ').trim().slice(0, max);
    const id = v => (typeof v === 'number' && Number.isSafeInteger(v) && v > 0) || (typeof v === 'string' && /^[A-Za-z0-9_-]{1,100}$/.test(v));
    const same = (a, b) => String(a) === String(b);
    function fail(message) { throw new Error(message); }
    function origin() { if (!root.location || root.location.origin !== ORIGIN) fail('预约会话不在校方系统，请重新连接'); }
    function storedUser() { try { return JSON.parse(root.sessionStorage.getItem('userInfo') || '{}') || {}; } catch (_) { return {}; } }
    async function request(path, params, post) {
        origin();
        const user = storedUser(), headers = {Accept: 'application/json', lan: '1'};
        if (user.token) headers.token = user.token;
        let url = '/ic-web/' + path;
        const options = {method: post ? 'POST' : 'GET', credentials: 'same-origin', headers};
        if (post) { headers['Content-Type'] = 'application/json'; options.body = JSON.stringify(params || {}); }
        else if (params) url += '?' + new URLSearchParams(params).toString();
        const response = await root.fetch(url, options);
        if (!response.ok) fail('校方连接失败，请稍后重试');
        const result = await response.json();
        if (!result || typeof result.code !== 'number') fail('校方返回了无法识别的数据');
        if (result.code !== 0) { const e = new Error(clean(result.message) || '校方未接受本次请求'); e.definite = true; throw e; }
        return result;
    }
    async function identity(account) {
        const response = await request('auth/userInfo');
        const user = response.data;
        if (!user || !id(user.accNo) || !same(user.logonName, account)) fail('校园账号与预约账号不一致，请重新连接');
        return user;
    }
    function dateKey(date) {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(date || '')) fail('请选择有效日期');
        const d = new Date(date + 'T00:00:00+08:00');
        if (!Number.isFinite(d.getTime()) || new Date(d.getTime() + 8 * 3600000).toISOString().slice(0, 10) !== date) fail('日期无效');
        return date.replace(/-/g, '');
    }
    function chinaDate(now) { return new Date((now == null ? Date.now() : now) + 8 * 3600000).toISOString().slice(0, 10); }
    function stamp(value) {
        if (typeof value === 'number' && Number.isFinite(value)) return value < 1e11 ? value * 1000 : value;
        const text = clean(value).replace(/\//g, '-');
        if (!text) return NaN;
        if (/^\d{10,15}$/.test(text)) {
            const num = Number(text);
            return text.length === 10 ? num * 1000 : num;
        }
        const normalized = text.includes('T') ? text : text.replace(' ', 'T');
        return Date.parse(/\+|Z$|[+-]\d\d:\d\d$/.test(normalized) ? normalized : `${normalized}+08:00`);
    }
    function timeText(value) {
        const text = clean(value);
        if (!text) return '';
        if (typeof value === 'number' || /^\d{10,15}$/.test(text)) {
            const ms = stamp(value);
            if (Number.isFinite(ms)) {
                return new Date(ms + 8 * 3600000).toISOString().replace('T', ' ').slice(0, 19);
            }
        }
        return text.replace(/\//g, '-');
    }
    function clock(value) { return /^(?:[01]\d|2[0-3]):[0-5]\d(?::00)?$/.test(String(value || '')); }
    function scopeDay(value) {
        if (value == null || value === '') return '';
        if (typeof value === 'number' && Number.isFinite(value)) return chinaDate(value < 1e11 ? value * 1000 : value);
        const s = clean(value, 30).replace(/\//g, '-');
        if (/^\d{10,15}$/.test(s)) return chinaDate(stamp(s));
        if (/^\d{8}$/.test(s)) return s.slice(0, 4) + '-' + s.slice(4, 6) + '-' + s.slice(6, 8);
        if (/^\d{4}-\d{2}-\d{2}/.test(s)) return s.slice(0, 10);
        return '';
    }
    function periodClock(value, isEnd) {
        const raw = clean(value, 32);
        const epoch = (typeof value === 'number' && Number.isFinite(value) && value >= 1e9) || /^\d{10,15}$/.test(raw);
        let s = epoch ? timeText(value) : raw.replace(/\//g, '-');
        const compact = s.match(/^(\d{3,4})$/);
        if (compact) {
            const p = compact[1].padStart(4, '0');
            s = p.slice(0, 2) + ':' + p.slice(2);
        }
        const dt = s.match(/^\d{4}-\d{2}-\d{2}[ T](\d{1,2}:[0-5]\d(?::[0-5]\d)?)/);
        if (dt) s = dt[1];
        const m = s.match(/^(\d{1,2}):([0-5]\d)(?::([0-5]\d))?$/);
        if (!m) return '';
        const h = Number(m[1]), min = Number(m[2]), sec = m[3] ? Number(m[3]) : 0;
        if (isEnd && h === 24 && min === 0 && sec === 0) return '24:00';
        if (h < 0 || h > 23) return '';
        return String(h).padStart(2, '0') + ':' + String(min).padStart(2, '0');
    }
    // 校方开放时段会用 24:00 表示当天结束，但它不是一个可提交的钟点，换成次日零点。
    function at(date, value) { const text = String(value == null ? '' : value); if (text === '24:00' || text === '24:00:00') return stamp(date + ' 00:00:00') + 86400000; return stamp(date + ' ' + (text.length === 5 ? text + ':00' : text)); }
    function array(data) { if (!Array.isArray(data)) fail('校方未返回有效列表，不能判断是否空闲'); return data; }
    const numeric = v => v !== null && v !== undefined && String(v).trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : NaN;
    function safeRoom(raw, date) {
        if (!raw || !id(raw.devId)) return null;
        if (raw.classKind != null && raw.classKind !== '' && Number(raw.classKind) > 1) return null;
        const rule = raw.resvRule;
        let open = ([raw.openTimes, raw.openTime, raw.openTimeList, raw.devOpenTime].find(a => Array.isArray(a) && a.length > 0) || [])
            .map(x => ({slot: x, scopeLimit: undefined}));
        if (!open.length && Array.isArray(raw.timeScopeOpenInfo)) {
            const scopes = raw.timeScopeOpenInfo.filter(x => {
                if (!x || typeof x !== 'object') return false;
                const sd = scopeDay(x.startDay || x.startDate || x.beginDay);
                const ed = scopeDay(x.endDay || x.endDate);
                if (!sd && !ed) return true;
                if (sd && !ed) return date >= sd;
                if (!sd && ed) return date <= ed;
                return date >= sd && date <= ed;
            });
            open = scopes.flatMap(x => {
                const list = [x.openTimeList, x.openTimes, x.openTime].find(a => Array.isArray(a) && a.length > 0);
                const items = list || (x.openStartTime || x.openEndTime || x.startTime || x.endTime ? [x] : []);
                return items.map(slot => ({slot, scopeLimit: x.openLimit}));
            });
        }
        const periods = open.map(({slot: x, scopeLimit}) => {
            if (!x || typeof x !== 'object') return null;
            const start = periodClock(x.openStartTime || x.startTime || x.openStart || x.start, false);
            const end = periodClock(x.openEndTime || x.endTime || x.openEnd || x.end, true);
            if (!start || !end || !(at(date, end) > at(date, start))) return null;
            const rawLimit = x.openLimit !== undefined && x.openLimit !== null ? x.openLimit
                : (scopeLimit !== undefined && scopeLimit !== null ? scopeLimit : undefined);
            const limit = rawLimit === false ? 0 : numeric(rawLimit);
            // Live ic.nwpu.edu.cn study rooms (sysKind=1) return openLimit=1 on normal bookable days; keep unknown/other codes blocked.
            if (limit !== 0 && limit !== 1) return null;
            return {start, end, limit};
        }).filter(Boolean);
        const reservations = Array.isArray(raw.resvInfo) ? raw.resvInfo : null;
        // Live ic.nwpu.edu.cn study rooms without academic timetables omit cls or set cls:null; reject non-array garbage.
        const classes = Array.isArray(raw.cls) ? raw.cls : (raw.cls == null ? [] : null);
        const min = numeric(raw.minUser), max = numeric(raw.maxUser);
        const releaseNum = rule && typeof rule === 'object' ? (Number.isFinite(numeric(rule.laterLineTime)) ? numeric(rule.laterLineTime) : (() => { const c = periodClock(rule.laterLineTime, false); return c ? Number(c.replace(':', '')) : NaN; })()) : NaN;
        const rules = rule && typeof rule === 'object' ? {minMinutes: numeric(rule.minResvTime), maxMinutes: numeric(rule.maxResvTime), interval: numeric(rule.timeInterval), earliest: numeric(rule.earliestResvTime), latest: numeric(rule.latestResvTime), freezing: numeric(rule.freezingTime), release: releaseNum} : null;
        const completeRules = rules && rules.minMinutes > 0 && rules.maxMinutes >= rules.minMinutes && rules.interval > 0 && rules.earliest >= rules.latest && rules.latest >= 0 && rules.freezing >= 0;
        // 不可预约时把原因说清楚；界面直接显示，不让人对着“暂不可约”猜。
        const reasons = [];
        if (raw.onlyView) reasons.push('校方标注为仅可查看，不能预约');
        if (!rule || typeof rule !== 'object') reasons.push('校方没有返回预约规则');
        else if (!completeRules) reasons.push('预约时长或可约范围规则没读全');
        if (!periods.length) {
            const firstSlot = open[0] && open[0].slot;
            const limTag = firstSlot ? ',lim=' + String(firstSlot.openLimit)
                + ',t=' + clean(firstSlot.openStartTime || firstSlot.startTime || firstSlot.openStart || firstSlot.start, 20)
                + '~' + clean(firstSlot.openEndTime || firstSlot.endTime || firstSlot.openEnd || firstSlot.end, 20) : '';
            reasons.push('这一天没有读到开放时段[' + (Array.isArray(raw.openTimes) ? raw.openTimes.length : String(raw.openTimes))
                + '/' + (Array.isArray(raw.timeScopeOpenInfo) ? raw.timeScopeOpenInfo.length : String(raw.timeScopeOpenInfo))
                + '/' + open.length + limTag + ']');
        }
        if (reservations === null) reasons.push('占用信息没读全');
        if (classes === null) reasons.push('课表占用没读全[' + typeof raw.cls + ']');
        if (!(Number.isInteger(min) && min > 0)) reasons.push('房间人数下限没读全');
        else if (!(Number.isInteger(max) && max >= min)) reasons.push('房间人数上限没读全');
        const usable = reasons.length === 0;
        const occupied = reservations === null || classes === null ? null : reservations.filter(x => !x || !(Number(x.resvStatus) & 128)).map(x => ({start: timeText(x && (x.startTime || x.resvBeginTime || x.beginTime || x.start)), end: timeText(x && (x.endTime || x.resvEndTime || x.end)), buffer: rules ? rules.freezing : NaN}))
            .concat(classes.map(x => ({start: timeText(x && (x.start || x.startTime)), end: timeText(x && (x.end || x.endTime)), buffer: 0})));
        return {id: raw.devId, name: clean(raw.devName), location: clean(raw.labName), kind: clean(raw.kindName), campus: clean(raw.campusName || raw.campus), min: Number.isInteger(min) && min > 0 ? min : null, max: Number.isInteger(max) && max > 0 ? max : null,
            periods, occupied, rules, available: usable, reason: usable ? '' : reasons.slice(0, 2).join('；'), date};
    }
    function validate(room, date, start, end, count, now) {
        dateKey(date);
        if (!room || !room.available || room.date !== date) fail('房间规则尚未读全，请重新查询');
        if (!clock(start) || !clock(end)) fail('时间格式应为 HH:mm');
        const begin = at(date, start), finish = at(date, end), current = now == null ? Date.now() : now;
        if (!(begin > current && finish > begin)) fail('请选择尚未开始且结束晚于开始的时段');
        const rule = room.rules, minutes = (finish - begin) / 60000;
        if (!Number.isFinite(rule.minMinutes) || !Number.isFinite(rule.maxMinutes) || !Number.isFinite(rule.interval) || rule.interval <= 0 || rule.minMinutes <= 0 || rule.maxMinutes < rule.minMinutes) fail('校方预约时长规则尚未读全');
        if (minutes < rule.minMinutes || minutes > rule.maxMinutes) fail('预约时长须为 ' + rule.minMinutes + '–' + rule.maxMinutes + ' 分钟');
        if (!room.periods.some(p => at(date, p.start) <= begin && finish <= at(date, p.end))) fail('所选时段不在开放时间内');
        const minute = Number(start.slice(0, 2)) * 60 + Number(start.slice(3, 5));
        const endMinute = Number(end.slice(0, 2)) * 60 + Number(end.slice(3, 5));
        const period = room.periods.find(p => at(date, p.start) <= begin && finish <= at(date, p.end));
        const base = Number(period.start.slice(0, 2)) * 60 + Number(period.start.slice(3, 5));
        if ((minute - base) % rule.interval || (endMinute - base) % rule.interval) fail('时间须按校方 ' + rule.interval + ' 分钟间隔选择');
        // The official frontend uses latestResvTime as minimum lead time and
        // earliestResvTime as the future horizon; whole days use China's midnight.
        const midnight = stamp(chinaDate(current) + ' 00:00:00');
        const first = rule.latest % 1440 === 0 ? midnight + rule.latest * 60000 : current + rule.latest * 60000;
        let last = current + rule.earliest * 60000;
        if (rule.earliest % 1440 === 0) {
            const release = String(rule.release).padStart(4, '0');
            if (!/^\d{4}$/.test(release) || !clock(release.slice(0, 2) + ':' + release.slice(2))) fail('校方放号时间尚未读全');
            const released = current >= at(chinaDate(current), release.slice(0, 2) + ':' + release.slice(2));
            last = midnight + rule.earliest * 60000 + (released ? 86400000 : 0);
        }
        if (begin < first) fail('未达到校方最早可约时间');
        if (begin >= last || finish > last) fail('超出校方可预约日期范围');
        if (room.min == null || room.max == null) fail('房间人数要求尚未读全');
        if (count < room.min || count > room.max) fail('该房间需要 ' + room.min + '–' + room.max + ' 位真实参与者（包含本人）');
        if (!Array.isArray(room.occupied)) fail('占用信息不完整，请重新查询');
        for (const item of room.occupied) {
            const a = stamp(item.start), b = stamp(item.end);
            if (!Number.isFinite(a) || !Number.isFinite(b)) fail('部分占用时间未能解析，请重新查询');
            if (begin < b && finish > a) fail('所选时段已被占用，请更换时间');
        }
        return {begin: date + ' ' + start.slice(0, 5) + ':00', end: date + ' ' + end.slice(0, 5) + ':00'};
    }
    function campusTag(value) {
        const text = String(value || '');
        if (text.includes('长安')) return '长安校区';
        if (text.includes('友谊')) return '友谊校区';
        if (text.includes('太仓')) return '太仓校区';
        return '';
    }
    function nodeCampus(item) {
        if (!item || typeof item !== 'object') return '';
        for (const f of [item.campusName, item.campus, item.labName, item.kindName, item.roomName, item.devName, item.name, item.title, item.label]) {
            const c = campusTag(f);
            if (c) return c;
        }
        return '';
    }
    function walkMenu(nodes, parentId, out, inheritedCampus) {
        if (!nodes) return;
        if (!Array.isArray(nodes)) {
            if (typeof nodes === 'object') walkMenu([nodes], parentId, out, inheritedCampus);
            return;
        }
        for (const n of nodes) {
            if (!n || typeof n !== 'object') continue;
            const sk = n.sysKind != null && n.sysKind !== '' ? Number(n.sysKind) : NaN;
            if (Number.isFinite(sk) && sk > 1) continue;
            const c = nodeCampus(n) || inheritedCampus || '';
            if (c) out.menuCampuses.add(c);
            const recordId = (val, set) => {
                if (!id(val)) return '';
                const s = String(val);
                if (set) set.add(s);
                if (c) out.idCampus.set(s, c);
                return s;
            };
            const kindId = recordId(n.kindId, out.kindIds);
            const labId = recordId(n.labId, out.labIds);
            const roomId = recordId(n.roomId, out.roomIds);
            const devId = recordId(n.devId, out.devIds);
            const selfId = id(n.id) ? String(n.id) : '';
            if (c && selfId) out.idCampus.set(selfId, c);
            const pId = parentId || (id(n.parentId) ? String(n.parentId) : '');
            const kids = [n.children, n.childList, n.subList, n.sonList, n.nodes, n.list, n.rooms, n.roomList,
                n.labs, n.kinds, n.devs, n.devList, n.data].find(a => Array.isArray(a) && a.length > 0);
            if (kids) {
                if (selfId) out.parentIds.add(selfId);
                walkMenu(kids, selfId || pId, out, c);
            } else if (selfId) {
                out.leafIds.add(selfId);
                const key = selfId + '|' + pId;
                if (!out.seenLeaves.has(key)) {
                    out.seenLeaves.add(key);
                    out.leaves.push({id: selfId, parentId: pId, kindId, labId, roomId, devId, campus: c});
                }
            }
        }
    }
    async function loadMenu() {
        const out = {kindIds: new Set(), labIds: new Set(), roomIds: new Set(), devIds: new Set(),
            parentIds: new Set(), leafIds: new Set(), seenLeaves: new Set(), leaves: [], menuCampuses: new Set(), idCampus: new Map()};
        const ingestList = (res, primaryKey, targetSet) => {
            for (const x of array(res && res.data)) {
                if (!x || typeof x !== 'object') continue;
                const sk = x.sysKind != null && x.sysKind !== '' ? Number(x.sysKind) : NaN;
                if (Number.isFinite(sk) && sk > 1) continue;
                const c = nodeCampus(x);
                if (c) out.menuCampuses.add(c);
                const record = (val, set) => {
                    if (!id(val)) return;
                    const s = String(val);
                    if (set) set.add(s);
                    if (c) out.idCampus.set(s, c);
                };
                record(x[primaryKey] || x.id, targetSet);
                record(x.kindId, out.kindIds);
                record(x.labId, out.labIds);
                record(x.roomId, out.roomIds);
            }
        };
        try { const m = await request('roomMenu', {sysKind: 1}); walkMenu(m && m.data, '', out, ''); } catch (_) {}
        try { ingestList(await request('kind', {sysKind: 1}), 'kindId', out.kindIds); } catch (_) {}
        try { ingestList(await request('lab', {sysKind: 1}), 'labId', out.labIds); } catch (_) {}
        // sysKind=1 仅返回一个校区（或漏掉某些节点）时，补查无参菜单并过滤非研修室节点。
        if (!out.menuCampuses.has('长安校区') || !out.menuCampuses.has('友谊校区')) {
            try { const m = await request('roomMenu'); walkMenu(m && m.data, '', out, ''); } catch (_) {}
            try { ingestList(await request('kind'), 'kindId', out.kindIds); } catch (_) {}
            try { ingestList(await request('lab'), 'labId', out.labIds); } catch (_) {}
        }
        if (!out.leafIds.size && !out.kindIds.size && !out.labIds.size && !out.roomIds.size) {
            try { const sm = await request('seatMenu'); walkMenu(sm && sm.data, '', out, ''); } catch (_) {}
        }
        return out;
    }
    function roomKey(item) {
        return String(item.devId || item.roomId || item.id || JSON.stringify(item));
    }
    function hasBothCampuses(rowsOrSet) {
        if (rowsOrSet instanceof Set) return rowsOrSet.has('长安校区') && rowsOrSet.has('友谊校区');
        let c1 = false, c2 = false;
        for (const r of array(rowsOrSet)) {
            const c = nodeCampus(r);
            if (c === '长安校区') c1 = true;
            else if (c === '友谊校区') c2 = true;
            if (c1 && c2) return true;
        }
        return false;
    }
    async function tryBatch(items, buildParams) {
        const list = (items || []).filter(Boolean).slice(0, 60);
        if (!list.length) return null;
        const merged = [], seen = new Set(), missed = [];
        let more = false, anyValid = false, leadErrs = 0, lastErr = null;
        const trackMore = (params, res) => {
            const ps = Number(params && params.pageSize) || 30;
            const pg = Number(params && params.page) || 1;
            const arr = res && Array.isArray(res.data) ? res.data : [];
            if (arr.length >= ps || (res && Number.isFinite(res.count) && pg * ps < res.count)) more = true;
        };
        const append = arr => {
            for (const item of array(arr)) {
                if (!item || typeof item !== 'object') continue;
                const k = roomKey(item);
                if (!seen.has(k)) { seen.add(k); merged.push(item); }
            }
        };
        for (const item of list) {
            const p = buildParams(item);
            if (!p) continue;
            try {
                const r = await request('reserve', p);
                if (r && Array.isArray(r.data)) {
                    anyValid = true;
                    leadErrs = 0;
                    if (r.data.length > 0) append(r.data);
                    else missed.push(item);
                    trackMore(p, r);
                } else {
                    missed.push(item);
                }
            } catch (e) {
                lastErr = e;
                missed.push(item);
                if (!anyValid && ++leadErrs >= 3) break;
            }
        }
        if (!anyValid) return {ok: false, error: lastErr, missed};
        return {ok: true, data: merged, count: merged.length, more, missed};
    }
    async function queryReserve(key, page) {
        let firstErr = null, firstRes = null;
        try { firstRes = await request('reserve', {sysKind: 1, resvDates: key, page, pageSize: 40}); }
        catch (e) { firstErr = e; }
        if (firstRes && Array.isArray(firstRes.data) && firstRes.data.length > 0 && hasBothCampuses(firstRes.data)) {
            return {res: firstRes, out: null};
        }
        const out = await loadMenu();
        const hasMenu = !!(out.leafIds.size || out.kindIds.size || out.labIds.size || out.roomIds.size || out.parentIds.size || out.devIds.size);
        if (firstRes && Array.isArray(firstRes.data) && (!hasMenu || page > 1)) {
            return {res: firstRes, out};
        }
        const agg = {items: [], seen: new Set(), more: false, count: null, campuses: new Set(), anyEmpty: false, multiBatch: false};
        const mergeInto = (res, fromBatch) => {
            if (!res || !Array.isArray(res.data)) return;
            if (res.data.length === 0) agg.anyEmpty = true;
            for (const item of res.data) {
                if (!item || typeof item !== 'object') continue;
                const c = nodeCampus(item) || out.idCampus.get(String(item.labId || '')) || out.idCampus.get(String(item.kindId || '')) || '';
                if (c) agg.campuses.add(c);
                if (c && !item.campusName && !item.campus) item.campusName = c;
                const k = roomKey(item);
                if (!agg.seen.has(k)) { agg.seen.add(k); agg.items.push(item); }
            }
            if (fromBatch) {
                agg.multiBatch = true;
                if (res.more) agg.more = true;
            } else {
                const ps = 40;
                if (res.data.length >= ps || (Number.isFinite(res.count) && page * ps < res.count)) agg.more = true;
                if (agg.count == null && Number.isFinite(res.count)) agg.count = res.count;
            }
        };
        if (firstRes && Array.isArray(firstRes.data)) mergeInto(firstRes, false);
        const kinds = [...out.kindIds], labs = [...out.labIds], rooms = [...out.roomIds];
        const devs = [...out.devIds], leaves = [...out.leafIds], parents = [...out.parentIds];
        const singleAttempts = [];
        if (kinds.length) {
            singleAttempts.push({sysKind: 1, resvDates: key, page, pageSize: 40, kindIds: kinds.join(','), labId: ''});
            singleAttempts.push({sysKind: 1, resvDates: key, page, pageSize: 40, kindIds: kinds.join(',')});
        }
        if (leaves.length) {
            singleAttempts.push({sysKind: 1, resvDates: key, page, pageSize: 40, kindIds: leaves.join(','), labId: ''});
            singleAttempts.push({sysKind: 1, resvDates: key, page, pageSize: 40, kindIds: leaves.join(',')});
        }
        if (labs.length === 1) {
            singleAttempts.push({sysKind: 1, resvDates: key, page, pageSize: 40, labId: labs[0]});
            if (kinds.length) singleAttempts.push({sysKind: 1, resvDates: key, page, pageSize: 40, kindIds: kinds.join(','), labId: labs[0]});
        }
        if (rooms.length) {
            singleAttempts.push({sysKind: 1, resvDates: key, page, pageSize: 40, roomIds: rooms.join(',')});
            singleAttempts.push({resvDates: key, sysKind: 1, roomIds: rooms.join(',')});
        }
        if (leaves.length && !rooms.length) singleAttempts.push({sysKind: 1, resvDates: key, page, pageSize: 40, roomIds: leaves.join(',')});
        if (devs.length) singleAttempts.push({sysKind: 1, resvDates: key, devIds: devs.join(',')});
        let lastErr = firstErr;
        for (const params of singleAttempts) {
            try {
                const res = await request('reserve', params);
                if (res && Array.isArray(res.data)) {
                    mergeInto(res, false);
                    // 仅当两个校区都已拿到，或没有任何其他维度的待查列表时，才直接结束。
                    const moreAxes = labs.length > 0 || kinds.length > 1 || leaves.length > 1 || parents.length > 0;
                    if ((res.data.length > 0 || page > 1) && (hasBothCampuses(agg.campuses) || !moreAxes)) {
                        return {res: {code: 0, data: agg.items, count: agg.multiBatch ? agg.items.length : (agg.count != null ? Math.max(agg.count, agg.items.length) : agg.items.length), more: agg.more}, out};
                    }
                }
            } catch (err) { lastErr = err; }
        }
        const runPlan = async (items, fn) => {
            if (!items || !items.length) return [];
            const res = await tryBatch(items, fn);
            if (!res || !res.ok) { if (res && res.error) lastErr = res.error; return items; }
            mergeInto(res, true);
            return res.missed;
        };
        // 先按叶子节点/类目查，若有未命中的叶子或仍缺校区，再补查独立 kindIds、labIds 与房间维度并合并去重。
        let missedLeaves = await runPlan(out.leaves, leaf => ({sysKind: 1, resvDates: key, page, pageSize: 30, kindIds: leaf.kindId || leaf.id, labId: leaf.labId || ''}));
        if (missedLeaves.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            missedLeaves = await runPlan(missedLeaves, leaf => ({sysKind: 1, resvDates: key, page, pageSize: 30, kindIds: leaf.id}));
        }
        if (missedLeaves.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            missedLeaves = await runPlan(missedLeaves, leaf => (leaf.parentId ? {sysKind: 1, resvDates: key, page, pageSize: 30, kindIds: leaf.id, labId: leaf.parentId} : null));
        }
        if (kinds.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            const extraKinds = kinds.filter(k => !out.leafIds.has(k));
            const kList = extraKinds.length ? extraKinds : (missedLeaves.length ? kinds : []);
            const missedKinds = await runPlan(kList, k => ({sysKind: 1, resvDates: key, page, pageSize: 30, kindIds: k, labId: ''}));
            if (missedKinds.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
                await runPlan(missedKinds, k => ({sysKind: 1, resvDates: key, page, pageSize: 30, kindIds: k}));
            }
        }
        if (labs.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            await runPlan(labs, l => ({sysKind: 1, resvDates: key, page, pageSize: 30, labId: l}));
        }
        if (missedLeaves.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            missedLeaves = await runPlan(missedLeaves, leaf => ({sysKind: 1, resvDates: key, page, pageSize: 30, labId: leaf.labId || leaf.id}));
        }
        if (missedLeaves.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            missedLeaves = await runPlan(missedLeaves, leaf => (leaf.parentId ? {sysKind: 1, resvDates: key, page, pageSize: 30, kindIds: leaf.parentId, labId: leaf.id} : null));
        }
        if (parents.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            const extraParents = parents.filter(p => !out.labIds.has(p));
            const missedParents = await runPlan(extraParents.length ? extraParents : parents, p => ({sysKind: 1, resvDates: key, page, pageSize: 30, labId: p}));
            if (missedParents.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
                await runPlan(missedParents, p => ({sysKind: 1, resvDates: key, page, pageSize: 30, kindIds: p, labId: ''}));
            }
        }
        if (rooms.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            await runPlan(rooms, r => ({sysKind: 1, resvDates: key, page, pageSize: 30, roomIds: r}));
        }
        if (missedLeaves.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            missedLeaves = await runPlan(missedLeaves, leaf => ({sysKind: 1, resvDates: key, page, pageSize: 30, roomIds: leaf.roomId || leaf.id}));
            if (missedLeaves.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
                missedLeaves = await runPlan(missedLeaves, leaf => ({resvDates: key, sysKind: 1, roomIds: leaf.roomId || leaf.id}));
            }
        }
        if (devs.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            await runPlan(devs, d => ({sysKind: 1, resvDates: key, devIds: d}));
        }
        if (missedLeaves.length && (!agg.items.length || !hasBothCampuses(agg.campuses))) {
            await runPlan(missedLeaves, leaf => ({sysKind: 1, resvDates: key, devIds: leaf.devId || leaf.id}));
        }
        if (agg.items.length || agg.anyEmpty) {
            return {res: {code: 0, data: agg.items, count: agg.multiBatch ? agg.items.length : (agg.count != null ? agg.count : agg.items.length), more: agg.more}, out};
        }
        throw lastErr || firstErr;
    }
    async function query(args, account) {
        await identity(account); const date = clean(args.date, 10), key = dateKey(date);
        rooms.clear();
        const {res: result, out} = await queryReserve(key, args.page || 1);
        const items = array(result.data), safe = [];
        for (const raw of items) { const room = safeRoom(raw, date); if (room) { rooms.set(String(room.id), raw); safe.push(room); } }
        const diag = out ? {
            kinds: out.kindIds.size,
            labs: out.labIds.size,
            leaves: out.leaves.length,
            menuCampuses: [...out.menuCampuses],
            roomCampuses: [...new Set(safe.map(r => nodeCampus(r)).filter(Boolean))]
        } : null;
        return {rooms: safe, date, count: Number.isFinite(result.count) ? result.count : null, page: args.page || 1,
            more: typeof result.more === 'boolean' ? result.more : null, diag};
    }
    function cancellable(row, user) { const state = row && numeric(row.resvStatus); return !!row && row.classKind === 1 && same(row.appAccNo, user.accNo) && Number.isSafeInteger(state) && state >= 0 && !(state & 4) && !(state & 128) && typeof row.uuid === 'string' && id(row.uuid); }
    function status(value) {
        if (!Number.isInteger(value)) return '状态待确认';
        if (value & 128) return '已结束'; if (value & 512) return '违约 / 异常';
        if (value & 8192) return '待同行人确认'; if (value & 4) return '使用中';
        return '校方状态码 ' + value;
    }
    async function mine(args, account) {
        const user = await identity(account); bookings.clear();
        const begin = clean(args.begin || chinaDate(Date.now() - 7 * 86400000), 10), end = clean(args.end || chinaDate(Date.now() + 30 * 86400000), 10);
        dateKey(begin); dateKey(end);
        const result = await request('reserve/resvInfo', {beginDate: begin, endDate: end, needStatus: 0, page: args.page || 1, pageNum: 40, orderKey: 'gmt_create', orderModel: 'desc'});
        const items = array(result.data).filter(x => x && Number(x.classKind) === 1 && (same(x.appAccNo, user.accNo) || (Array.isArray(x.resvMemberInfoList) && x.resvMemberInfoList.some(m => same(m.accNo, user.accNo) || same(m.logonName, account)))));
        return {bookings: items.map(x => { bookings.set(String(x.uuid), x); const state = numeric(x.resvStatus); const code = Number.isInteger(state) ? state : null; return {uuid: clean(x.uuid, 100), name: clean((x.resvDevInfoList || []).map(d => d.devName || d.roomName).join('、')), location: clean((x.resvDevInfoList || []).map(d => d.labName).join('、')), title: clean(x.testName), begin: timeText(x.resvBeginTime || x.startTime || x.beginTime), end: timeText(x.resvEndTime || x.endTime), status: status(code), state: code, canCancel: cancellable(x, user)}; }), count: Number.isFinite(result.count) ? result.count : null, page: args.page || 1};
    }
    async function findMembers(args, account) {
        const user = await identity(account), key = clean(args.key, 60);
        if (key.length < 2) fail('请输入至少两个字符的姓名或学号');
        const response = await request('account/getMembers', {key, page: 1, pageNum: 10});
        return {members: array(response.data).filter(x => id(x.accNo) && !same(x.accNo, user.accNo) && x.status !== 2 && x.localstatus !== 2).map(x => { members.set(String(x.accNo), x); return {id: x.accNo, name: clean(x.trueName), account: clean(x.logonName)}; })};
    }
    async function refreshRoom(roomId, key, cached) {
        const find = data => array(data).find(x => same(x.devId, roomId));
        try {
            const response = await request('reserve', {sysKind: 1, devIds: roomId, resvDates: key});
            const hit = find(response.data);
            if (hit) return hit;
        } catch (e) {
            if (!e || !e.definite) throw e;
        }
        const direct = [];
        if (cached && id(cached.kindId)) {
            direct.push({sysKind: 1, resvDates: key, page: 1, pageSize: 40, kindIds: cached.kindId, labId: ''});
            direct.push({sysKind: 1, resvDates: key, page: 1, pageSize: 40, kindIds: cached.kindId});
        }
        if (cached && id(cached.labId)) direct.push({sysKind: 1, resvDates: key, page: 1, pageSize: 40, labId: cached.labId});
        if (cached && id(cached.roomId)) direct.push({sysKind: 1, resvDates: key, page: 1, pageSize: 40, roomIds: cached.roomId});
        for (const params of direct) {
            try {
                const res = await request('reserve', params);
                const hit = find(res.data);
                if (hit) return hit;
            } catch (e) {
                if (e && !e.definite) throw e;
            }
        }
        const fallback = await queryReserve(key, 1);
        const hit = find(fallback.data);
        if (hit || !fallback.more) return hit || null;
        for (let pg = 2; pg <= 4; pg += 1) {
            const next = await queryReserve(key, pg);
            const moreHit = find(next.data);
            if (moreHit) return moreHit;
            if (!next.data.length) break;
        }
        return null;
    }
    async function submit(args, account) {
        if (args.confirmed !== true || args.agreed !== true) fail('请阅读须知并确认预约');
        const user = await identity(account), key = dateKey(args.date);
        if (!id(args.id) || !rooms.has(String(args.id))) fail('请先查询并选择房间');
        const raw = await refreshRoom(args.id, key, rooms.get(String(args.id)));
        if (!raw) fail('该房间已不可预约，请重新查询');
        const room = safeRoom(raw, args.date), ids = [user.accNo];
        if (!Array.isArray(args.members) || args.members.length > 30) fail('同行人列表无效');
        for (const member of args.members) {
            if (!id(member) || ids.some(x => same(x, member)) || !members.has(String(member))) fail('同行人信息已失效，请重新搜索添加');
            ids.push(member);
        }
        const times = validate(room, args.date, args.start, args.end, ids.length);
        const title = clean(args.title, 100); if (!title) fail('请填写研修主题');
        const config = await request('sysConfig/public');
        const mode = config.data && String(config.data.resvCode);
        if (mode === '2') fail('校方要求滑块验证，请先使用官方系统完成验证；本次未提交预约');
        if (mode !== '0' && mode !== '1') fail('校方验证码配置尚未读全，本次未提交预约');
        const captcha = clean(args.captcha, 12);
        if (mode === '1' && !captcha) fail('请输入图片验证码');
        const payload = {sysKind: 1, appAccNo: user.accNo, memberKind: ids.length > 1 ? 2 : 1, resvBeginTime: times.begin, resvEndTime: times.end, testName: title, resvKind: 2, resvProperty: 0, appUrl: '', resvMember: ids, resvDev: [raw.devId], memo: clean(args.memo, 300), captcha, addServices: []};
        // Never retry writes: after dispatch, a network failure means the server outcome is unknown.
        try { const result = await request('reserve', payload, true); return {success: true, message: clean(result.message) || '校方已受理，请在我的预约核对状态'}; }
        catch (e) { if (e.definite) throw e; return {unknown: true, message: '提交结果未确认，请先查询我的预约，勿重复提交'}; }
    }
    async function cancel(args, account) {
        if (args.confirmed !== true || !id(args.uuid) || !bookings.has(String(args.uuid))) fail('请先查询并确认要取消的本人预约');
        const user = await identity(account), row = bookings.get(String(args.uuid));
        if (!cancellable(row, user)) fail('该预约不满足取消条件，请重新查询');
        try { const result = await request('reserve/delete', {uuid: row.uuid}, true); bookings.delete(String(args.uuid)); return {success: true, message: clean(result.message) || '已取消，请刷新核对'}; }
        catch (e) { if (e.definite) throw e; return {unknown: true, message: '取消结果未确认，请先刷新我的预约，勿重复取消'}; }
    }
    async function captcha(args, account) {
        await identity(account);
        const response = await request('sysConfig/public');
        const mode = response.data && String(response.data.resvCode);
        const tip = await request('sysInfo', {sysKind: 1});
        let notice = '';
        if (Array.isArray(tip.data)) notice = tip.data.map(x => clean(x.content, 6000)).filter(Boolean).join('\n');
        else if (tip.data) notice = clean(tip.data.content, 6000);
        if (!notice) fail('尚未读取到校方预约须知，请稍后重试');
        if (mode === '2') return {mode, notice, message: '校方要求滑块验证，本版本暂不能在原生页面完成，请使用官方系统验证'};
        if (mode !== '1' && mode !== '0') fail('校方验证码配置尚未读全');
        if (mode === '0') return {mode, notice};
        const image = await root.fetch('/ic-web/captcha?id=' + Date.now(), {credentials: 'same-origin', headers: storedUser().token ? {token: storedUser().token} : {}});
        if (!image.ok) fail('验证码读取失败');
        const blob = await image.blob();
        if (!/^image\//.test(blob.type) || blob.size > 500000) fail('验证码格式无法识别');
        const data = await new Promise((resolve, reject) => { const reader = new root.FileReader(); reader.onload = () => resolve(reader.result); reader.onerror = reject; reader.readAsDataURL(blob); });
        return {mode, notice, image: data};
    }
    async function run(method, args, account) {
        if (busy) return {error: '上一请求尚未完成，请稍候'};
        busy = true;
        try {
            origin();
            const operations = {ready: async (_, who) => { await identity(who); return {ready: true}; }, query, mine, members: findMembers, submit, cancel, captcha};
            if (!Object.prototype.hasOwnProperty.call(operations, method)) fail('不支持的操作');
            return await operations[method](args || {}, account);
        } catch (e) { return {error: clean(e.message) || '请求未完成，请稍后重试'}; }
        finally { busy = false; }
    }
    root.NwpuStudyRoom = {run, safeRoom, validate, stamp, chinaDate, cancellable, status};
    if (typeof module !== 'undefined') module.exports = root.NwpuStudyRoom;
})(typeof window !== 'undefined' ? window : globalThis);
