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
    await test('cashier supports redirect url string and object payloads',async()=>{
        const u=await setup(()=>'https://pay.nwpu.edu.cn/cashier?id=1').api.cashier(account,'order','prepay');
        assert.equal(u.url,'https://pay.nwpu.edu.cn/cashier?id=1');
        const wx=await setup(()=>'weixin://wap/pay?prepayid=wx1').api.cashier(account,'order','prepay');
        assert.equal(wx.url,'weixin://wap/pay?prepayid=wx1');
        const obj=await setup(()=>({url:'alipays://platformapi/startapp?appId=20000125'})).api.cashier(account,'order','prepay');
        assert.equal(obj.url,'alipays://platformapi/startapp?appId=20000125');
    });
    await test('cross-origin ticket calls rejected',async()=>{const x=setup(()=>({fy:'5'}));x.context.location.origin='https://example.com';await assert.rejects(x.api.quote(trip,date,account));assert.equal(x.calls.length,0);});
    await test('orders skips zero-booked trips and filters inactive states',async()=>{
        const trips=[
            {id:'t-zero',time:'07:00',route:'友谊到长安',booked:0},
            {id:'t-paid',time:'08:00',route:'友谊到长安',booked:5},
            {id:'t-pay',time:'09:00',route:'长安到友谊',booked:null},
            {id:'t-cancel',time:'10:00',route:'友谊到长安',booked:3},
            {id:'t-err',time:'11:00',route:'长安到友谊',booked:2}
        ];
        const x=setup((url,body)=>{
            if (body.objid==='t-paid') return {yydh:'YY1',yyzt:'待核验',fy:'4'};
            if (body.objid==='t-pay') return {yydh:'YY2',yyzt:'待支付',fy:'4'};
            if (body.objid==='t-cancel') return {yydh:'YY3',yyzt:'已取消',fy:'4'};
            if (body.objid==='t-err') throw new Error('boom');
            return {yydh:'',yyzt:'',fy:'4'};
        });
        const list=await x.api.orders(trips,date,account);
        assert.equal(x.calls.some(c=>c.body.objid==='t-zero'),false);
        assert.equal(list.length,2);
        assert.equal(list[0].order,'YY1'); assert.equal(list[0].paid,true);
        assert.equal(list[1].order,'YY2'); assert.equal(list[1].payable,true);
    });
    await test('scanDates reuses currentTrips and isolates per-day failures',async()=>{
        const x=setup((url,body)=>{
            if (url.endsWith('GetRouteByType')) {
                return {filteredBusRoutes:[{Objid:'r1',Name:'长安到友谊'}]};
            }
            if (url.endsWith('GetReserveInfoList')) {
                if (body.rq==='2026/11/13') throw new Error('network');
                return [{objId:'t-next',fcsj:'14:00',yyrs:3,kyyrs:40,sfkyy:true}];
            }
            if (url.endsWith('GetAppointmentOrder')) {
                if (body.objid==='t-today') return {yydh:'O-TODAY',yyzt:'待核验',fy:'4'};
                if (body.objid==='t-next') return {yydh:'O-NEXT',yyzt:'待支付',fy:'4'};
            }
            return {};
        });
        const res=await x.api.scanDates(['2026-11-11','2026-11-12','2026-11-13'],'2026-11-11',[{id:'t-today',time:'08:00',route:'友谊到长安',booked:2}],account);
        assert.equal(res.results.length,3);
        assert.equal(res.results[0].ok,true); assert.equal(res.results[0].tickets[0].order,'O-TODAY');
        assert.equal(res.results[1].ok,true); assert.equal(res.results[1].tickets[0].order,'O-NEXT');
        assert.equal(res.results[2].ok,false); assert.equal(res.results[2].tickets.length,0);
        // 2026-11-11 复用传入的 currentTrips，仅额外对 12/13 两日调过 GetRouteByType
        assert.equal(x.calls.filter(c=>c.url.endsWith('GetRouteByType')).length,2);
    });
    await test('scanDates merges GetMyAppointment objid and fcdd and stops early once matched',async()=>{
        const x=setup((url,body)=>{
            if (url.endsWith('GetMyAppointment')) {
                if (body.YYZT==='待核验') return {list:[{objid:'5013261121bf1cc533',yydh:'O-TODAY',ZT:'待核验',fcsj:'14:00:00',fcdd:'友谊校区图书馆东门',qd:'友谊校区',zd:'长安校区',fy:'5.00',yktyfddh:'PRE1'}]};
                return {list:[]};
            }
            if (url.endsWith('GetAppointmentOrder')) {
                if (body.objid==='t-today') return {yydh:'O-TODAY',yyzt:'待核验',fy:'5'};
                throw Error('unexpected extra quote');
            }
            return {};
        });
        const res=await x.api.scanDates(['2026-11-21','2026-11-22','2026-11-23'],'2026-11-21',[{id:'t-today',time:'14:00',route:'友谊到长安',booked:5},{id:'t-other',time:'18:00',route:'友谊到长安',booked:10}],account);
        assert.equal(res.results[0].tickets.length,1);
        assert.equal(res.results[0].tickets[0].qrCode,'5013261121bf1cc533');
        assert.equal(res.results[0].tickets[0].place,'友谊校区图书馆东门');
        assert.equal(x.calls.some(c=>c.url.endsWith('GetRouteByType')),false);
        assert.equal(x.calls.filter(c=>c.url.endsWith('GetAppointmentOrder')).length,1);
    });
    await test('scanDates preserves unmatched GetMyAppointment tickets in extraTickets',async()=>{
        const x=setup((url,body)=>{
            if (url.endsWith('GetMyAppointment')) {
                if (body.YYZT==='待支付') return {list:[{objid:'5013261125aa112233',yydh:'O-LATER',ZT:'待支付',fcsj:'09:00:00',qd:'长安校区',zd:'友谊校区',fy:'5',syzfsj:12}]};
                return {list:[]};
            }
            if (url.endsWith('GetRouteByType')) return {filteredBusRoutes:[]};
            return {};
        });
        const res=await x.api.scanDates(['2026-11-21'],'2026-11-21',[],account);
        assert.equal(res.extraTickets.length,1);
        assert.equal(res.extraTickets[0].qrCode,'5013261125aa112233');
        assert.equal(res.extraTickets[0].payRemainMin,12);
    });
    console.log(total+' checks passed');
}
main().catch(e=>{console.error(e);process.exitCode=1;});
