const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const source = fs.readFileSync(require('node:path').join(__dirname, '../android/app/assets/nwpu-bus.js'), 'utf8');
const api = require('../android/app/assets/nwpu-bus.js');
const route = {Name: '友谊到长安'};
let checks = 0;
function check(name, run) { run(); checks++; console.log('PASS ' + name); }
check('capacity minus booked gives remaining seats', () => assert.equal(api.trips([{fcsj:'08:00', yyrs:12, kyyrs:'40'}],route)[0].remaining,28));
check('sold out is a real zero', () => assert.equal(api.trips([{fcsj:'08:00', yyrs:40, kyyrs:'40'}],route)[0].remaining,0));
check('missing counts stay unknown', () => { const t=api.trips([{fcsj:'08:00'}],route)[0]; assert.equal(t.booked,null); assert.equal(t.remaining,null); });
check('malformed counts stay unknown', () => assert.equal(api.count('12人'),null));
check('inconsistent capacity does not invent zero seats', () => assert.equal(api.trips([{fcsj:'08:00',yyrs:42,kyyrs:40}],route)[0].remaining,null));
check('invalid times are excluded', () => assert.equal(api.trips([{fcsj:'28:00'}],route).length,0));
check('server decides whether booking is open', () => assert.equal(api.trips([{fcsj:'08:00',sfkyy:'false',yyrs:0,kyyrs:40}],route)[0].open,false));
async function main() {
    const calls=[];
    const context={module:{exports:{}},URLSearchParams,location:{origin:'https://hq-bus.nwpu.edu.cn',pathname:'/h5/'},fetch:async (path,options) => {
        calls.push({path,options});
        return {ok:true,json:async () => ({isSuccess:true,data:path.includes('GetRouteByType')?{filteredBusRoutes:[{Objid:'route-1',Name:'友谊到长安'}]}:[{fcsj:'08:00',yyrs:2,kyyrs:'40',sfkyy:true}]})};
    }};
    vm.runInNewContext(source,context);
    const result=await context.module.exports.collect('2026-11-10','synthetic-account');
    check('queries return parsed counts', () => assert.equal(result[0].remaining,38));
    check('query uses official read endpoints only', () => assert.deepEqual(calls.map(x=>x.path),['/api/GetRouteByType','/api/GetReserveInfoList']));
    check('date and identity belong to current query', () => { const body=new URLSearchParams(calls[1].options.body); assert.equal(body.get('rq'),'2026/11/10'); assert.equal(body.get('no'),'synthetic-account'); assert.equal(body.get('xlId'),'route-1'); });
    const routes=['A','B','C'];
    const pending=new Map();
    const response=data=>({ok:true,json:async()=>({isSuccess:true,data})});
    const flush=()=>new Promise(resolve=>setImmediate(resolve));
    context.fetch=async (path,options) => {
        if (path.includes('GetRouteByType')) return response({filteredBusRoutes:routes.map(id=>({Objid:id,Name:id}))});
        const id=new URLSearchParams(options.body).get('xlId');
        return new Promise(resolve=>pending.set(id,()=>resolve(response([{fcsj:'08:00'}]))));
    };
    const parallel=context.module.exports.collect('2026-11-10','synthetic-account');
    await flush();
    check('independent route requests overlap with bounded concurrency', () => assert.deepEqual([...pending.keys()],['A','B']));
    pending.get('B')();
    pending.get('A')();
    await flush();
    pending.get('C')();
    const ordered=await parallel;
    check('route order is stable even when a later request completes first', () => assert.deepEqual(Array.from(ordered,t=>t.route),routes));

    let routeCalls=0;
    context.fetch=async (path,options)=>{
        if (path.includes('GetRouteByType')) {
            routeCalls++;
            return response({filteredBusRoutes:[{Objid:'A',Name:'A'}]});
        }
        return response([{objId:new URLSearchParams(options.body).get('rq'),fcsj:'08:00'}]);
    };
    context.module.exports.start('2026-11-10','synthetic-account',1);
    context.module.exports.start('2026-11-10','synthetic-account',1);
    await flush();
    check('polling does not repeat the in-flight read', () => assert.equal(routeCalls,1));
    context.module.exports.start('2026-11-11','synthetic-account',2);
    await flush();
    check('a new query refreshes the date in the existing browser', () => assert.equal(context.__louBus.items[0].id,'2026/11/11'));
    context.fetch=async () => ({ok:true,json:async () => ({isSuccess:false})});
    await assert.rejects(context.module.exports.collect('2026-11-10','synthetic-account')); checks++;
    context.location.origin='https://uis.nwpu.edu.cn';
    await assert.rejects(context.module.exports.collect('2026-11-10','synthetic-account')); checks++;
    console.log(checks+' checks passed');
}
main().catch(e=>{console.error(e);process.exitCode=1;});
