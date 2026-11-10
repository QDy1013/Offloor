const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const script=fs.readFileSync(require('node:path').join(__dirname,'../android/app/assets/nwpu-room-day.js'),'utf8');
const date=new Date(Date.now()+28800000).toISOString().slice(0,10);
function fixture(){
    const requests=[];
    const room={id:8,nameZh:'测试教室',building:{id:5,campus:{id:3}}};
    const root={location:{hostname:'jwxt.nwpu.edu.cn',pathname:'/student/for-std/room-free'},document:{querySelectorAll:()=>[{__vue__:{freeRooms:[room]}}]},setTimeout,clearTimeout,AbortController,Date};
    root.window=root;
    root.fetch=async(url,options)=>{
        const body=options.body&&JSON.parse(options.body); requests.push({url,body});
        let data;
        if(url.includes('get-unit-campus'))data=[1,2,3,4].map(value=>({value,name:'第'+value+'节',time:'08:30-09:15',changeTime:'09:00-09:45'}));
        else if(url.includes('get-table-layout'))data={startDate:date.slice(0,4)+'-01-01',changeMonth:1,changeDayOfMonth:1};
        else if(body.dateTimeSegmentCmd.units[0]==='1')data={roomList:[room]};
        else if(body.dateTimeSegmentCmd.units[0]==='2')data={roomList:[]};
        else if(body.dateTimeSegmentCmd.units[0]==='3')data={message:'未返回教室列表'};
        else throw Error('network');
        return {ok:true,json:async()=>data};
    };
    vm.runInNewContext(script,root);
    return {root,requests};
}
(async()=>{
    const f=fixture(),result=await f.root.NwpuRoomDay.load('测试教室',date);
    assert.deepEqual(Array.from(result.items,i=>i.state),['free','occupied','unknown','unknown']);
    console.log('PASS empty successful list means occupied; malformed and failed responses stay unknown');
    assert.ok(result.items.every(i=>i.time==='09:00-09:45'));
    console.log('PASS school seasonal timetable selects the current section times');
    const queries=f.requests.filter(r=>r.body);
    assert.equal(queries.length,4);
    for(const q of queries){assert.equal(q.body.roomId,'8');assert.equal(q.body.buildingId,'5');assert.equal(q.body.campusId,'3');assert.equal(q.body.dateTimeSegmentCmd.startDateTime,date);assert.equal(q.body.dateTimeSegmentCmd.endDateTime,date);}
    console.log('PASS each section query is constrained to the selected room and current day');
    await assert.rejects(f.root.NwpuRoomDay.load('其他教室',date));
    await assert.rejects(f.root.NwpuRoomDay.load('测试教室','2000-01-01'));
    assert.equal(f.requests.length,6);
    console.log('PASS unmatched rooms and stale dates never issue requests');
})().catch(e=>{console.error(e);process.exitCode=1;});
