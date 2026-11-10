package com.nopalou;

import java.text.SimpleDateFormat;
import java.util.*;

/** 来源和后续处理记录的只读呈现；使用存档里的实际记录，不补写推测的历史。 */
public final class TaskTrace {
    public static final class Entry {
        public final long at;
        public final String actor, title, text;
        public final boolean original;
        Entry(long at,String actor,String title,String text,boolean original) {
            this.at=at;this.actor=actor;this.title=title;this.text=text;this.original=original;
        }
    }
    public static List<Entry> entries(Engine.Card card) {
        List<Entry> result=new ArrayList<>();
        if(card==null)return result;
        result.add(new Entry(card.sourceTs,nz(card.sender,"来源未知"),"事项来源",nz(card.sourceText,"原消息不可用"),true));
        for(TaskChanges.Event event:card.changes.history())
            result.add(new Entry(event.at,nz(event.actor,"来源未知"),event.description,nz(event.text,""),false));
        return result;
    }
    public static String text(Engine.Card card) {
        StringBuilder result=new StringBuilder();
        for(Entry entry:entries(card)) {
            if(result.length()>0)result.append("\n\n");
            result.append(time(entry.at)).append(" · ").append(entry.actor).append("\n")
                    .append(entry.title).append("\n").append(entry.text);
        }
        if(card!=null && !card.changes.hasPending())return result.toString();
        if(card!=null)for(Engine.Msg pending:card.changes.pending())
            result.append("\n\n待核对的变更\n").append(pending.text);
        return result.toString();
    }
    private static String nz(String value,String fallback) {return value==null||value.trim().isEmpty()?fallback:value;}
    public static String time(long at) {
        return at<=0?"时间未知":new SimpleDateFormat("MM-dd HH:mm",Locale.CHINA).format(new Date(at));
    }
}
