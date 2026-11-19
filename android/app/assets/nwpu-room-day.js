/* Read-only day availability from the same endpoints used by the school room page. */
(function(root){
    'use strict';
    if(root.NwpuRoomDay)return;
    const schedules=new Map();
    let active;
    function cancel(){ if(active)active.abort(); }
    async function read(url,signal,body){
        if(signal.aborted)throw Error('cancelled');
        const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),10000);
        const abort=()=>controller.abort();
        signal.addEventListener('abort',abort,{once:true});
        try{
            const r=await fetch(url,{credentials:'same-origin',signal:controller.signal,...(body?{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)}:{})});
            if(!r.ok)throw Error('response');
            return await r.json();
        }finally{clearTimeout(timer);signal.removeEventListener('abort',abort);}
    }
    async function schedule(campus,date,signal){
        const key=campus+'|'+date;
        if(schedules.has(key))return schedules.get(key);
        const param='?campusId='+encodeURIComponent(campus);
        const [units,layout]=await Promise.all([
            read('/student/ws/room-borrow/get-unit-campus'+param,signal),
            read('/student/ws/room-borrow/get-table-layout-campus'+param,signal)
        ]);
        if(!Array.isArray(units)||!units.length||!layout||signal.aborted)throw Error('units');
        const result={units,layout};
        schedules.set(key,result);
        return result;
    }
    async function load(name,date){
        if(location.hostname!=='jwxt.nwpu.edu.cn'||!/^\/student\/for-std\/room-free/.test(location.pathname))throw Error('page');
        const today=new Date(Date.now()+28800000).toISOString().slice(0,10);
        if(date!==today)throw Error('date');
        const el=Array.from(document.querySelectorAll('#room-borrow-app,.container')).find(e=>e.__vue__&&Array.isArray(e.__vue__.freeRooms));
        const vm=el&&el.__vue__, matches=vm&&vm.freeRooms.filter(r=>r.nameZh===name);
        if(!matches||matches.length!==1)throw Error('room');
        const room=matches[0],campus=room.building.campus.id;
        cancel();
        const controller=active=new AbortController(),signal=controller.signal;
        try{
            const {units,layout}=await schedule(campus,date,signal);
            const year=String(layout.startDate||'').slice(0,4);
            const change=layout.changeMonth&&layout.changeDayOfMonth?year+'-'+String(layout.changeMonth).padStart(2,'0')+'-'+String(layout.changeDayOfMonth).padStart(2,'0'):'';
            const items=new Array(units.length);
            let next=0;
            const worker=async()=>{
                while(next<units.length && !signal.aborted){
                    const at=next++,unit=units[at];
                    const time=change&&date>=change?unit.changeTime:unit.time;
                    const item={label:unit.name,time:time||'',state:'unknown'};
                    try{
                        const result=await read('/student/ws/room-borrow/free-list',signal,{campusId:String(campus),buildingId:String(room.building.id),roomId:String(room.id),roomType:null,seatsForLessonGte:'',hasDataPermission:false,dateTimeSegmentCmd:{startDateTime:date,endDateTime:date,startTime:'',endTime:'',weekdays:[],units:[String(unit.value)]}});
                        if(Array.isArray(result.roomList))item.state=result.roomList.some(r=>String(r.id)===String(room.id))?'free':'occupied';
                    }catch(_){}
                    items[at]=item;
                }
            };
            await Promise.all(Array.from({length:Math.min(3,units.length)},worker));
            if(signal.aborted)throw Error('cancelled');
            return {date,name,items};
        }finally{
            controller.abort();
            if(active===controller)active=null;
        }
    }
    root.NwpuRoomDay={load,cancel};
})(window);
