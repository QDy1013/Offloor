const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = path.join(__dirname, '../android/app/src/com/nopalou');
const main = fs.readFileSync(path.join(base, 'MainActivity.java'), 'utf8');
const nav = fs.readFileSync(path.join(base, 'AppNavigation.java'), 'utf8');
let checks = 0;
function check(name, run) { run(); checks++; console.log('PASS ' + name); }
check('editorial overview removes hero dark box and decorative tags', () => {
    assert.match(main, /brand\.setTypeface\(Ui\.editorialBold\(\)\)/);
    assert.match(main, /headerMeta\.addView\(sub/);
    assert.match(main, /headline\.setTypeface\(Ui\.editorialBold\(\)\)/);
    assert.match(main, /headline\.setText\(s\)/);
    assert.match(main, /stats\.setText\(TextUtils\.join/);
    assert.match(main, /if \(sub != null\) sub\.setText\(subLine\(\)\)/);
    assert.doesNotMatch(main, /Ui\.tag\(this, "今日概览"|Ui\.heroSurface\(this|studyLoopCard|updateStudyLoop/);
});
check('segmented bar keeps 4 quiet tabs, collapsible search and status filters', () => {
    assert.match(main, /segTab\("全部"\)/);
    assert.match(main, /segTab\("今天"\)/);
    assert.match(main, /segTab\("待确认"\)/);
    assert.match(main, /stateFilter\.setOnClickListener\(v -> showFilterMenu\(\)\)/);
    assert.match(main, /selected \? Ui\.roundStroke\(this, Ui\.CARD, Ui\.LINE, 9, 0\.75f\) : null/);
    assert.match(main, /searchToggle\.setOnClickListener\(v -> toggleSearch\(\)\)/);
    assert.match(main, /uiHandler\.postDelayed\(searchRefresh, 180\)/);
});
const listItem = main.slice(main.indexOf('    View cardView('), main.indexOf('    private static String kindName('));
check('cards stay hairline-flat without redundant square pills', () => {
    assert.match(listItem, /Ui\.cardSurface\(card, 14\)/);
    assert.match(listItem, /Ui\.roundStroke\(this, Ui\.FILL, Ui\.LINE, 14, 0\.75f\)/);
    assert.match(listItem, /Ui\.pressScale\(card, 0\.99f\)/);
    assert.match(listItem, /cardLp\.bottomMargin = dp\(this, 8\)/);
    assert.match(listItem, /meta\.addView\(sourceMark\(c, 14\)\)/);
    assert.match(listItem, /return card/);
    assert.doesNotMatch(listItem, /Ui\.tag\(this|kindTag\(c\.kind\)/);
});
check('completion target keeps accessible touch size and retains undo and edit actions', () => {
    assert.match(listItem, /checkLp = lp\(dp\(this, 44\), dp\(this, 44\)\)/);
    assert.match(listItem, /checkLp\.topMargin = 0/);
    assert.doesNotMatch(listItem, /checkLp\.topMargin = -/);
    assert.match(listItem, /Gravity\.TOP \| Gravity\.CENTER_HORIZONTAL/);
    assert.match(listItem, /else animateComplete\(card, circle, body, c\)/);
    assert.match(listItem, /card\.setOnClickListener\(v -> showSource\(c\)\)/);
    assert.match(listItem, /showCardEdit\(c\)/);
});
check('navigation uses flat hairline bar without floating capsule shadows', () => {
    assert.match(nav, /shell\.addView\(Ui\.hairline\(from\)/);
    assert.match(nav, /tab\.setMinimumHeight\(Ui\.dp\(from, 48\)\)/);
    assert.match(nav, /tab\.setBackground\(Ui\.ripple\(from, null, Ui\.press\(\), 12\)\)/);
    assert.match(nav, /selected \? Ui\.ACCENT : Ui\.MUTE/);
    assert.match(nav, /tab\.setContentDescription\(title/);
    assert.match(nav, /if \(target == TIMETABLE\) openTimetable\(from\)/);
    assert.match(nav, /target == ACADEMIC/);
    assert.match(nav, /target == CAMPUS/);
    assert.doesNotMatch(nav, /iconWell\.setBackground\(Ui\.round\(from, Ui\.ACCENT_BG|Ui\.lift\(row/);
});
check('more keeps full touch target and limits top-level rows', () => {
    assert.match(main, /top\.addView\(more, lp\(dp\(this, 48\), dp\(this, 48\)\)\)/);
    assert.match(main, /more\.setOnClickListener\(v -> showMoreMenu\(\)\)/);
    const moreFn = main.slice(main.indexOf('    void showMoreMenu() {'), main.indexOf('    private void showRecognitionMenu() {'));
    const rows = (moreFn.match(/menuRow\(/g) || []).length;
    assert.ok(rows <= 7 && rows >= 5, 'expected slim more menu rows, got ' + rows);
    assert.match(moreFn, /this::showToolsMenu/);
    assert.match(moreFn, /this::showUsageNotice/);
});
check('group headings keep light editorial typography and hairline divider', () => {
    const header = main.slice(main.indexOf('    View groupHeader('), main.indexOf('    View cardView('));
    assert.match(header, /uiText\(String\.valueOf\(count\), 12, Ui\.MUTE, false\)/);
    assert.match(header, /View rule = new View\(this\)/);
    assert.match(header, /lp\.topMargin = dp\(this, 20\)/);
    assert.match(header, /lp\.bottomMargin = dp\(this, 8\)/);
});
console.log(checks + ' home visual source checks passed');
