package com.nopalou;

import java.io.*;
import java.util.*;

/** 单个事项的变更历史和待关联通知。 */
public final class TaskChanges {
    public static final class Event {
        public final long at;
        public final String actor, description, text;
        Event(long at, String actor, String description, String text) {
            this.at=at; this.actor=actor; this.description=description; this.text=text;
        }
    }
    private final List<Event> history = new ArrayList<>();
    private final LinkedHashMap<String, Engine.Msg> pending = new LinkedHashMap<>();
    boolean baseNeedsConfirm;
    long revision;
    public List<Event> history() { return new ArrayList<>(history); }
    public List<Engine.Msg> pending() { return new ArrayList<>(pending.values()); }
    public boolean hasPending() { return !pending.isEmpty(); }
    Engine.Msg find(String id) { return pending.get(id); }
    void record(Engine.Msg m, String description) {
        history.add(new Event(m.ts,m.sender,description,m.text));
        while(history.size()>20) history.remove(0);
        revision++;
    }
    void action(long at, String actor, String description, String detail) {
        record(new Engine.Msg("", "", "", actor, detail, "", at), description);
    }
    void defer(Engine.Msg m, boolean needsConfirm) {
        if (pending.isEmpty()) baseNeedsConfirm=needsConfirm;
        if (!pending.containsKey(m.id)) {
            pending.put(m.id,m);
            while(pending.size()>20) pending.remove(pending.keySet().iterator().next());
            record(m,"收到变更，对应事项待核对");
        }
    }
    void remove(String id) { if(pending.remove(id)!=null) revision++; }
    void removeOlder(long at) {
        for(Engine.Msg m:pending()) if(m.ts<=at) remove(m.id);
    }
    void write(Writer out) throws IOException {
        out.write("changeRevision\t"+revision+"\nchangeBase\t"+(baseNeedsConfirm?"1":"0")+"\n");
        for(Event e:history) out.write("HISTORY\t"+e.at+"\t"+Engine.esc(e.actor)+"\t"+Engine.esc(e.description)+"\t"+Engine.esc(e.text)+"\n");
        for(Engine.Msg m:pending.values()) out.write("PENDINGCHANGE\t"+Engine.esc(m.id)+"\t"+Engine.esc(m.groupId)+"\t"+Engine.esc(m.groupName)
                +"\t"+Engine.esc(m.sender)+"\t"+Engine.esc(m.text)+"\t"+Engine.esc(m.pkg)+"\t"+m.ts+"\t"+(m.timeUncertain?"1":"0")+"\n");
    }
    void read(String key,String value) throws Exception {
        if("changeRevision".equals(key)) revision=StateFormat.number(value);
        else if("changeBase".equals(key)) baseNeedsConfirm="1".equals(value);
        else if("HISTORY".equals(key)) {
            String[] f=value.split("\t",-1);
            if(f.length!=4||history.size()>=20) throw new IOException("变更历史无效");
            history.add(new Event(StateFormat.number(f[0]),Engine.unesc(f[1]),Engine.unesc(f[2]),Engine.unesc(f[3])));
        } else if("PENDINGCHANGE".equals(key)) {
            String[] f=value.split("\t",-1);
            if((f.length!=7&&f.length!=8)||pending.size()>=20||Engine.blank(Engine.unesc(f[0]))) throw new IOException("待核对变更无效");
            Engine.Msg m=new Engine.Msg(Engine.unesc(f[0]),Engine.unesc(f[1]),Engine.unesc(f[2]),Engine.unesc(f[3]),Engine.unesc(f[4]),Engine.unesc(f[5]),StateFormat.number(f[6]));
            if(f.length==8) { if(!"0".equals(f[7])&&!"1".equals(f[7])) throw new IOException("来源时间状态无效");m.timeUncertain="1".equals(f[7]); }
            if(pending.put(m.id,m)!=null) throw new IOException("待核对变更重复");
        }
    }
}
