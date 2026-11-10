package com.nopalou;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** 独立场景回放协议；同时兼容基线版本，每个 CASE 使用全新引擎。 */
public final class ReliabilityReplay {
    static String decode(String s){return new String(Base64.getDecoder().decode(s),StandardCharsets.UTF_8);}
    static String encode(String s){return Base64.getEncoder().encodeToString((s==null?"":s).getBytes(StandardCharsets.UTF_8));}
    static void dump(String id,Engine e){
        if(e==null)return;
        System.out.println("CASE\t"+id);
        for(Engine.Card c:e.all()) System.out.println("CARD\t"+encode(c.title)+"\t"+c.deadline+"\t"+c.needsConfirm+"\t"+encode(c.groupId));
    }
    public static void main(String[] args)throws Exception{
        Engine engine=null;String id="";
        BufferedReader in=new BufferedReader(new InputStreamReader(System.in,StandardCharsets.US_ASCII));String line;
        while((line=in.readLine())!=null){String[] f=line.split("\t",-1);
            if("CASE".equals(f[0])){dump(id,engine);id=f[1];try(InputStream model=Files.newInputStream(Paths.get(args[0]))){engine=new Engine(model);}}
            else if("MSG".equals(f[0])){
                String group=decode(f[3]);long at=Long.parseLong(f[2]);
                engine.setGroupMode(group,"课程事项群","com.tencent.mm",Engine.MODE_FOCUS,at);
                Engine.Msg m=new Engine.Msg(f[1],group,"课程事项群",decode(f[4]),decode(f[5]),"com.tencent.mm",at);
                if("1".equals(f[6]))try{Engine.Msg.class.getField("timeUncertain").setBoolean(m,true);}catch(NoSuchFieldException legacy){}
                engine.process(m);
            }
        }
        dump(id,engine);
    }
}
