const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync(require('node:path').join(__dirname,'../android/app/assets/nwpu-bus.js'),'utf8');
const trip={id:'synthetic-trip',time:'08:00'},date='2026-11-11',account='synthetic-account';
let total=0;
function setup(responder) {
    const calls=[];
    const context={module:{exports:{}},URLSearchParams,location:{origin:'https://hq-bus.nwpu.edu.cn',pathname:'/h5/'},fetch:async (url,options)=>{
        const body=Object.fromEntries(new URLSearchParams(options.body)); calls.push({url,body});
        const data=await responder(url,body);
        return {ok:true,json:async()=>({isSuccess:true,data})};
    }};
    vm.runInNewContext(source,context);
    return {api:context.module.exports,calls,context};
}
async function test(name,run){await run();total++;console.log('PASS '+name);}
async function main(){
    const state=setup(()=>({})).api.orderState;
    await test('pending payment never means paid',()=>assert.equal(state({yydh:'order',yyzt:'待支付',fy:'5'}).paid,false));
    await test('unknown and cancelled states never mean success',()=>{for(const s of ['','已取消','已过期','未知'])assert.equal(state({yydh:'order',yyzt:s}).paid,false);});
    await test('paid status requires real order number',()=>{assert.equal(state({yyzt:'待核验'}).paid,false);assert.equal(state({yydh:'order',yyzt:'待核验'}).paid,true);});
    await test('missing or invalid fee cannot be submitted',()=>{for(const fee of [undefined,'','NaN','-1'])assert.equal(state({fy:fee}).canSubmit,false);});
    await test('agreement required before any request',async()=>{const x=setup(()=>({}));await assert.rejects(x.api.submit(trip,date,account,'5',false));assert.equal(x.calls.length,0);});
    await test('changed fee prevents write',async()=>{const x=setup(()=>({fy:'6'}));await assert.rejects(x.api.submit(trip,date,account,'5',true));assert.equal(x.calls.length,1);});
    await test('existing order is reused without duplicate write',async()=>{const x=setup(()=>({fy:'5',yydh:'existing',yyzt:'待支付'}));const r=await x.api.submit(trip,date,account,'5',true);assert.equal(r.order,'existing');assert.equal(x.calls.length,1);});
    await test('confirmed write uses school fields and is not success yet',async()=>{const x=setup(url=>url.endsWith('GetAppointmentOrder')?{fy:'5'}:'order-1');const r=await x.api.submit(trip,date,account,'5',true);assert.equal(r.paid,false);assert.equal(x.calls[1].body.PBGLID,trip.id);assert.equal(x.calls[1].body.date,'2026/11/11');assert.equal(x.calls[1].body.account,account);});
    await test('ambiguous write is not retried in same session',async()=>{const x=setup(url=>{if(url.endsWith('ConfirmAppointment'))throw Error('timeout');return {fy:'5'};});await assert.rejects(x.api.submit(trip,date,account,'5',true));await assert.rejects(x.api.submit(trip,date,account,'5',true));assert.equal(x.calls.filter(c=>c.url.endsWith('ConfirmAppointment')).length,1);});
    await test('free prepay still requires confirmed server status',async()=>{const x=setup(url=>url.endsWith('GetPrePayOrderNo')?'0':{fy:'0',yydh:'order',yyzt:'待支付'});const r=await x.api.prepare(trip,date,account,'order');assert.equal(r.paid,false);assert.equal(x.calls.length,3);});
    await test('prepay belongs to exact current order',async()=>{const x=setup(()=>({fy:'5',yydh:'other',yyzt:'待支付'}));const r=await x.api.prepare(trip,date,account,'order');assert.equal(r.prepay,undefined);assert.equal(x.calls.length,1);});
    await test('pending prepay returns preparing without creating another order',async()=>{const x=setup(url=>url.endsWith('GetPrePayOrderNo')?'':{fy:'5',yydh:'order',yyzt:'待支付'});assert.equal((await x.api.prepare(trip,date,account,'order')).preparing,true);assert.equal(x.calls[1].body.schedulingMngID,trip.id);});
    await test('cashier request keeps exact order and prepay',async()=>{const x=setup(()=>'<html>school cashier</html>');const r=await x.api.cashier(account,'order','prepay');assert.ok(r.html);assert.equal(x.calls[0].body.outTradeNo,'order');assert.equal(x.calls[0].body.prepayId,'prepay');});
    await test('cross-origin ticket calls rejected',async()=>{const x=setup(()=>({fy:'5'}));x.context.location.origin='https://example.com';await assert.rejects(x.api.quote(trip,date,account));assert.equal(x.calls.length,0);});
    console.log(total+' checks passed');
}
main().catch(e=>{console.error(e);process.exitCode=1;});
