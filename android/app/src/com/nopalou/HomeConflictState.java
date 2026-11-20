package com.nopalou;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 首页未来 7 天日程冲突状态管理器（无 Android 依赖，纯逻辑模块）：
 * 聚合课表撞课（StudyPlanner.findLessonClashes）与待办时间相近（ClashPlan.find），
 * 供首页顶部统计行、卡片第二行与详情底抽屉统一读取，避免在界面插入会引起跳动的横幅。
 */
final class HomeConflictState {
    private List<ClashPlan.Clash> activeTaskClashes = Collections.emptyList();
    private List<StudyPlanner.LessonClash> activeLessonClashes = Collections.emptyList();
    private final Map<String, List<StudyPlanner.LessonClash>> lessonClashesByCard = new HashMap<>();
    private final Map<String, ClashPlan.Clash> taskClashByCard = new HashMap<>();

    void refresh(Engine shown, Timetable timetable, boolean inDemo, long now) {
        lessonClashesByCard.clear();
        taskClashByCard.clear();
        if (shown == null) {
            activeTaskClashes = Collections.emptyList();
            activeLessonClashes = Collections.emptyList();
            return;
        }
        List<Engine.Card> all = shown.all();
        activeTaskClashes = ClashPlan.find(all, now);
        activeLessonClashes = (!inDemo && timetable != null)
                ? StudyPlanner.findLessonClashes(all, timetable.weekAheadCourses(now), now)
                : Collections.emptyList();
        for (StudyPlanner.LessonClash lc : activeLessonClashes) {
            if (lc.card == null || lc.card.id == null) continue;
            List<StudyPlanner.LessonClash> list = lessonClashesByCard.get(lc.card.id);
            if (list == null) {
                list = new ArrayList<>();
                lessonClashesByCard.put(lc.card.id, list);
            }
            list.add(lc);
        }
        for (ClashPlan.Clash g : activeTaskClashes) {
            for (Engine.Card card : g.cards) {
                if (card != null && card.id != null) taskClashByCard.put(card.id, g);
            }
        }
    }

    boolean isEmpty() {
        return activeTaskClashes.isEmpty() && activeLessonClashes.isEmpty();
    }

    /** 首页大标题下统计行末尾的轻量可点摘要（无冲突返回 null）。 */
    String summaryLabel() {
        int lessonCount = activeLessonClashes.size();
        int taskCount = activeTaskClashes.size();
        int total = lessonCount + taskCount;
        if (total == 0) return null;
        if (lessonCount > 0 && taskCount == 0) return lessonCount + " 处撞课 ›";
        if (lessonCount == 0) return taskCount + " 处撞车 ›";
        return total + " 处冲突 ›";
    }

    /** 卡片第二行末尾极简提示词：撞课 / 撞车 / null。 */
    String cardTag(Engine.Card c) {
        if (c == null || c.done || c.id == null) return null;
        if (lessonClashesByCard.containsKey(c.id)) return "撞课";
        if (taskClashByCard.containsKey(c.id)) return "撞车";
        return null;
    }

    /** 供卡片详情底抽屉展示当前卡片关联的具体撞课或相近事项。 */
    List<String> cardLines(Engine.Card c) {
        List<String> out = new ArrayList<>();
        if (c == null || c.done || c.id == null) return out;
        List<StudyPlanner.LessonClash> lessons = lessonClashesByCard.get(c.id);
        if (lessons != null) {
            for (StudyPlanner.LessonClash lc : lessons) {
                out.add("撞课 · " + lc.text());
            }
        }
        ClashPlan.Clash group = taskClashByCard.get(c.id);
        if (group != null) {
            int peers = 0;
            for (Engine.Card peer : group.cards) {
                if (peer == null || c.id.equals(peer.id)) continue;
                if (peers++ >= 3) break;
                out.add("时间相近 · 与「" + peer.displayTitle() + "」（" + Engine.humanDeadline(peer.deadline) + "）挤在一起");
            }
        }
        return out;
    }

    String detailTitle() {
        return activeLessonClashes.isEmpty() ? "这几件事挤在一起" : "日程冲突提醒";
    }

    String detailBody() {
        StringBuilder body = new StringBuilder();
        if (!activeLessonClashes.isEmpty()) {
            body.append("与已导入课表冲突：");
            int limit = Math.min(activeLessonClashes.size(), 6);
            for (int i = 0; i < limit; i++) {
                body.append("\n· ").append(activeLessonClashes.get(i).text());
            }
            if (activeLessonClashes.size() > limit) {
                body.append("\n· 另有 ").append(activeLessonClashes.size() - limit).append(" 项冲突");
            }
        }
        if (!activeTaskClashes.isEmpty()) {
            if (body.length() > 0) body.append("\n\n待办时间相近：");
            int gLimit = Math.min(activeTaskClashes.size(), 4);
            for (int g = 0; g < gLimit; g++) {
                ClashPlan.Clash clash = activeTaskClashes.get(g);
                if (g > 0) body.append("\n");
                if (activeTaskClashes.size() > 1) {
                    if (body.length() > 0) body.append("\n");
                    body.append(Engine.humanDeadline(clash.start)).append("前后：");
                }
                for (int i = 0; i < clash.cards.size(); i++) {
                    Engine.Card c = clash.cards.get(i);
                    if (body.length() > 0) body.append("\n");
                    body.append("· ").append(c.displayTitle()).append("  ")
                            .append(Engine.humanDeadline(c.deadline));
                }
            }
            if (activeTaskClashes.size() > gLimit) {
                body.append("\n· 另有 ").append(activeTaskClashes.size() - gLimit).append(" 组相近事项");
            }
        }
        return body.toString();
    }
}
