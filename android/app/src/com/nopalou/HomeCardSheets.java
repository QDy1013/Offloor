package com.nopalou;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.CalendarContract;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Calendar;
import java.util.function.LongConsumer;

/**
 * 首页卡片相关的底部面板与表单（从 MainActivity 拆出，降低主界面耦合度）：
 * 卡片详情溯源、信息修正、手动新建/编辑任务、写入系统日历。
 */
final class HomeCardSheets {
    private HomeCardSheets() { }

    static void showSource(MainActivity a, Engine.Card c) {
        android.app.Dialog[] holder = new android.app.Dialog[1];
        long now = System.currentTimeMillis();
        LinearLayout content = new LinearLayout(a);
        content.setOrientation(LinearLayout.VERTICAL);

        LinearLayout head = new LinearLayout(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(a.sourceMark(c, 18));
        TextView from = a.uiText(a.joinDistinct(a.sourceName(c), c.groupName), 13, Ui.SUB, true);
        from.setSingleLine(true);
        from.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams fromLp = new LinearLayout.LayoutParams(0, -2, 1);
        fromLp.leftMargin = MainActivity.dp(a, 8);
        head.addView(from, fromLp);
        TextView close = a.uiText("×", 22, Ui.SUB, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭详情");
        close.setBackground(Ui.ripple(a, Ui.disc(a, Ui.FILL), Ui.press(), 18));
        close.setOnClickListener(v -> holder[0].dismiss());
        head.addView(close, a.lp(MainActivity.dp(a, 36), MainActivity.dp(a, 36)));
        LinearLayout.LayoutParams headLp = a.lp(-1, -2);
        headLp.topMargin = MainActivity.dp(a, 12);
        content.addView(head, headLp);

        TextView kindTag = a.kindTag(c.kind);
        if (kindTag != null) {
            LinearLayout.LayoutParams kLp = a.lp(-2, -2);
            kLp.topMargin = MainActivity.dp(a, 18);
            content.addView(kindTag, kLp);
        }
        TextView title = a.uiText(c.displayTitle(), 22, Ui.TEXT, false);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setLineSpacing(MainActivity.dp(a, 4), 1f);
        title.setTextIsSelectable(true);
        LinearLayout.LayoutParams titleLp = a.lp(-1, -2);
        titleLp.topMargin = MainActivity.dp(a, kindTag == null ? 18 : 10);
        content.addView(title, titleLp);

        // 截止时间块
        String deadline = c.deadlineHint() != null ? c.deadlineHint() + " · 时刻待确认"
                : c.deadline == 0 ? "时间待确认" : (c.needsConfirm ? "候选时间：" : "") + dateTimeLabel(c.deadline);
        boolean unsure = c.needsConfirm || c.deadline == 0 || c.deadlineHint() != null;
        int urgency = TodayView.urgency(c.deadline, c.needsConfirm, c.done, now);
        int tone = c.done ? Ui.SUB : unsure ? Ui.AMBER_TX : urgency >= 1 ? Ui.urgencyColor(urgency) : Ui.ACCENT;
        int toneBg = c.done ? Ui.FILL : unsure ? Ui.AMBER_BG : urgency >= 1 ? Ui.urgencyBg(urgency) : Ui.ACCENT_BG;
        LinearLayout dueBox = new LinearLayout(a);
        dueBox.setOrientation(LinearLayout.VERTICAL);
        dueBox.setPadding(MainActivity.dp(a, 16), MainActivity.dp(a, 14), MainActivity.dp(a, 16), MainActivity.dp(a, 14));
        dueBox.setBackground(Ui.round(a, toneBg, 16));
        dueBox.addView(a.uiText(c.done ? "已完成" : "exam".equals(c.kind) ? "考试时间" : "截止时间", 12, Ui.withAlpha(tone, 200), true));
        TextView dueValue = a.uiText(deadline, 18, tone, true);
        dueValue.setPadding(0, MainActivity.dp(a, 6), 0, 0);
        dueBox.addView(dueValue);
        if (!c.done && !unsure && c.deadline > 0) {
            TextView cd = a.uiText(TodayView.countdown(c.deadline, now), 13, tone, false);
            cd.setPadding(0, MainActivity.dp(a, 4), 0, 0);
            dueBox.addView(cd);
            java.util.List<String> conflicts = a.cardConflictLines(c);
            if (!conflicts.isEmpty()) {
                View sep = new View(a);
                sep.setBackgroundColor(Ui.withAlpha(Ui.ORANGE, 55));
                LinearLayout.LayoutParams sepLp = a.lp(-1, 1);
                sepLp.topMargin = MainActivity.dp(a, 10);
                sepLp.bottomMargin = MainActivity.dp(a, 8);
                dueBox.addView(sep, sepLp);
                for (int i = 0; i < conflicts.size(); i++) {
                    TextView line = a.uiText(conflicts.get(i), 12, Ui.ORANGE, false);
                    line.setLineSpacing(MainActivity.dp(a, 2), 1f);
                    if (i > 0) line.setPadding(0, MainActivity.dp(a, 4), 0, 0);
                    dueBox.addView(line);
                }
            }
        }
        if (unsure && !TextUtils.isEmpty(c.reason)) {
            TextView why = a.uiText(c.reason, 12, tone, false);
            why.setLineSpacing(MainActivity.dp(a, 2), 1f);
            why.setPadding(0, MainActivity.dp(a, 6), 0, 0);
            dueBox.addView(why);
        }
        LinearLayout.LayoutParams dueLp = a.lp(-1, -2);
        dueLp.topMargin = MainActivity.dp(a, 18);
        content.addView(dueBox, dueLp);

        Engine shown = a.demo == null ? a.engine : a.demo.engine;
        if (unsure && shown.all().contains(c)) {
            Button editNow = a.button("核对标题和时间", v -> { holder[0].dismiss(); showCardEdit(a, c); });
            LinearLayout.LayoutParams editLp = a.lp(-1, MainActivity.dp(a, 44));
            editLp.topMargin = MainActivity.dp(a, 10);
            content.addView(editNow, editLp);
            if (c.deadline > 0 && !c.changes.hasPending()) {
                Button confirmTime = a.button("确认这个候选时间", v -> {
                    if (shown.editCard(c, c.displayTitle(), c.deadline)) {
                        if (a.demo == null) EngineHolder.saveAsync(a);
                        holder[0].dismiss();
                        showSource(a, c);
                    }
                });
                content.addView(confirmTime, a.lp(-1, MainActivity.dp(a, 44)));
            }
        }
        for (Engine.Msg change : c.changes.pending()) {
            TextView changeText = a.uiText("待关联的变更 · " + (change.sender == null ? "来源未知" : change.sender)
                    + "\n" + change.text, 13, Ui.AMBER_TX, false);
            changeText.setPadding(0, MainActivity.dp(a, 16), 0, MainActivity.dp(a, 8));
            changeText.setTextIsSelectable(true);
            content.addView(changeText);
            Button applyChange = a.button("revoke".equals(Engine.detectRevision(change.text))
                    ? "按这条通知取消本事项" : "这条变更对应本事项", v -> {
                if (shown.resolvePendingChange(c, change.id)) {
                    if (a.demo == null) EngineHolder.saveAsync(a);
                    holder[0].dismiss();
                    if (shown.all().contains(c)) showSource(a, c);
                    else Toast.makeText(a, "已归入已移除卡片，可查看记录或恢复", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(a, "原文时间仍不明确，请手动核对", Toast.LENGTH_LONG).show();
                }
            });
            content.addView(applyChange, a.lp(-1, MainActivity.dp(a, 44)));
            Button ignoreChange = a.button("忽略这条变更", v -> {
                if (shown.ignorePendingChange(c, change.id)) {
                    if (a.demo == null) EngineHolder.saveAsync(a);
                    holder[0].dismiss();
                    showSource(a, c);
                }
            });
            content.addView(ignoreChange, a.lp(-1, MainActivity.dp(a, 44)));
        }

        // 关键信息
        LinearLayout info = new LinearLayout(a);
        info.setOrientation(LinearLayout.VERTICAL);
        infoRow(a, info, "提交到", c.channel);
        infoRow(a, info, "地点", c.place);
        if ("manual".equals(c.sourcePackageName())) infoRow(a, info, "备注", c.manualNotes);
        infoRow(a, info, "携带", c.bring);
        infoRow(a, info, "发送者", TextUtils.isEmpty(c.sender) ? "未知" : c.sender);
        LinearLayout.LayoutParams infoLp = a.lp(-1, -2);
        infoLp.topMargin = MainActivity.dp(a, 8);
        content.addView(info, infoLp);

        // 快捷操作
        LinearLayout actions = new LinearLayout(a);
        Intent sourceIntent = SourceLaunch.intentFor(a, c.sourcePackageName());
        if (sourceIntent != null) {
            Button open = a.button("打开" + a.sourceName(c), v -> {
                try {
                    holder[0].dismiss();
                    a.startActivity(sourceIntent);
                } catch (Exception unavailable) {
                    Toast.makeText(a, "来源应用暂时无法打开", Toast.LENGTH_SHORT).show();
                }
            });
            actions.addView(open, new LinearLayout.LayoutParams(0, MainActivity.dp(a, 46), 1));
        }
        Button copy = a.button("复制", v -> {
            String summary = c.displayTitle() + "\n截止：" + deadline + (TextUtils.isEmpty(c.channel) ? "" : "\n提交到：" + c.channel)
                    + "\n来源：" + a.sourceName(c) + " · " + c.groupName;
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) a.getSystemService(MainActivity.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("事项摘要", summary));
                Toast.makeText(a, "已复制事项摘要", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(0, MainActivity.dp(a, 46), 1);
        copyLp.leftMargin = sourceIntent == null ? 0 : MainActivity.dp(a, 8);
        actions.addView(copy, copyLp);
        Button calendar = a.button("加入日历", v -> {
            holder[0].dismiss();
            addToCalendar(a, c);
        });
        LinearLayout.LayoutParams calLp = new LinearLayout.LayoutParams(0, MainActivity.dp(a, 46), 1);
        calLp.leftMargin = MainActivity.dp(a, 8);
        actions.addView(calendar, calLp);
        for (int i = 0; i < actions.getChildCount(); i++) {
            ((Button) actions.getChildAt(i)).setTextSize(13);
            actions.getChildAt(i).setPadding(MainActivity.dp(a, 6), 0, MainActivity.dp(a, 6), 0);
        }
        LinearLayout.LayoutParams actionsLp = a.lp(-1, -2);
        actionsLp.topMargin = MainActivity.dp(a, 12);
        content.addView(actions, actionsLp);

        // 原始消息
        TextView section = a.uiText("来源与处理记录", 13, Ui.SUB, true);
        section.setPadding(MainActivity.dp(a, 2), MainActivity.dp(a, 26), 0, MainActivity.dp(a, 10));
        content.addView(section);
        StringBuilder original = new StringBuilder(TaskTrace.text(c));
        original.append("\n\n记录范围：当前来源与最近 20 条处理记录；旧版本未记录的操作不补写。");
        if (c.context != null && !c.context.isEmpty()) {
            original.append("\n\n相关上下文");
            for (String line : c.context) original.append("\n· ").append(line);
        }
        LinearLayout quote = new LinearLayout(a);
        quote.setBackground(Ui.round(a, Ui.BG, 14));
        quote.setPadding(MainActivity.dp(a, 14), MainActivity.dp(a, 14), MainActivity.dp(a, 14), MainActivity.dp(a, 14));
        View bar = new View(a);
        bar.setBackground(Ui.round(a, Ui.LINE, 2));
        quote.addView(bar, a.lp(MainActivity.dp(a, 3), -1));
        TextView source = a.uiText(original.toString(), 14, Ui.TEXT, false);
        source.setTextIsSelectable(true);
        source.setLineSpacing(MainActivity.dp(a, 5), 1.05f);
        LinearLayout.LayoutParams srcLp = new LinearLayout.LayoutParams(0, -2, 1);
        srcLp.leftMargin = MainActivity.dp(a, 12);
        quote.addView(source, srcLp);
        content.addView(quote, a.lp(-1, -2));
        if (sourceIntent != null) {
            TextView note = a.uiText("打开" + a.sourceName(c) + "后，到对应群聊查看上下文", 12, Ui.MUTE, false);
            note.setPadding(MainActivity.dp(a, 2), MainActivity.dp(a, 8), 0, 0);
            content.addView(note);
        }

        // 次要操作：文字链接
        LinearLayout links = new LinearLayout(a);
        links.setGravity(Gravity.CENTER);
        TextView edit = linkText(a, "修正信息", Ui.ACCENT);
        edit.setOnClickListener(v -> {
            holder[0].dismiss();
            showCardEdit(a, c);
        });
        links.addView(edit, new LinearLayout.LayoutParams(0, MainActivity.dp(a, 44), 1));
        TextView remove = linkText(a, "移除事项", Ui.RED);
        remove.setOnClickListener(v -> a.alert().setTitle("移除这条事项？").setMessage("可在「更多 → 已移除的卡片」中恢复。")
                .setNegativeButton("保留", null).setPositiveButton("移除", (d, w) -> {
                    if (shown.dismissCard(c, System.currentTimeMillis())) {
                        holder[0].dismiss();
                        a.render();
                        if (a.demo == null) EngineHolder.saveAsync(a);
                    }
                }).show());
        links.addView(remove, new LinearLayout.LayoutParams(0, MainActivity.dp(a, 44), 1));
        String gid = a.engine.groupKeyOf(c);
        if (a.demo == null && !TextUtils.isEmpty(gid)) {
            boolean platform = Engine.PlatformNotice.isPlatform(c.sourcePackageName());
            String gname = platform ? a.sourceName(c) : (TextUtils.isEmpty(c.groupName) ? a.sourceName(c) : c.groupName);
            String mode = a.engine.groupMode(gid);
            TextView manage = linkText(a, Engine.MODE_NORMAL.equals(mode) ? "管理这个群" : "群：" + MainActivity.modeLabel(mode), Ui.SUB);
            manage.setOnClickListener(v -> {
                holder[0].dismiss();
                a.chooseGroupMode(gid, gname, c.sourcePackageName(), mode);
            });
            links.addView(manage, new LinearLayout.LayoutParams(0, MainActivity.dp(a, 44), 1));
        }
        LinearLayout.LayoutParams linksLp = a.lp(-1, -2);
        linksLp.topMargin = MainActivity.dp(a, 14);
        content.addView(links, linksLp);

        LinearLayout footer = new LinearLayout(a);
        footer.setPadding(0, MainActivity.dp(a, 12), 0, 0);
        Button complete = a.stylePrimary(a.button(c.done ? "恢复为待办" : "标记完成", v -> {
            holder[0].dismiss();
            a.toggleDone(c);
        }));
        footer.addView(complete, new LinearLayout.LayoutParams(-1, MainActivity.dp(a, 52)));
        holder[0] = a.showSheet(content, footer);
    }

    private static void infoRow(MainActivity a, LinearLayout parent, String label, String value) {
        if (TextUtils.isEmpty(value) || value.trim().isEmpty()) return;
        if (parent.getChildCount() > 0) parent.addView(Ui.hairline(a));
        LinearLayout row = new LinearLayout(a);
        row.setPadding(MainActivity.dp(a, 2), MainActivity.dp(a, 12), MainActivity.dp(a, 2), MainActivity.dp(a, 12));
        TextView l = a.uiText(label, 14, Ui.MUTE, false);
        row.addView(l, a.lp(MainActivity.dp(a, 64), -2));
        TextView v = a.uiText(value.trim(), 14, Ui.TEXT, false);
        v.setLineSpacing(MainActivity.dp(a, 3), 1f);
        v.setTextIsSelectable(true);
        row.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
        parent.addView(row, a.lp(-1, -2));
    }

    private static TextView linkText(MainActivity a, String text, int color) {
        TextView t = a.uiText(text, 14, color, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(MainActivity.dp(a, 4), 0, MainActivity.dp(a, 4), 0);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setBackground(Ui.ripple(a, null, Ui.press(), 10));
        return t;
    }

    static void showCardEdit(MainActivity a, Engine.Card card) {
        if ("manual".equals(card.sourcePackageName())) {
            showManualCardForm(a, card, null);
            return;
        }
        LinearLayout form = dialogForm(a);
        EditText title = input(a, form, "事项标题");
        title.setText(card.displayTitle());
        Long[] deadline = {null};
        Button time = a.button("时间：" + (card.deadlineHint() == null ? dateTimeLabel(card.deadline)
                : card.deadlineHint() + "（时刻待确认）"), v -> {
            long initial = deadline[0] == null ? card.deadline : deadline[0];
            chooseDateTime(a, initial, value -> {
                deadline[0] = value;
                ((Button) v).setText("时间：" + dateTimeLabel(value));
            });
        });
        form.addView(time);
        form.addView(a.button("标为时间待确认", v -> {
            deadline[0] = 0L;
            time.setText("时间：待设置");
        }));
        AlertDialog dialog = a.alert()
                .setTitle("修正卡片")
                .setView(scrollForm(a, form))
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String label = title.getText().toString().trim();
            if (label.isEmpty()) {
                title.setError("请输入事项标题");
                return;
            }
            Engine shown = a.demo == null ? a.engine : a.demo.engine;
            if (shown.editCard(card, label, deadline[0])) {
                if (a.demo == null) EngineHolder.saveAsync(a);
                a.render();
            } else {
                Toast.makeText(a, "这张卡片已移除", Toast.LENGTH_SHORT).show();
            }
            dialog.dismiss();
        });
    }

    static void showManualCardForm(MainActivity a, Engine.Card card, Bundle draft) {
        if (a.manualCardDialog != null) return;
        Engine shown = a.demo == null ? a.engine : a.demo.engine;
        LinearLayout form = CourseUi.column(a);
        form.setFocusableInTouchMode(true);
        form.setPadding(0, MainActivity.dp(a, 4), 0, MainActivity.dp(a, 20));
        form.addView(CourseUi.text(a, card == null ? "新建任务卡片" : "编辑任务卡片", 26, Ui.TEXT, true));
        TextView description = CourseUi.text(a, a.demo == null ? "把要做的事记下来，安排时间，留好细节。"
                : "当前为离线演示，卡片仅保留在演示列表。", 13, Ui.SUB, false);
        description.setPadding(0, MainActivity.dp(a, 8), 0, 0);
        description.setLineSpacing(MainActivity.dp(a, 3), 1f);
        form.addView(description);
        CourseUi.gap(form, 24);
        form.addView(Ui.sectionLabel(a, "事项内容"));
        EditText title = Ui.field(form, "事项标题 · 必填", "例如：完成课程报告", null, InputType.TYPE_CLASS_TEXT);
        title.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        if (card != null) title.setText(card.displayTitle());
        CourseUi.gap(form, 24);
        form.addView(Ui.sectionLabel(a, "时间安排 · 可选"));
        CourseUi.gap(form, 10);
        LinearLayout timing = Ui.group(a);
        timing.setPadding(MainActivity.dp(a, 14), MainActivity.dp(a, 14), MainActivity.dp(a, 14), MainActivity.dp(a, 14));
        timing.addView(CourseUi.text(a, "截止时间", 15, Ui.TEXT, true));
        TextView timeHint = CourseUi.text(a, "设置具体时间后沿用到期提醒设置；也可以暂不设置。", 12, Ui.SUB, false);
        timeHint.setPadding(0, MainActivity.dp(a, 4), 0, MainActivity.dp(a, 12));
        timeHint.setLineSpacing(MainActivity.dp(a, 2), 1f);
        timing.addView(timeHint);
        long[] deadline = {card == null ? 0L : card.deadline};
        TextView time = CourseUi.action(a, "时间：" + dateTimeLabel(deadline[0]), false, () -> {});
        time.setTextColor(Ui.ACCENT);
        time.setBackground(Ui.ripple(a, Ui.round(a, Ui.ACCENT_BG, 16), Ui.press(), 16));
        time.setContentDescription("选择截止日期和时间");
        time.setOnClickListener(v -> chooseDateTime(a, deadline[0], value -> {
            deadline[0] = value;
            time.setText("时间：" + dateTimeLabel(value));
        }));
        timing.addView(time, new LinearLayout.LayoutParams(-1, -2));
        form.addView(timing, new LinearLayout.LayoutParams(-1, -2));
        CourseUi.gap(form, 24);
        form.addView(Ui.sectionLabel(a, "补充信息 · 可选"));
        EditText place = Ui.field(form, "地点", "例如：教学楼 A301", null, InputType.TYPE_CLASS_TEXT);
        place.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(200)});
        EditText notes = Ui.field(form, "备注", "补充准备事项、材料或其他细节", null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        notes.setSingleLine(false);
        notes.setMinLines(3);
        notes.setMaxLines(6);
        notes.setGravity(Gravity.TOP | Gravity.START);
        notes.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        if (card != null) { place.setText(card.place); notes.setText(card.manualNotes); }
        if (draft != null) {
            title.setText(draft.getString("title", ""));
            place.setText(draft.getString("place", ""));
            notes.setText(draft.getString("notes", ""));
            deadline[0] = draft.getLong("deadline", 0);
            time.setText("时间：" + dateTimeLabel(deadline[0]));
        }
        a.manualDraftState = () -> {
            Bundle saved = new Bundle();
            saved.putString("cardId", card == null ? "" : card.id);
            saved.putString("title", title.getText().toString());
            saved.putString("place", place.getText().toString());
            saved.putString("notes", notes.getText().toString());
            saved.putLong("deadline", deadline[0]);
            return saved;
        };
        LinearLayout footer = CourseUi.column(a);
        footer.addView(Ui.hairline(a));
        TextView privacy = CourseUi.text(a, a.demo == null ? "仅保存在本机 · 标题必填，其余可稍后补充"
                : "仅加入演示列表 · 不影响真实任务", 12, Ui.SUB, false);
        privacy.setPadding(0, MainActivity.dp(a, 12), 0, MainActivity.dp(a, 12));
        footer.addView(privacy);
        LinearLayout actions = new LinearLayout(a);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        TextView cancel = CourseUi.action(a, "取消", false, () -> {});
        actions.addView(cancel, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView save = CourseUi.action(a, card == null ? "加入任务" : "保存", true, () -> {});
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(0, -2, 2f);
        saveLp.leftMargin = MainActivity.dp(a, 12);
        actions.addView(save, saveLp);
        footer.addView(actions, new LinearLayout.LayoutParams(-1, -2));
        form.requestFocus();
        android.app.Dialog dialog = Ui.sheet(a, form, footer);
        a.manualCardDialog = dialog;
        dialog.setOnDismissListener(d -> { a.manualCardDialog = null; a.manualDraftState = null; });
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        cancel.setOnClickListener(v -> dialog.dismiss());
        save.setOnClickListener(v -> {
            if (title.getText().toString().trim().isEmpty()) { title.setError("请输入事项标题"); return; }
            String label = title.getText().toString(), location = place.getText().toString(), detail = notes.getText().toString();
            long now = System.currentTimeMillis();
            if (card == null) shown.addManualCard(label, deadline[0], location, detail, now);
            else if (!shown.editManualCard(card, label, deadline[0], location, detail, now)) {
                Toast.makeText(a, "这张卡片已移除", Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                return;
            }
            if (a.demo == null) EngineHolder.saveAsync(a);
            if (card == null) {
                a.keywords = "";
                a.searchField.setText("");
                a.newCardsOnly = false;
                a.scope = Engine.CardScope.ALL;
                a.window = Engine.CardWindow.ANY;
                a.visibleLimit = MainActivity.PAGE_SIZE;
            }
            dialog.dismiss();
            a.render();
            if (card == null) a.listScroll.smoothScrollTo(0, 0);
            Toast.makeText(a, card == null ? "已加入任务卡片" : "任务卡片已保存", Toast.LENGTH_SHORT).show();
        });
    }

    private static LinearLayout dialogForm(MainActivity a) {
        LinearLayout form = new LinearLayout(a);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(MainActivity.dp(a, 22), MainActivity.dp(a, 6), MainActivity.dp(a, 22), MainActivity.dp(a, 8));
        return form;
    }

    private static ScrollView scrollForm(MainActivity a, LinearLayout form) {
        ScrollView scroll = new ScrollView(a);
        scroll.addView(form);
        return scroll;
    }

    private static EditText input(MainActivity a, LinearLayout form, String hint) {
        EditText field = new EditText(a);
        field.setHint(hint);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setSingleLine(true);
        form.addView(field);
        return field;
    }

    static String dateTimeLabel(long value) {
        return value == 0 ? "待设置" : android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", value).toString();
    }

    private static void chooseDateTime(MainActivity a, long initial, LongConsumer selected) {
        Calendar value = Calendar.getInstance();
        if (initial > 0) value.setTimeInMillis(initial);
        new DatePickerDialog(a, (date, year, month, day) ->
                Ui.pickTime(a, "选择时间", value.get(Calendar.HOUR_OF_DAY), value.get(Calendar.MINUTE),
                        new int[]{9 * 60, 12 * 60, 18 * 60, 20 * 60, 22 * 60, 23 * 60 + 59},
                        (hour, minute) -> {
                            value.set(year, month, day, hour, minute, 0);
                            value.set(Calendar.MILLISECOND, 0);
                            selected.accept(value.getTimeInMillis());
                        }),
                value.get(Calendar.YEAR), value.get(Calendar.MONTH), value.get(Calendar.DAY_OF_MONTH)).show();
    }

    static void addToCalendar(MainActivity a, Engine.Card c) {
        if (c.deadlineHint() != null || c.deadline == 0) {
            Toast.makeText(a, "请先确认具体截止时间，再加入日历", Toast.LENGTH_LONG).show();
            showCardEdit(a, c);
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_INSERT);
            i.setData(CalendarContract.Events.CONTENT_URI);
            i.putExtra(CalendarContract.Events.TITLE, "【Offloor】" + c.displayTitle());
            // 描述不写群消息原文，避免系统日历云同步把原文外泄；只写来源与考试要点。
            StringBuilder desc = new StringBuilder();
            desc.append("来源：").append(a.joinDistinct(a.sourceName(c), c.groupName, c.sender));
            if ("exam".equals(c.kind)) {
                if (!TextUtils.isEmpty(c.place)) desc.append("\n地点：").append(c.place);
                if (!TextUtils.isEmpty(c.bring)) desc.append("\n带：").append(c.bring);
            }
            i.putExtra(CalendarContract.Events.DESCRIPTION, desc.toString());
            i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, c.calendarStart());
            if (!"exam".equals(c.kind)) i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, c.deadline);
            a.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(a, "这台手机没有可用的日历应用", Toast.LENGTH_LONG).show();
        }
    }
}
