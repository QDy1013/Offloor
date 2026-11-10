// No browser or live credentials needed: drive the real query adapter against a school-page fixture.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../android/app/assets/nwpu-auto.js'), 'utf8');
let checks = 0;
function check(name, test) { test(); checks++; console.log('PASS ' + name); }
function fixture(when = '2026-11-09T08:30:00+08:00', sites, rooms = [{name: '合成教室A'}]) {
    const instant = Date.parse(when);
    class Clock extends Date { static now() { return instant; } }
    const school = {
        campusList: sites || [{value: 'observed-c', text: '长安校区'}, {value: 'observed-y', text: '友谊校区'}],
        freeListParam: {campusId: '', dateTimeSegmentCmd: {}}, posted: {}, freeRooms: [{name: '旧教室'}], calls: 0,
        $nextTick(callback) { callback(); },
        queryFreeList() { this.calls++; this.posted.freeListParam = JSON.parse(JSON.stringify(this.freeListParam)); this.freeRooms = rooms; }
    };
    const document = {
        readyState: 'complete',
        querySelector() { return null; },
        querySelectorAll(selector) { return selector === '#room-borrow-app,.container' ? [{__vue__: school}] : []; }
    };
    const root = {
        Date: Clock, document,
        location: {protocol: 'https:', hostname: 'jwxt.nwpu.edu.cn', pathname: '/student/for-std/room-free', origin: 'https://jwxt.nwpu.edu.cn', href: 'https://jwxt.nwpu.edu.cn/student/for-std/room-free'},
        NwpuCampus: {
            allowed() { return true; }, safeUrl() { return 'https://jwxt.nwpu.edu.cn/student/for-std/room-free'; },
            capture() { return {modules: {classroom: {state: school.freeRooms.length ? 'ok' : 'empty', fields: [], links: [], tables: school.freeRooms.length ? [{title: '查询结果', columns: ['教室'], rows: school.freeRooms.map(room => [room.name])}] : []}}}; }
        }
    };
    root.window = root;
    vm.createContext(root); vm.runInContext(source, root); root.NwpuAuto.focus();
    return {root, school, api: root.NwpuAuto, inspect: () => root.NwpuAuto.inspect('classroom', [])};
}
const request = {campus: '友谊校区', date: '2026-11-09', start: '14:00', end: '16:00'};
check('uses the selected campus identifier from the actual school options', () => {
    const f = fixture(); assert.equal(f.api.setClassroomQuery(request), true); const r = f.inspect();
    assert.equal(f.school.posted.freeListParam.campusId, 'observed-y');
    assert.equal(f.school.byWeek, false); assert.equal(f.school.byTime, true);
    assert.match(r.snapshot.modules.classroom.fields[0].value, /友谊校区.*2026-11-09 14:00—16:00/);
});
check('submits both selected times and the current day unchanged', () => {
    const f = fixture(); f.api.setClassroomQuery(request); f.inspect();
    assert.deepEqual(f.school.posted.freeListParam.dateTimeSegmentCmd, {startDateTime: '2026-11-09', endDateTime: '2026-11-09', startTime: '14:00', endTime: '16:00'});
});
check('repeated status polling does not resubmit the query', () => {
    const f = fixture(); f.api.setClassroomQuery(request); f.inspect(); f.inspect(); assert.equal(f.school.calls, 1);
});
check('an unavailable selected campus does not silently fall back', () => {
    const f = fixture(undefined, [{value: 'observed-c', text: '长安校区'}]); f.api.setClassroomQuery(request); const r = f.inspect();
    assert.equal(f.school.calls, 0); assert.equal(r.snapshot.modules.classroom.state, 'unavailable'); assert.equal(r.snapshot.modules.classroom.tables.length, 0);
});
check('an unsupported campus never submits and hides old results', () => {
    const f = fixture(); assert.equal(f.api.setClassroomQuery({...request, campus: '未知校区'}), false); const r = f.inspect();
    assert.equal(f.school.calls, 0); assert.equal(r.snapshot.modules.classroom.tables.length, 0);
});
check('yesterday and tomorrow cannot masquerade as a fresh current-day query', () => {
    for (const date of ['2026-11-08', '2026-11-10']) { const f = fixture(); assert.equal(f.api.setClassroomQuery({...request, date}), false); f.inspect(); assert.equal(f.school.calls, 0); }
});
check('invalid or reversed clock times are rejected before submitting', () => {
    for (const pair of [['24:00', '25:00'], ['16:00', '14:00'], ['14:00', '14:00']]) {
        const f = fixture(); assert.equal(f.api.setClassroomQuery({...request, start: pair[0], end: pair[1]}), false); f.inspect(); assert.equal(f.school.calls, 0);
    }
});
check('an explicit empty response is represented as no returned rooms', () => {
    const f = fixture(undefined, undefined, []); f.api.setClassroomQuery(request); const r = f.inspect();
    assert.equal(r.snapshot.modules.classroom.state, 'empty'); assert.equal(r.snapshot.modules.classroom.tables.length, 0);
});
check('the default query remains on the same day near midnight', () => {
    const f = fixture('2026-11-09T23:59:00+08:00'); f.inspect(); const p = f.school.posted.freeListParam.dateTimeSegmentCmd;
    assert.equal(p.startDateTime, '2026-11-09'); assert.equal(p.endDateTime, '2026-11-09'); assert.equal(p.endTime, '23:59'); assert.ok(p.startTime < p.endTime);
});
check('the default daytime query covers the next hour', () => {
    const f = fixture(); f.inspect(); const p = f.school.posted.freeListParam.dateTimeSegmentCmd;
    assert.equal(p.startTime, '08:30'); assert.equal(p.endTime, '09:30');
});
check('page mutations and unrelated pending requests cannot complete a room query', () => {
    const f = fixture();
    f.school.freeRooms = []; f.school.queryFreeList = function () { this.calls++; };
    f.api.setClassroomQuery(request); f.inspect();
    f.root.__louAutoDriver.changed++; f.root.__louAutoDriver.pending = 1;
    let r = f.inspect(); assert.equal(r.settling, true); assert.equal(r.preparing, true);
    f.root.__louAutoDriver.pending = 0;
    r = f.inspect(); assert.equal(r.settling, true); assert.equal(r.pending, 1);
    assert.equal(f.school.calls, 1);
});
check('a fresh successful empty response completes only the matching query', () => {
    const f = fixture();
    f.school.freeRooms = []; f.school.queryFreeList = function () { this.calls++; };
    f.api.setClassroomQuery(request); f.inspect();
    f.school.posted.freeListParam = {...f.school.freeListParam, campusId: 'observed-c'};
    assert.equal(f.inspect().settling, true);
    f.school.posted.freeListParam = JSON.parse(JSON.stringify(f.school.freeListParam));
    const r = f.inspect(); assert.equal(r.settling, false); assert.equal(r.pending, 0);
    assert.equal(r.snapshot.modules.classroom.state, 'empty');
});
check('an old response with identical conditions cannot finish a new query', () => {
    const f = fixture();
    f.school.posted.freeListParam = {campusId: 'observed-y', dateTimeSegmentCmd: {startDateTime: request.date, endDateTime: request.date, startTime: request.start, endTime: request.end}};
    f.school.queryFreeList = function () { this.calls++; };
    f.api.setClassroomQuery(request); assert.equal(f.inspect().settling, true);
    f.school.posted.freeListParam = JSON.parse(JSON.stringify(f.school.freeListParam));
    assert.equal(f.inspect().settling, false);
});
check('a completed room response is not blocked by unrelated long-running requests', () => {
    const f = fixture(); f.root.__louAutoDriver.pending = 2;
    f.api.setClassroomQuery(request); const r = f.inspect();
    assert.equal(r.settling, false); assert.equal(r.pending, 0);
    assert.equal(r.snapshot.modules.classroom.state, 'ok');
});
console.log(checks + ' checks passed');
