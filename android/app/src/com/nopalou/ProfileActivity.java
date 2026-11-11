package com.nopalou;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 我的档案设置页：只填引擎真正会用的六个字段。
 * 保存后写 ProfileStore，并同步到当前引擎实例，回到主界面立刻生效。
 */
public class ProfileActivity extends Activity {

    EditText etName, etAlias, etSid, etGroup, etClass, etCourse;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.apply(this);
        Engine.Profile p = ProfileStore.load(this);
        buildUi(p);
    }

    static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }

    void buildUi(Engine.Profile p) {
        // 和其他二级页面同一套：顶栏返回 + 标题、白卡分组、统一输入框和按钮。
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Ui.BG);
        LinearLayout root = CourseUi.column(this);
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(Ui.topBar(this, "我的档案"));

        LinearLayout who = Ui.card(root);
        Ui.heading(who, "这些信息用来做什么",
                "楼外用姓名、别名和学号判断群里是不是在叫你，用班级、小组和课程判断一条通知和你有没有关系，"
                        + "比如“第5组周五展示”“软工2402 交报告”。只保存在本机，不确定的可以留空。");
        etName = field(who, "姓名", "如：张三", p.name);
        etAlias = field(who, "别名（逗号分隔）", "如：三三，小张", join(p.aliases));
        etSid = field(who, "学号", "如：2024001", p.studentId);

        LinearLayout belong = Ui.card(root);
        Ui.heading(belong, "班级与课程", null);
        etGroup = field(belong, "小组", "如：第5组", p.groupNo);
        etClass = field(belong, "班级", "如：软工2402", p.className);
        etCourse = field(belong, "课程（逗号分隔）", "如：高等数学，大学物理", join(p.courses));

        LinearLayout actions = CourseUi.column(this);
        actions.setPadding(dp(this, 16), dp(this, 16), dp(this, 16), 0);
        root.addView(actions);
        actions.addView(CourseUi.action(this, "保存", true, () -> commit(collect(), false)), CourseUi.row(dp(this, 48)));
        CourseUi.gap(actions, 10);
        actions.addView(CourseUi.action(this, "使用演示身份", false, () -> commit(Engine.Profile.demo(), true)),
                CourseUi.row(dp(this, 48)));

        TextView tip = CourseUi.text(this, "演示身份：" + demoLine(Engine.Profile.demo())
                + "\n离线演示和通知回放用它，不要用自己的真实信息跑演示。", 12, Ui.MUTE, false);
        tip.setLineSpacing(dp(this, 3), 1f);
        tip.setPadding(dp(this, 4), dp(this, 14), dp(this, 4), dp(this, 28));
        actions.addView(tip);

        setContentView(sv);
        Ui.systemBars(this, sv);
    }

    /** 加一行"标签 + 输入框"，返回输入框。 */
    EditText field(LinearLayout parent, String label, String hint, String value) {
        return Ui.field(parent, label, hint, value == null ? "" : value.trim(), InputType.TYPE_CLASS_TEXT);
    }

    /** 从输入框读档案；空白项一律置空，交给引擎的 clean 处理。 */
    Engine.Profile collect() {
        Engine.Profile p = new Engine.Profile();
        p.name = text(etName);
        p.studentId = text(etSid);
        p.groupNo = text(etGroup);
        p.className = text(etClass);
        p.aliases = split(etAlias);
        p.courses = split(etCourse);
        return p;
    }

    static String text(EditText e) {
        return e == null || e.getText() == null ? "" : e.getText().toString().trim();
    }

    /** 逗号/顿号/分号都能拆，顺手去掉空项。 */
    static List<String> split(EditText e) {
        List<String> out = new ArrayList<>();
        String s = text(e);
        if (s.isEmpty()) return out;
        for (String part : s.split("[,，、;；]")) {
            String v = part.trim();
            if (!v.isEmpty()) out.add(v);
        }
        return out;
    }

    /** 演示身份的一行摘要，内容全部取自 demo()，界面上不写死任何具体的人。 */
    static String demoLine(Engine.Profile d) {
        List<String> parts = new ArrayList<>();
        if (d != null) {
            if (d.name != null && !d.name.trim().isEmpty()) parts.add(d.name.trim());
            if (d.studentId != null && !d.studentId.trim().isEmpty()) parts.add(d.studentId.trim());
            if (d.groupNo != null && !d.groupNo.trim().isEmpty()) parts.add(d.groupNo.trim());
            if (d.className != null && !d.className.trim().isEmpty()) parts.add(d.className.trim());
        }
        return join(parts);
    }

    static String join(List<String> v) {
        if (v == null || v.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String s : v) {
            if (s == null || s.trim().isEmpty()) continue;
            if (b.length() > 0) b.append("，");
            b.append(s.trim());
        }
        return b.toString();
    }

    void commit(Engine.Profile p, boolean demo) {
        ProfileStore.save(this, p);
        Engine e = EngineHolder.get(this);
        if (e != null) e.setProfile(p);
        Toast.makeText(this, demo ? "已切到演示身份" : "档案已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
