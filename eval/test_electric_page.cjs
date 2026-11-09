// 一卡通“交电费”页面读电量（NwpuCampus.electricPage）。不需要浏览器：用最小的假页面跑。
// 运行：node eval/test_electric_page.cjs
const path = require('path');
let count = 0;
function check(name, ok) { if (!ok) { console.error('FAIL ' + name); process.exit(1); } count++; console.log('PASS ' + name); }
function page(origin, vue, text) {
    globalThis.location = {origin};
    globalThis.document = {
        querySelector: sel => sel === '#app' && vue ? {__vue__: vue} : null,
        body: {innerText: text || ''}
    };
}
const C = require(path.join(__dirname, '..', 'android', 'app', 'assets', 'nwpu-campus.js'));
const Y = 'https://yktapp.nwpu.edu.cn';

page('https://jwxt.nwpu.edu.cn', null, '');
check('别的站点不读', C.electricPage().state === 'elsewhere');

// Vue 组件数据里 showData 带“当前剩余电量”
page(Y, {$data: {loading: false}, $children: [{$data: {aboutEleric: {electricInfo: {showData: {'当前剩余电量': '4.68', '房间': '保密'}}}}, $children: []}]}, '');
let r = C.electricPage();
check('组件数据:' + JSON.stringify(r), r.state === 'ok' && r.fields[0].label === '当前剩余电量' && r.fields[0].value === '4.68 度');
check('不带宿舍信息', JSON.stringify(r).indexOf('房间') < 0);

// {name, value} 列表
page(Y, {$data: {list: [{name: '缴费金额', value: '0'}, {name: '剩余电量', value: '12.5度'}]}, $children: []}, '');
r = C.electricPage();
check('名称/值列表', r.state === 'ok' && r.fields[0].value === '12.5 度');

// 只有页面文字
page(Y, null, '电费缴纳\n当前剩余电量：  23.1 度\n去缴费');
r = C.electricPage();
check('页面文字', r.state === 'ok' && r.fields[0].value === '23.1 度');

// 还没加载出来 / 只有选择宿舍
page(Y, {$data: {campus: '', building: ''}, $children: []}, '请选择校区');
check('没加载出来继续等', C.electricPage().state === 'loading');

// 循环引用、私有字段不炸
const a = {$data: {}}; a.$data.self = a.$data; a.$data._secret = {'剩余电量': '99'}; a.$children = [];
page(Y, a, '');
check('循环引用和私有字段', C.electricPage().state === 'loading');

// 离谱的数不收
page(Y, {$data: {showData: {'当前剩余电量': '99999999'}}, $children: []}, '');
check('离谱的数不收', C.electricPage().state === 'loading');

console.log('PASS ' + count);
