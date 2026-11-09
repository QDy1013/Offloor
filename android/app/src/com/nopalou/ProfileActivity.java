package com.nopalou;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.Button;
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
        ScrollView sv = new ScrollView(this);
        // 页面底色统一走规范里的极浅冷灰，替换原来的深色底。
        sv.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(this, 18), dp(this, 28), dp(this, 18), dp(this, 28));
        sv.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("我的档案");
        // 标题用主文字色（近黑），浅色底上保持最强的一级层次。
        title.setTextColor(Ui.TEXT);
        title.setTextSize(28);
        title.setTypeface(Ui.serif());
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("只留在本机，用来判断哪条消息跟你有关。不确定的可以留空。");
        // 副标题降为次级灰，和标题拉开强弱。
        sub.setTextColor(Ui.SUB);
        sub.setTextSize(12);
        sub.setLineSpacing(dp(this, 3), 1f);
        sub.setPadding(0, dp(this, 8), 0, dp(this, 14));
        root.addView(sub);

        etName = field(root, "姓名", "如：张三", p.name, InputType.TYPE_CLASS_TEXT);
        etAlias = field(root, "别名（逗号分隔）", "如：三三，小张", join(p.aliases), InputType.TYPE_CLASS_TEXT);
        etSid = field(root, "学号", "如：2024001", p.studentId, InputType.TYPE_CLASS_TEXT);
        etGroup = field(root, "小组", "如：第5组", p.groupNo, InputType.TYPE_CLASS_TEXT);
        etClass = field(root, "班级", "如：软工2402", p.className, InputType.TYPE_CLASS_TEXT);
        etCourse = field(root, "课程（逗号分隔）", "如：高等数学，大学物理", join(p.courses), InputType.TYPE_CLASS_TEXT);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(0, dp(this, 18), 0, 0);

        Button save = primaryButton("保存");
        save.setOnClickListener(v -> commit(collect(), false));
        bar.addView(save);

        Button demo = button("使用演示身份");
        demo.setOnClickListener(v -> commit(Engine.Profile.demo(), true));
        bar.addView(demo);

        root.addView(bar);

        TextView tip = new TextView(this);
        tip.setText("演示身份：" + demoLine(Engine.Profile.demo())
                + "\n演示模式用它，不要用自己的真实信息跑演示。");
        tip.setTextColor(Ui.SUB);
        tip.setTextSize(11);
        tip.setLineSpacing(dp(this, 3), 1f);
        tip.setPadding(0, dp(this, 16), 0, 0);
        root.addView(tip);

        setContentView(sv);
        Ui.systemBars(this, sv);
    }

    /** 加一行"标签 + 输入框"，返回输入框。 */
    EditText field(LinearLayout root, String label, String hint, String value,
                   int inputType) {
        TextView t = new TextView(this);
        t.setText(label);
        // 字段标签用次级灰，弱于输入内容。
        t.setTextColor(Ui.SUB);
        t.setTextSize(12);
        t.setPadding(0, dp(this, 10), 0, dp(this, 6));
        root.addView(t);

        EditText e = new EditText(this);
        e.setHint(hint);
        // 提示文字用弱化灰，正文用主文字色，浅色底上都清晰可读。
        e.setHintTextColor(Ui.MUTE);
        e.setTextColor(Ui.TEXT);
        e.setTextSize(15);
        e.setSingleLine(true);
        e.setInputType(inputType);
        e.setPadding(dp(this, 14), dp(this, 13), dp(this, 14), dp(this, 13));
        GradientDrawable g = new GradientDrawable();
        // 输入框：和首页搜索框同一种浅灰填充，不加描边。
        g.setColor(Ui.FILL);
        g.setCornerRadius(dp(this, 14));
        e.setBackground(g);
        if (value != null && !value.trim().isEmpty()) e.setText(value);
        root.addView(e, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return e;
    }

    Button primaryButton(String text) {
        Button b = button(text);
        // 主按钮：品牌蓝底 + 纯白文字，浅色界面里唯一的高饱和块。
        b.setTextColor(Ui.ON_ACCENT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.ACCENT);
        g.setCornerRadius(dp(this, 14));
        b.setBackground(g);
        b.setTypeface(Ui.medium());
        return b;
    }

    Button button(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(15);
        btn.setAllCaps(false);
        // 次按钮：浅灰填充 + 墨色文字，与首页次级按钮一致。
        btn.setTextColor(Ui.TEXT);
        GradientDrawable g = new GradientDrawable();
        g.setColor(Ui.FILL);
        g.setCornerRadius(dp(this, 14));
        btn.setBackground(g);
        btn.setStateListAnimator(null);
        btn.setMinHeight(dp(this, 50));
        btn.setPadding(dp(this, 22), dp(this, 10), dp(this, 22), dp(this, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(this, 10);
        btn.setLayoutParams(lp);
        return btn;
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
