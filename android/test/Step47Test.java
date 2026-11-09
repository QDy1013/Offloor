package com.nopalou;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/** 未出卡的复判也必须服从后续取消、档案和群设置变化。 */
public final class Step47Test {
    static String root; static int checks, failures;
    static long now = 1800000000000L;
    static void check(String label, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + label); checks++; if (!ok) failures++;
    }
    static Engine fresh() throws Exception {
        try (InputStream in = Files.newInputStream(Paths.get(root, "app/assets/model.tsv"))) { return new Engine(in); }
    }
    static Engine.Escalation pending(Engine e) {
        List<Engine.Escalation> requests = new ArrayList<>(); e.escalator = requests::add;
        e.process(new Engine.Msg("source", "g", "大学物理课程群", "陈嘉禾", "周五前把实验报告交了，别拖", "com.tencent.mm", now));
        for (Engine.Escalation request : requests) if (request.cardId == null) return request;
        throw new AssertionError("铺垫必须产生未出卡消息复判");
    }
    static Engine.AiVerdict reply(Engine.Escalation request) {
        Engine.AiVerdict v = new Engine.AiVerdict();
        v.msg = request.msg; v.epoch = request.epoch; v.cardState = request.cardState;
        v.scopeVersion = request.scopeVersion; v.scopeGroup = request.msg.groupId; v.profileKey = request.profileKey;
        v.todo = true; v.title = "提交实验报告"; v.confidence = .95; v.timeSure = true; v.deadline = now + 86400000L;
        return v;
    }
    public static void main(String[] args) throws Exception {
        root = args[0];
        Engine cancelled = fresh(); Engine.AiVerdict late = reply(pending(cancelled));
        cancelled.process(new Engine.Msg("cancel", "g", "大学物理课程群", "王老师", "实验报告取消了，不用交了", "com.tencent.mm", now + 1000));
        check("取消之后旧复判不会重新出卡", !cancelled.applyAi(late, now + 2000) && cancelled.all().isEmpty());
        Engine changed = fresh(); Engine.AiVerdict ignored = reply(pending(changed));
        changed.setGroupMode("g", "大学物理课程群", "com.tencent.mm", Engine.MODE_IGNORE, now + 1);
        changed.setGroupMode("g", "大学物理课程群", "com.tencent.mm", Engine.MODE_NORMAL, now + 2);
        check("改过群策略后不恢复旧请求", !changed.applyAi(ignored, now + 3) && changed.all().isEmpty());
        Engine profile = fresh(); Engine.AiVerdict oldProfile = reply(pending(profile));
        Engine.Profile newProfile = new Engine.Profile(); newProfile.name = "另一个人"; profile.setProfile(newProfile);
        check("档案变化后旧判断不写回", !profile.applyAi(oldProfile, now) && profile.all().isEmpty());
        Engine other = fresh(); Engine.AiVerdict unrelated = reply(pending(other));
        other.process(new Engine.Msg("other-cancel", "another", "另一门课", "王老师", "实验报告取消了", "com.tencent.mm", now+1));
        check("其他群的变更不使本群复判失效", other.applyAi(unrelated, now+2) && other.all().size()==1);
        Engine reboot = fresh(); Engine.Escalation request = pending(reboot);
        check("未出卡复判也会请求持久化", reboot.hasStateChanges());
        ByteArrayOutputStream saved = new ByteArrayOutputStream(); reboot.saveState(saved);
        Engine restored = fresh(); restored.loadState(new ByteArrayInputStream(saved.toByteArray()));
        check("重启保留有效群版本", restored.currentRequest(request) && restored.applyAi(reply(request), now));
        Engine images=fresh();
        Engine.Escalation image=images.beginImageRequest(new Engine.Msg("image","g","大学物理课程群","王老师","","com.tencent.mm",now));
        check("新图片请求有效",images.currentRequest(image));
        images.process(new Engine.Msg("image-cancel","g","大学物理课程群","王老师","实验报告取消了","com.tencent.mm",now+1));
        check("后续取消使旧图片请求失效",!images.currentRequest(image));
        System.out.println("检查 " + checks + " 项，失败 " + failures + " 项");
        if (failures > 0) System.exit(1);
    }
}
