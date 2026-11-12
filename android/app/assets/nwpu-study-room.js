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
        if (typeof value === 'number' && Number.isFinite(value)) return value;
        const t = String(value || '').replace(/\//g, '-').replace(' ', 'T');
        const n = Date.parse(t + (/Z$|[+-]\d\d:\d\d$/.test(t) ? '' : '+08:00'));
        return Number.isFinite(n) ? n : NaN;
    }
    function clock(value) { return /^(?:[01]\d|2[0-3]):[0-5]\d(?::00)?$/.test(String(value || '')); }
    function at(date, value) { return stamp(date + ' ' + (value.length === 5 ? value + ':00' : value)); }
    function array(data) { if (!Array.isArray(data)) fail('校方未返回有效列表，不能判断是否空闲'); return data; }
    const numeric = v => v !== null && v !== undefined && String(v).trim() !== '' && Number.isFinite(Number(v)) ? Number(v) : NaN;
    function safeRoom(raw, date) {
        if (!raw || !id(raw.devId)) return null;
        const rule = raw.resvRule;
        let open = Array.isArray(raw.openTimes) ? raw.openTimes : [];
        if (!open.length && Array.isArray(raw.timeScopeOpenInfo)) {
            const scopes = raw.timeScopeOpenInfo.filter(x => x &&
                ((!x.startDay && !x.endDay && raw.timeScopeOpenInfo.length === 1) ||
                (date >= String(x.startDay || '').slice(0, 10).replace(/\//g, '-') && date <= String(x.endDay || x.startDay || '').slice(0, 10).replace(/\//g, '-'))));
            open = scopes.flatMap(x => Array.isArray(x.openTimeList) ? x.openTimeList : []);
        }
        const periods = open.filter(x => x && clock(x.openStartTime) && (clock(x.openEndTime) || x.openEndTime === '24:00') && at(date, x.openEndTime) > at(date, x.openStartTime) && numeric(x.openLimit) === 0)
            .map(x => ({start: clean(x.openStartTime), end: clean(x.openEndTime), limit: 0}));
        const reservations = Array.isArray(raw.resvInfo) ? raw.resvInfo : null;
        const classes = Array.isArray(raw.cls) ? raw.cls : null;
        const min = numeric(raw.minUser), max = numeric(raw.maxUser);
        const rules = rule && typeof rule === 'object' ? {minMinutes: numeric(rule.minResvTime), maxMinutes: numeric(rule.maxResvTime), interval: numeric(rule.timeInterval), earliest: numeric(rule.earliestResvTime), latest: numeric(rule.latestResvTime), freezing: numeric(rule.freezingTime), release: numeric(rule.laterLineTime)} : null;
        const completeRules = rules && rules.minMinutes > 0 && rules.maxMinutes >= rules.minMinutes && rules.interval > 0 && rules.earliest >= rules.latest && rules.latest >= 0 && rules.freezing >= 0;
        const usable = !!completeRules && periods.length > 0 && reservations !== null && classes !== null && !raw.onlyView && Number.isInteger(min) && min > 0 && Number.isInteger(max) && max >= min;
        const occupied = reservations === null || classes === null ? null : reservations.filter(x => !x || !(Number(x.resvStatus) & 128)).map(x => ({start: clean(x && x.startTime), end: clean(x && x.endTime), buffer: rules ? rules.freezing : NaN}))
            .concat(classes.map(x => ({start: clean(x && (x.start || x.startTime)), end: clean(x && (x.end || x.endTime)), buffer: 0})));
        return {id: raw.devId, name: clean(raw.devName), location: clean(raw.labName), kind: clean(raw.kindName), min: Number.isInteger(min) && min > 0 ? min : null, max: Number.isInteger(max) && max > 0 ? max : null,
            periods, occupied, rules, available: usable, date};
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
    async function query(args, account) {
        await identity(account); const date = clean(args.date, 10), key = dateKey(date);
        rooms.clear();
        const result = await request('reserve', {sysKind: 1, resvDates: key, page: args.page || 1, pageSize: 40});
        const items = array(result.data), safe = [];
        for (const raw of items) { const room = safeRoom(raw, date); if (room) { rooms.set(String(room.id), raw); safe.push(room); } }
        return {rooms: safe, date, count: Number.isFinite(result.count) ? result.count : null, page: args.page || 1};
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
        return {bookings: items.map(x => { bookings.set(String(x.uuid), x); return {uuid: clean(x.uuid, 100), name: clean((x.resvDevInfoList || []).map(d => d.devName || d.roomName).join('、')), location: clean((x.resvDevInfoList || []).map(d => d.labName).join('、')), title: clean(x.testName), begin: clean(x.resvBeginTime), end: clean(x.resvEndTime), status: status(Number(x.resvStatus)), canCancel: cancellable(x, user)}; }), count: result.count, page: args.page || 1};
    }
    async function findMembers(args, account) {
        const user = await identity(account), key = clean(args.key, 60);
        if (key.length < 2) fail('请输入至少两个字符的姓名或学号');
        const response = await request('account/getMembers', {key, page: 1, pageNum: 10});
        return {members: array(response.data).filter(x => id(x.accNo) && !same(x.accNo, user.accNo) && x.status !== 2 && x.localstatus !== 2).map(x => { members.set(String(x.accNo), x); return {id: x.accNo, name: clean(x.trueName), account: clean(x.logonName)}; })};
    }
    async function submit(args, account) {
        if (args.confirmed !== true || args.agreed !== true) fail('请阅读须知并确认预约');
        const user = await identity(account), key = dateKey(args.date);
        if (!id(args.id) || !rooms.has(String(args.id))) fail('请先查询并选择房间');
        const response = await request('reserve', {sysKind: 1, devIds: args.id, resvDates: key});
        const raw = array(response.data).find(x => same(x.devId, args.id));
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
