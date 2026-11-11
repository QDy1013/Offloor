const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = path.join(__dirname, '../android/app/src/com/nopalou');
const notice = fs.readFileSync(path.join(base, 'HomeUsageNotice.java'), 'utf8');
const main = fs.readFileSync(path.join(base, 'MainActivity.java'), 'utf8');
let checks = 0;
function check(name, run) { run(); checks++; console.log('PASS ' + name); }

// Source-level UI contracts; actual dialog rendering is verified on the connected device.
check('usage notice is a dialog, not a permanent home card', () => {
    assert.match(notice, /new AlertDialog\.Builder\(activity, Ui\.dialogTheme\(\)\)/);
    assert.match(notice, /setTitle\("使用须知"\)/);
    assert.match(main, /usageNoticeDialog\.show\(\)/);
    assert.doesNotMatch(main, /page\.addView\(usageNotice|new HomeUsageNotice\(/);
    assert.doesNotMatch(notice, /展开须知|extends LinearLayout/);
});
check('first home entry automatically schedules the dialog', () => {
    assert.match(main, /showStartupDialogs\(\);/);
    assert.match(main, /!startupNoticeHandled && HomeUsageNotice\.shouldAutoShow\(this\)/);
    assert.match(main, /uiHandler\.postDelayed\([\s\S]*?showUsageNotice\(\)/);
});
check('acknowledgement is versioned and saved only by the confirm button', () => {
    assert.match(notice, /getInt\("acknowledged_version", 0\) < VERSION/);
    assert.match(notice, /setPositiveButton\("知道了",[\s\S]*?putInt\("acknowledged_version", VERSION\)\.apply\(\)/);
    assert.equal((notice.match(/putInt\("acknowledged_version"/g) || []).length, 1);
    assert.match(notice, /setCanceledOnTouchOutside\(false\)/);
});
check('more menu can reopen the notice after acknowledgement', () => {
    assert.match(main, /menuRow\(body, holder, "使用须知",[^\n]*this::showUsageNotice\)/);
    const show = main.slice(main.indexOf('    void showUsageNotice() {'), main.indexOf('    protected void onPause()'));
    assert.doesNotMatch(show, /shouldAutoShow/);
});
check('settings and health actions dismiss the notice before opening existing flows', () => {
    assert.match(main, /HomeUsageNotice\.create\(this, this::showSetupGuide, this::showHealthCheck\)/);
    assert.match(notice, /setNegativeButton\("后台设置",[\s\S]*?d\.dismiss\(\);\s*openSetup\.run\(\)/);
    assert.match(notice, /setNeutralButton\("通知自检",[\s\S]*?d\.dismiss\(\);\s*openHealth\.run\(\)/);
});
check('active dialog is restored after activity recreation', () => {
    assert.match(main, /b\.getBoolean\("usageNoticeShowing", false\)/);
    assert.match(main, /state\.putBoolean\("usageNoticeShowing", usageNoticeDialog != null && usageNoticeDialog\.isShowing\(\)\)/);
    assert.match(main, /restoreUsageNotice \|\|/);
});
check('dialog is not duplicated and is released when the host is destroyed', () => {
    assert.match(main, /if \(usageNoticeDialog != null && usageNoticeDialog\.isShowing\(\)\) return/);
    assert.match(main, /setOnDismissListener\(dialog -> usageNoticeDialog = null\)/);
    const destroy = main.slice(main.indexOf('    protected void onDestroy()'), main.indexOf('    void ensureNotificationAccess()'));
    assert.match(destroy, /usageNoticeDialog\.setOnDismissListener\(null\)/);
    assert.match(destroy, /usageNoticeDialog\.dismiss\(\)/);
});
check('automatic notice waits for a resumed host and does not stack with setup', () => {
    assert.match(main, /if \(resumed && !isFinishing\(\) && !isDestroyed\(\) && demo == null/);
    assert.match(main, /\(setupDialog == null \|\| !setupDialog\.isShowing\(\)\)\) showUsageNotice\(\)/);
    assert.match(main, /\(usageNoticeDialog == null \|\| !usageNoticeDialog\.isShowing\(\)\)[\s\S]*?showSetupGuide\(\)/);
});
check('covers permission, autostart, battery and restart precautions', () => {
    for (const value of ['通知使用权', '自启动与后台运行', '电池与最近任务', '重启或重连后', '不能保证永不被系统清理']) assert.ok(notice.includes(value), value);
});
check('explains notification visibility and recognition limits', () => {
    for (const value of ['免打扰', '隐藏正文', '历史聊天', '图片内文字', '自动识别可能遗漏', '校方信息为准']) assert.ok(notice.includes(value), value);
});
check('display does not change permissions, notification recovery or task storage', () => {
    assert.doesNotMatch(notice, /requestRebind|requestUnbind|setComponentEnabledSetting|NotifRecovery\.|EngineHolder\.|startActivity\(/);
});
check('long notice scrolls and does not truncate text', () => {
    assert.match(notice, /new ScrollView\(activity\)/);
    assert.match(notice, /setView\(scroll(?:, 0, 0, 0, 0)?\)/);
    assert.doesNotMatch(notice, /setMaxLines|setSingleLine|setEllipsize/);
});
check('dialog follows the existing theme palette', () => {
    assert.match(notice, /Ui\.dialogTheme\(\)/);
    assert.match(notice, /Ui\.TEXT/);
    assert.match(notice, /Ui\.SUB/);
    assert.doesNotMatch(notice, /Color\.WHITE|Color\.BLACK|parseColor/);
});
check('notice uses a minimal text header and plain sections without decorative cards', () => {
    assert.match(notice, /setCustomTitle\(header\)/);
    assert.match(notice, /header, "使用须知", 20, Ui\.TEXT, true/);
    assert.match(notice, /body\.addView\(Ui\.hairline\(activity\)\)/);
    assert.doesNotMatch(notice, /Ui\.HERO_|Ui\.ON_HERO|OFFLOOR \/|header\.setBackground|summary\.setBackground|index\.setBackground|String number/);
    assert.equal((notice.match(/addDetail\(activity, body, "/g) || []).length, 6);
    for (const title of ['通知使用权', '自启动与后台运行', '电池与最近任务', '收不到消息时', '重启或重连后', '重要事项请核对']) {
        assert.ok(notice.includes('addDetail(activity, body, "' + title + '"'), title);
    }
});
check('scrolling content is bounded while native actions remain outside', () => {
    assert.match(notice, /heightPixels \* 0\.48f/);
    assert.match(notice, /Math\.min\(cap, available\)/);
    assert.match(notice, /View\.MeasureSpec\.AT_MOST/);
    assert.match(notice, /button\.setMinimumHeight\(Ui\.dp\(activity, 48\)\)/);
    assert.match(notice, /widthPixels - Ui\.dp\(activity, 32\), Ui\.dp\(activity, 420\)/);
});
check('only confirmation is styled as the primary action', () => {
    assert.match(notice, /BUTTON_POSITIVE\), true\)/);
    assert.match(notice, /BUTTON_NEGATIVE\), false\)/);
    assert.match(notice, /BUTTON_NEUTRAL\), false\)/);
    assert.match(notice, /primary \? Ui\.ON_ACCENT : Ui\.ACCENT/);
});
console.log(checks + ' home notice dialog source checks passed');
