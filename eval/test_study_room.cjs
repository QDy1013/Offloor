'use strict';
// Synthetic official responses only: never connects to the school or books a room.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const app = path.resolve(__dirname, '../android/app');
const script = fs.readFileSync(path.join(app, 'assets/nwpu-study-room.js'), 'utf8');
const NOW = Date.parse('2026-11-12T08:00:00+08:00');
const DATE = '2026-11-12';
let checks = 0;
function check(name, operation) { operation(); console.log('PASS ' + name); checks++; }
function rawRoom(extra = {}) {
    return {devId: 123, devName: '研修室 A', labName: '图书馆', minUser: 1, maxUser: 3,
        openTimes: [{openStartTime: '08:00', openEndTime: '22:00', openLimit: 0}], resvInfo: [], cls: [],
        resvRule: {minResvTime: 30, maxResvTime: 180, timeInterval: 30, earliestResvTime: 10080, latestResvTime: 0, freezingTime: 0, laterLineTime: 800}, ...extra};
}
function harness(options = {}) {
    const calls = [];
    class Clock extends Date { static now() { return NOW; } }
    const root = {Date: Clock, URLSearchParams, location: {origin: 'https://ic.nwpu.edu.cn'},
        sessionStorage: {getItem() { return JSON.stringify({token: 'synthetic-token'}); }},
        fetch: async (url, init) => {
            calls.push({url, init, payload: init.body ? JSON.parse(init.body) : null});
            if (options.handle) { const response = await options.handle(url, init); if (response !== undefined) return response; }
            let data;
            if (url.startsWith('/ic-web/auth/userInfo')) data = {accNo: 10, logonName: options.account || 'owner'};
            else if (url.startsWith('/ic-web/sysConfig/public')) data = {resvCode: options.mode || '0'};
            else if (url.startsWith('/ic-web/sysInfo')) data = [{content: '请遵守校方研修室使用规则'}];
            else if (url.startsWith('/ic-web/account/getMembers')) data = [{accNo: 20, trueName: '测试同行人', logonName: 'member'}];
            else if (url.startsWith('/ic-web/reserve/resvInfo')) data = options.bookings || [];
            else if (url.startsWith('/ic-web/reserve')) data = init.method === 'POST' ? {} : [rawRoom(options.room)];
            else throw new Error('unexpected endpoint: ' + url);
            return {ok: true, json: async () => ({code: 0, data, count: Array.isArray(data) ? data.length : 1})};
        }};
    root.window = root;
    vm.createContext(root); vm.runInContext(script, root);
    return {api: root.NwpuStudyRoom, calls, root, posts: () => calls.filter(c => c.init.method === 'POST')};
}
const payload = (extra = {}) => ({id: 123, date: DATE, start: '09:00', end: '10:00', title: '测试主题', members: [], agreed: true, confirmed: true, ...extra});
async function main() {
    let h = harness(), result = await h.api.run('query', {date: DATE}, 'owner');
    check('query displays actual room rules', () => { assert.equal(result.rooms.length, 1); assert.equal(result.rooms[0].available, true); assert.equal(result.rooms[0].min, 1); });
    result = await h.api.run('submit', payload(), 'owner');
    check('confirmed submit dispatches one write with verified identity', () => { assert.equal(result.success, true); assert.equal(h.posts().length, 1); assert.equal(h.posts()[0].payload.appAccNo, 10); assert.deepEqual(Array.from(h.posts()[0].payload.resvMember), [10]); });
    check('availability is refreshed immediately before write', () => { const queries = h.calls.filter(c => c.url.startsWith('/ic-web/reserve?')); assert.equal(queries.length, 2); assert.match(queries[1].url, /devIds=123/); });

    for (const [name, room] of [['missing occupancy', {resvInfo: undefined}], ['missing class schedule', {cls: undefined}], ['missing rules', {resvRule: null}], ['unknown opening limit', {openTimes: [{openStartTime: '08:00', openEndTime: '22:00'}]}], ['view only', {onlyView: true}], ['missing participant limit', {minUser: undefined}]]) {
        h = harness({room}); result = await h.api.run('query', {date: DATE}, 'owner');
        check(name + ' fails closed', () => assert.equal(result.rooms[0].available, false));
    }
    h = harness(); const room = h.api.safeRoom(rawRoom(), DATE);
    for (const [name, date, start, end, count] of [['invalid calendar day', '2026-02-30', '09:00', '10:00', 1], ['past time', DATE, '07:00', '08:00', 1], ['end before start', DATE, '10:00', '09:00', 1], ['interval mismatch', DATE, '09:10', '10:10', 1], ['too short', DATE, '09:00', '09:10', 1], ['too long', DATE, '09:00', '13:00', 1], ['outside opening', DATE, '21:00', '23:00', 1], ['too many participants', DATE, '09:00', '10:00', 4]]) {
        check(name + ' rejected', () => assert.throws(() => h.api.validate(room, date, start, end, count, NOW)));
    }
    check('overlap rejected', () => { const r = h.api.safeRoom(rawRoom({resvInfo: [{startTime: DATE + ' 09:30:00', endTime: DATE + ' 10:30:00', resvStatus: 0}]}), DATE); assert.throws(() => h.api.validate(r, DATE, '09:00', '10:00', 1, NOW), /占用/); });
    check('unknown occupancy timestamp rejected', () => { const r = h.api.safeRoom(rawRoom({cls: [{start: 'unknown', end: 'unknown'}]}), DATE); assert.throws(() => h.api.validate(r, DATE, '09:00', '10:00', 1, NOW), /未能解析/); });

    h = harness({account: 'other'}); result = await h.api.run('query', {date: DATE}, 'owner');
    check('cross-account session blocked before query', () => { assert.match(result.error, /不一致/); assert.equal(h.calls.length, 1); });
    h = harness(); h.root.location.origin = 'https://example.invalid'; result = await h.api.run('query', {date: DATE}, 'owner');
    check('untrusted origin blocked before network', () => { assert.ok(result.error); assert.equal(h.calls.length, 0); });
    h = harness(); result = await h.api.run('submit', payload(), 'owner');
    check('unqueried room cannot be submitted', () => { assert.match(result.error, /先查询/); assert.equal(h.posts().length, 0); });
    h = harness(); await h.api.run('query', {date: DATE}, 'owner'); result = await h.api.run('submit', payload({confirmed: false}), 'owner');
    check('explicit confirmation required', () => { assert.match(result.error, /确认/); assert.equal(h.posts().length, 0); });
    result = await h.api.run('submit', payload({members: [999]}), 'owner');
    check('unverified participant rejected without write', () => { assert.match(result.error, /同行人/); assert.equal(h.posts().length, 0); });
    await h.api.run('members', {key: 'member'}, 'owner'); result = await h.api.run('submit', payload({members: [20]}), 'owner');
    check('verified participant included without invented identities', () => { assert.equal(result.success, true); assert.deepEqual(Array.from(h.posts()[0].payload.resvMember), [10, 20]); });
    for (const mode of ['1', '2', 'unknown']) {
        h = harness({mode}); await h.api.run('query', {date: DATE}, 'owner'); result = await h.api.run('submit', payload(), 'owner');
        check('captcha mode ' + mode + ' cannot bypass school verification', () => { assert.ok(result.error); assert.equal(h.posts().length, 0); });
    }
    h = harness({handle: async (url, init) => { if (init.method === 'POST') throw new Error('network interrupted'); }});
    await h.api.run('query', {date: DATE}, 'owner'); result = await h.api.run('submit', payload(), 'owner');
    check('network loss after submit is unknown and never retried', () => { assert.equal(result.unknown, true); assert.match(result.message, /勿重复/); assert.equal(h.posts().length, 1); });
    h = harness({handle: async (url, init) => { if (init.method === 'POST') return {ok: true, json: async () => ({code: 1, message: '时段冲突'})}; }});
    await h.api.run('query', {date: DATE}, 'owner'); result = await h.api.run('submit', payload(), 'owner');
    check('school rejection stays an error without retries', () => { assert.match(result.error, /时段冲突/); assert.equal(h.posts().length, 1); });

    const booking = {uuid: 'test-booking', classKind: 1, appAccNo: 10, resvStatus: 0, resvDevInfoList: [{devName: 'A'}]};
    h = harness({bookings: [booking, {...booking, uuid: 'other-booking', appAccNo: 30}]}); result = await h.api.run('mine', {}, 'owner');
    check('my bookings exclude unrelated accounts', () => { assert.equal(result.bookings.length, 1); assert.equal(result.bookings[0].canCancel, true); });
    result = await h.api.run('cancel', {uuid: 'test-booking', confirmed: true}, 'owner');
    check('cancel sends exactly one verified booking id', () => { assert.equal(result.success, true); assert.equal(h.posts().length, 1); assert.equal(h.posts()[0].payload.uuid, 'test-booking'); });
    result = await h.api.run('cancel', {uuid: 'test-booking', confirmed: true}, 'owner');
    check('same cancelled booking cannot be dispatched twice', () => { assert.ok(result.error); assert.equal(h.posts().length, 1); });
    check('participant cannot cancel somebody else\'s booking', () => assert.equal(h.api.cancellable({...booking, appAccNo: 30}, {accNo: 10}), false));
    check('in-use booking cannot be cancelled', () => assert.equal(h.api.cancellable({...booking, resvStatus: 4}, {accNo: 10}), false));
    for (const state of [undefined, null, '', 'unknown', -1, 1.5]) {
        h = harness({bookings: [{...booking, resvStatus: state}]});
        await h.api.run('mine', {}, 'owner'); result = await h.api.run('cancel', {uuid: 'test-booking', confirmed: true}, 'owner');
        check('invalid booking status ' + String(state) + ' blocks cancellation', () => { assert.ok(result.error); assert.equal(h.posts().length, 0); });
    }
    h = harness({bookings: [booking], handle: async (url, init) => { if (init.method === 'POST') throw new Error('network interrupted'); }});
    await h.api.run('mine', {}, 'owner'); result = await h.api.run('cancel', {uuid: 'test-booking', confirmed: true}, 'owner');
    check('network loss after cancellation is unknown without retry', () => { assert.equal(result.unknown, true); assert.equal(h.posts().length, 1); });

    let release;
    h = harness({handle: (url) => url.includes('auth/userInfo') ? new Promise(resolve => { release = () => resolve({ok: true, json: async () => ({code: 0, data: {accNo: 10, logonName: 'owner'}})}); }) : undefined});
    const pending = h.api.run('ready', {}, 'owner'); result = await h.api.run('mine', {}, 'owner');
    check('parallel requests cannot dispatch while busy', () => { assert.match(result.error, /上一请求/); assert.equal(h.calls.length, 1); });
    release(); await pending;

    const session = fs.readFileSync(path.join(app, 'src/com/nopalou/StudyRoomSession.java'), 'utf8');
    const expression = session.match(/;if\(!window\.__louStudyReady[^\n]+?JSON\.stringify\(window\.__louStudyReady\)/)[0]
        .replace('"+JSONObject.quote(account)+"', JSON.stringify('owner'));
    const connection = {calls: 0, NwpuStudyRoom: {run: async () => { connection.calls++; return {ready: true}; }}};
    connection.window = connection; vm.createContext(connection);
    vm.runInContext(expression, connection); await Promise.resolve();
    vm.runInContext(expression, connection); vm.runInContext(expression, connection);
    check('ready polling never starts a second identity request', () => assert.equal(connection.calls, 1));
    const activity = fs.readFileSync(path.join(app, 'src/com/nopalou/StudyRoomActivity.java'), 'utf8');
    check('write guard persists before dispatch and is not erased by lifecycle stop', () => {
        const write = activity.slice(activity.indexOf('private void write('));
        assert.ok(write.indexOf('if (!markWrite(true))') < write.indexOf('call(method,payload'));
        assert.match(activity, /writeState\(\)\.edit\(\)\.putBoolean\(campus\.owner,pending\)\.commit\(\)/);
        const stop = activity.slice(activity.indexOf('void onStop()'), activity.indexOf('void onDestroy()'));
        assert.doesNotMatch(stop, /markWrite\(false\)/);
        assert.match(write, /if \(pendingWrite\(\)\)/);
        assert.match(activity, /if \(mine && reconciled\)/);
    });
    check('temporary pause does not invalidate requests or leave busy stuck', () => {
        assert.match(activity, /void onStart\(\)\s*\{\s*super\.onStart\(\); active=true/);
        assert.doesNotMatch(activity, /void onResume\(|void onPause\(/);
        assert.match(activity, /void onStop\(\)\s*\{\s*active=false; \+\+epoch; ready=false; busy=false/);
    });
    check('ready errors are reported immediately instead of cached until timeout', () => {
        assert.match(session, /else if \(!result\.optString\("error"\)\.isEmpty\(\)\) fail\(result\.optString\("error"\)\)/);
    });
    const mainActivity = fs.readFileSync(path.join(app, 'src/com/nopalou/MainActivity.java'), 'utf8');
    check('manual draft preserves all editable fields across activity recreation', () => {
        assert.match(mainActivity, /state\.putBundle\("manualCardDraft", manualDraftState\.get\(\)\)/);
        assert.match(mainActivity, /restoreManualDraft = b\.getBundle\("manualCardDraft"\)/);
        assert.match(mainActivity, /else showManualCardForm\(card, draft\)/);
        for (const field of ['cardId', 'title', 'place', 'notes']) assert.ok(mainActivity.includes('saved.putString("' + field + '"'));
        for (const field of ['title', 'place', 'notes']) assert.ok(mainActivity.includes('draft.getString("' + field + '"'));
        assert.match(mainActivity, /saved\.putLong\("deadline", deadline\[0\]\)/);
        assert.match(mainActivity, /deadline\[0\] = draft\.getLong\("deadline", 0\)/);
        assert.match(mainActivity, /if \(!id\.isEmpty\(\) && card == null\)/);
    });
    // ---------- 体验改进：不可约原因、状态码、时段计算与冲突提示 ----------
    for (const [name, room, pattern] of [
        ['rules missing', {resvRule: null}, /规则/],
        ['opening hours missing', {openTimes: []}, /开放时段/],
        ['view only room', {onlyView: true}, /仅可查看/],
        ['participant limit missing', {minUser: undefined}, /人数/],
        ['occupancy missing', {resvInfo: undefined}, /占用/],
        ['class schedule missing', {cls: undefined}, /课表/]]) {
        h = harness({room}); result = await h.api.run('query', {date: DATE}, 'owner');
        const row = result.rooms[0];
        check('unavailable room explains why: ' + name, () => { assert.equal(row.available, false); assert.match(row.reason, pattern); });
    }
    h = harness(); result = await h.api.run('query', {date: DATE}, 'owner');
    check('bookable room has no reason and reports the school count', () => {
        assert.equal(result.rooms[0].reason, ''); assert.equal(result.count, 1); assert.equal(result.page, 1);
    });
    h = harness({bookings: [booking]}); result = await h.api.run('mine', {}, 'owner');
    check('my bookings carry the numeric school state', () => assert.equal(result.bookings[0].state, 0));
    h = harness({bookings: [{...booking, resvStatus: 'unknown'}]}); result = await h.api.run('mine', {}, 'owner');
    check('unreadable booking state stays null instead of guessing', () => assert.equal(result.bookings[0].state, null));

    check('room cards use computed slots rather than hand-picked times', () => {
        assert.match(activity, /StudyRoomPlan\.slots\(plan, date, busySlots/);
        assert.match(activity, /slotChip\(line, slot\.label\(\), \(\) -> booking\(row, slot\.start, slot\.end\)\)/);
    });
    const guard = activity.slice(activity.indexOf('private String check('), activity.indexOf('private void searchMember('));
    check('booking panel rejects impossible times before dispatch', () => {
        for (const reason of ['结束时间要晚于开始时间', '开始时间已经过去', '开放时间', '预约时长须为', '位参与者', '已被占用']) {
            assert.ok(guard.includes(reason), 'missing guard: ' + reason);
        }
    });
    check('class and personal clashes are surfaced but never block booking', () => {
        assert.match(activity, /classesOn\(date\)/);
        assert.match(activity, /reservedOn\(date\)/);
        assert.match(activity, /时间冲突：/);
        assert.doesNotMatch(activity, /if \(!clashes\.isEmpty\(\)\) return/);
    });
    check('home reminder is optional and only after an explicit confirmation', () => {
        const submit = activity.slice(activity.indexOf('private void submit('), activity.indexOf('private void confirmCancel('));
        assert.match(submit, /write\("submit", payload,\s*\(\) -> \{ if \(remind\.isChecked\(\)\) queueReminder\(/);
        assert.match(activity, /remind\.setChecked\(true\)/);
    });
    check('a write that never left releases the duplicate-operation guard', () => {
        const write = activity.slice(activity.indexOf('private void write('));
        assert.match(write, /if \(!sent \|\| !ready\) \{ markWrite\(false\)/);
    });
    check('date picker is bounded to the bookable horizon', () => {
        assert.match(activity, /setMinDate\(StudyRoomPlan\.at\(StudyRoomPlan\.day\(now\), 0\)\)/);
        assert.match(activity, /setMaxDate\(/);
    });
    check('captcha image is decoded within a size limit and fails closed', () => {
        assert.match(activity, /encoded\.length\(\) > 700000/);
        assert.match(activity, /验证码图片无法读取，请关闭后重新选择房间/);
    });
    check('new slot logic is part of the release checks', () => {
        assert.ok(fs.existsSync(path.join(app, '..', 'test', 'Step59Test.java')));
        assert.match(fs.readFileSync(path.join(__dirname, 'run_checks.py'), 'utf8'), /"StudyRoomPlan"\)/);
    });
    // ---------- 回归：空占用导致的崩溃、24:00 边界、翻页与改时间的死路 ----------
    check('room card checks for a missing occupancy list before reading it', () => {
        const card = activity.slice(activity.indexOf('private void roomCard('), activity.indexOf('private String occupiedText('));
        const nullCheck = card.indexOf('if (occupied == null)');
        const firstUse = card.indexOf('occupied.length()');
        assert.ok(nullCheck >= 0 && firstUse > nullCheck, 'occupancy is read before the null check');
    });
    h = harness({room: {openTimes: [{openStartTime: '20:00', openEndTime: '24:00', openLimit: 0}]}});
    result = await h.api.run('query', {date: DATE}, 'owner');
    check('opening hours that end at 24:00 stay readable', () => {
        assert.equal(result.rooms[0].available, true);
        assert.equal(result.rooms[0].periods[0].end, '24:00');
    });
    h = harness({bookings: [{...booking, resvStatus: null}]}); result = await h.api.run('mine', {}, 'owner');
    check('a missing booking state never falls back to code 0', () => {
        assert.equal(result.bookings[0].state, null);
        assert.equal(result.bookings[0].status, '状态待确认');
    });
    check('pagination stops on a page that is not full', () => {
        assert.match(activity, /boolean more = rows\.length\(\) >= PAGE_SIZE/);
    });
    check('picking a start time with no legal end keeps the old choice', () => {
        assert.match(activity, /chosen\[0\] = previous; toast\("这个时间没有可用时长/);
    });
    check('an unconfirmed submit never leaves a home reminder behind', () => {
        const write = activity.slice(activity.indexOf('private void write('));
        assert.match(write, /\}, \(\) -> reminderRow = null\);/);
        assert.match(write, /if \(!sent \|\| !ready\) \{ markWrite\(false\); reminderRow = null/);
    });

    console.log(checks + ' study room checks passed');
}
main().catch(error => { console.error(error); process.exitCode = 1; });
