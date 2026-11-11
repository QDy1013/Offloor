const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const base = path.join(__dirname, '../android/app/src/com/nopalou');
const main = fs.readFileSync(path.join(base, 'MainActivity.java'), 'utf8');
const nav = fs.readFileSync(path.join(base, 'AppNavigation.java'), 'utf8');
let checks = 0;
function check(name, run) { run(); checks++; console.log('PASS ' + name); }
check('overview keeps dynamic date, headline and stats with distinct visual tiers', () => {
    assert.match(main, /heroMasthead\.addView\(sub/);
    assert.match(main, /Ui\.tag\(this, "今日概览"/);
    assert.match(main, /stats\.setBackground\(Ui\.round/);
    assert.match(main, /headline\.setText\(onHero\)/);
    assert.match(main, /stats\.setText\(TextUtils\.join/);
    assert.match(main, /if \(sub != null\) sub\.setText\(subLine\(\)\)/);
});
check('filters share brand color and retain counts, search and state actions', () => {
    assert.match(main, /selected \? Ui\.ON_ACCENT : Ui\.SUB/);
    assert.match(main, /selected \? Ui\.round\(this, Ui\.ACCENT, 12\)/);
    assert.match(main, /window = WINDOW_TABS\[index\]/);
    assert.match(main, /stateFilter\.setOnClickListener\(v -> showFilterMenu\(\)\)/);
    assert.match(main, /uiHandler\.postDelayed\(searchRefresh, 180\)/);
    assert.match(main, /String\.valueOf\(counts\[i\]\)/);
});
const listItem = main.slice(main.indexOf('    View cardView('), main.indexOf('    TextView kindTag('));
check('rollback restores standalone rounded cards and spacing', () => {
    assert.match(listItem, /Ui\.cardSurface\(card, 20\)/);
    assert.match(listItem, /Ui\.round\(this, Ui\.withAlpha\(Ui\.CARD/);
    assert.match(listItem, /Ui\.pressScale\(card, 0\.985f\)/);
    assert.match(listItem, /cardLp\.bottomMargin = dp\(this, 8\)/);
    assert.match(listItem, /meta\.addView\(sourceMark\(c, 15\)\)/);
    assert.match(listItem, /return card/);
    assert.doesNotMatch(listItem, /LinearLayout item|return item|View divider|card\.setBackground\(Ui\.ripple\(this, null/);
});
check('completion target stays inside card and retains complete, undo and edit actions', () => {
    assert.match(listItem, /checkLp = lp\(dp\(this, 48\), dp\(this, 48\)\)/);
    assert.match(listItem, /checkLp\.topMargin = 0/);
    assert.doesNotMatch(listItem, /checkLp\.topMargin = -/);
    assert.match(listItem, /Gravity\.TOP \| Gravity\.CENTER_HORIZONTAL/);
    assert.match(listItem, /else animateComplete\(card, circle, body, c\)/);
    assert.match(listItem, /card\.setOnClickListener\(v -> showSource\(c\)\)/);
    assert.match(listItem, /showCardEdit\(c\)/);
});
check('navigation retains compact accessible tabs and highlights just the active icon', () => {
    assert.match(nav, /tab\.setMinimumHeight\(Ui\.dp\(from,48\)\)/);
    assert.match(nav, /tab\.setBackground\(Ui\.ripple\(from, null, Ui\.press\(\), 14\)\)/);
    assert.match(nav, /if \(selected\) iconWell\.setBackground\(Ui\.round\(from, Ui\.ACCENT_BG, 11\)\)/);
    assert.match(nav, /new LinearLayout\.LayoutParams\(Ui\.dp\(from,44\),Ui\.dp\(from,24\)\)/);
    assert.match(nav, /tab\.setContentDescription\(title/);
    assert.match(nav, /if \(target == TIMETABLE\) openTimetable\(from\)/);
    assert.match(nav, /target == ACADEMIC/);
    assert.match(nav, /target == CAMPUS/);
});
check('more keeps a full touch target and themed outline', () => {
    assert.match(main, /moreLp = lp\(dp\(this, 48\), dp\(this, 48\)\)/);
    assert.match(main, /Ui\.roundStroke\(this, Ui\.CARD, Ui\.LINE, 14, 0\.75f\)/);
    assert.match(main, /more\.setOnClickListener\(v -> showMoreMenu\(\)\)/);
});
check('rollback restores original group headings and separation', () => {
    const header = main.slice(main.indexOf('    View groupHeader('), main.indexOf('    View cardView('));
    assert.match(header, /uiText\(String\.valueOf\(count\), 13, Ui\.MUTE, true\)/);
    assert.match(header, /View rule = new View\(this\)/);
    assert.match(header, /lp\.topMargin = dp\(this, 26\)/);
    assert.match(header, /lp\.bottomMargin = dp\(this, 12\)/);
});
console.log(checks + ' home visual source checks passed');
