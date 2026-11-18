// 校园网与一卡通余额解析回归测试（纯 Node 环境，不联网、不读真实账号）。
// 运行：node eval/test_campus_balance_sync.cjs
const assert = require('node:assert/strict');
const path = require('node:path');

let passed = 0;
function check(name, fn) {
    fn();
    passed++;
    console.log('PASS ' + name);
}

function makeEl(tag, opts = {}) {
    const el = {
        tagName: tag.toUpperCase(),
        className: opts.className || '',
        children: [],
        attributes: Object.assign({}, opts.attrs || {}),
        innerText: opts.innerText !== undefined ? opts.innerText : undefined,
        textContent: opts.textContent !== undefined ? opts.textContent : '',
        value: opts.value || '',
        __vue__: opts.vue || null,
        get parentElement() { return el._parent || null; },
        get nextElementSibling() {
            if (!el._parent) return null;
            const i = el._parent.children.indexOf(el);
            return i >= 0 && i + 1 < el._parent.children.length ? el._parent.children[i + 1] : null;
        },
        getAttribute: k => (k in el.attributes ? el.attributes[k] : null),
        getClientRects: () => (opts.hidden ? [] : [{width: 100, height: 30}]),
        closest: sel => {
            const parts = sel.split(',').map(s => s.trim().replace(/^\./, ''));
            for (let cur = el; cur; cur = cur._parent) {
                for (const p of parts) {
                    if (cur.tagName.toLowerCase() === p || cur.className.split(/\s+/).includes(p)) return cur;
                }
            }
            return null;
        },
        matches: sel => sel.split(',').some(s => el.className.split(/\s+/).includes(s.trim().replace(/^\./, ''))),
        cloneNode: () => makeEl(tag, opts),
        querySelector: sel => el.querySelectorAll(sel)[0] || null,
        querySelectorAll: sel => {
            const parts = sel.split(',').map(s => s.trim()).filter(Boolean);
            const hits = [];
            const visit = node => {
                for (const ch of node.children) {
                    if (matchesAny(ch, parts)) hits.push(ch);
                    visit(ch);
                }
            };
            visit(el);
            return hits;
        }
    };
    if (opts.children) for (const ch of opts.children) { ch._parent = el; el.children.push(ch); }
    return el;
}

function matchesSelector(el, sel) {
    const tokens = sel.split(/\s+/).filter(Boolean);
    if (tokens.length > 1) {
        if (!matchesSimple(el, tokens[tokens.length - 1])) return false;
        let idx = tokens.length - 2;
        for (let p = el._parent; p && idx >= 0; p = p._parent) {
            if (matchesSimple(p, tokens[idx])) idx--;
        }
        return idx < 0;
    }
    return matchesSimple(el, tokens[0]);
}

function matchesSimple(el, s) {
    if (!s) return false;
    if (s.startsWith('#')) return el.attributes.id === s.slice(1);
    if (s.startsWith('.')) return el.className.split(/\s+/).includes(s.slice(1));
    const attrM = s.match(/^([a-z*]*)\[([^\]=]+)(?:="([^"]*)")?\]$/i);
    if (attrM) {
        if (attrM[1] && attrM[1] !== '*' && el.tagName.toLowerCase() !== attrM[1].toLowerCase()) return false;
        const val = el.getAttribute(attrM[2]);
        return attrM[3] !== undefined ? val === attrM[3] : val !== null;
    }
    return el.tagName.toLowerCase() === s.toLowerCase();
}

function matchesAny(el, parts) { return parts.some(p => matchesSelector(el, p)); }

function setupPage(url, bodyChildren, bodyText = '') {
    globalThis.location = {href: url, origin: new URL(url).origin};
    const body = makeEl('body', {children: bodyChildren, innerText: bodyText, textContent: bodyText});
    globalThis.document = {
        body,
        querySelector: sel => body.querySelector(sel) || (matchesAny(body, sel.split(',').map(s => s.trim())) ? body : null),
        querySelectorAll: sel => body.querySelectorAll(sel)
    };
    return globalThis.document;
}

const C = require(path.join(__dirname, '..', 'android', 'app', 'assets', 'nwpu-campus.js'));
globalThis.NwpuCampus = C;
const S = require(path.join(__dirname, '..', 'android', 'app', 'assets', 'nwpu-sso.js'));

check('portal extracts multiline and currency-before-label balances in widgets', () => {
    const w1 = makeEl('li', {
        className: 'hotdata-ul-li',
        innerText: '校园网\n账户余额\n18.40 元',
        children: [makeEl('div', {className: 'hotdata-ul-li-top', children: [makeEl('div', {className: 'left', innerText: '校园网'})]})]
    });
    const w2 = makeEl('li', {
        className: 'hotdata-ul-li',
        innerText: '一卡通\n￥42.50\n校园卡余额\n过渡余额：0.00元',
        children: [makeEl('div', {className: 'hotdata-ul-li-top', children: [makeEl('div', {className: 'left', innerText: '一卡通'})]})]
    });
    const doc = setupPage('https://ecampus.nwpu.edu.cn/main.html', [w1, w2]);
    const out = C.portal(doc, 'https://ecampus.nwpu.edu.cn/main.html');
    assert.equal(out.network.fields[0].value, '18.40 元');
    assert.equal(out.card.fields[0].value, '42.50 元');
});

check('portal preserves missing-marker guard and extracts HotData Vue netFee + schoolCard', () => {
    const wMiss = makeEl('li', {
        className: 'hotdata-ul-li',
        innerText: '网费\n账户余额：--元',
        children: [makeEl('div', {className: 'hotdata-ul-li-top', children: [makeEl('div', {className: 'left', innerText: '网费'})]})]
    });
    const docMiss = setupPage('https://ecampus.nwpu.edu.cn/main.html', [wMiss]);
    assert.deepEqual(C.portal(docMiss, 'https://ecampus.nwpu.edu.cn/main.html'), {});

    const hotWrap = makeEl('ul', {
        vue: {$options: {name: 'HotData'}, allData: {schoolCard: {balance: '42.50', cardStatus: '正常'}, netFee: {balance: 15.8}}},
        children: [makeEl('li', {className: 'hotdata-ul-li'})]
    });
    const docVue = setupPage('https://ecampus.nwpu.edu.cn/main.html', [hotWrap]);
    const out = C.portal(docVue, 'https://ecampus.nwpu.edu.cn/main.html');
    assert.equal(out.card.fields[0].value, '42.50 元');
    assert.equal(out.card.fields.length, 2);
    assert.equal(out.network.fields[0].value, '15.8 元');
});

check('extracts 4-column KV balance table on zizhu.nwpu.edu.cn', () => {
    const tr = makeEl('tr', {
        children: [
            makeEl('th', {textContent: '账户余额'}),
            makeEl('td', {textContent: '27.60 元'}),
            makeEl('th', {textContent: '基础套餐'}),
            makeEl('td', {textContent: '校园网包月'})
        ]
    });
    const table = makeEl('table', {children: [makeEl('tbody', {children: [tr]})]});
    const doc = setupPage('https://zizhu.nwpu.edu.cn/home', [table], '');
    const out = C.extract(doc, 'https://zizhu.nwpu.edu.cn/home', 'network', true);
    assert.equal(out.state, 'ok');
    assert.equal(out.fields.find(f => f.label === '账户余额').value, '27.60 元');
    assert.equal(out.fields.find(f => f.label === '基础套餐').value, '校园网包月');
});

check('extracts card balance from Synjones H5 colon-less text and Vue state without leaking secrets', () => {
    const cell = makeEl('div', {
        className: 'van-cell',
        children: [
            makeEl('div', {className: 'van-cell__title', innerText: '校园卡余额'}),
            makeEl('div', {className: 'van-cell__value', innerText: '63.20 元'})
        ]
    });
    const docDom = setupPage('https://yktapp.nwpu.edu.cn/plat', [cell], '校园卡余额\n63.20 元');
    const outDom = C.extract(docDom, 'https://yktapp.nwpu.edu.cn/plat', 'card', true);
    assert.equal(outDom.state, 'ok');
    assert.equal(outDom.fields.find(f => f.label === '校园卡余额').value, '63.20 元');

    const app = makeEl('div', {
        attrs: {id: 'app'},
        vue: {$data: {cardBalance: '88.10', idCard: 'PRIVATE', token: 'PRIVATE'}, $children: []}
    });
    const docVue = setupPage('https://yktapp.nwpu.edu.cn/plat', [app], '一卡通首页');
    const outVue = C.extract(docVue, 'https://yktapp.nwpu.edu.cn/plat', 'card', true);
    assert.equal(outVue.state, 'ok');
    assert.equal(outVue.fields[0].value, '88.10 元');
    assert.equal(JSON.stringify(outVue).includes('PRIVATE'), false);
    assert.equal(C.safeUrl('/plat?synjones-auth=PRIVATE', 'https://yktapp.nwpu.edu.cn/'), '');
});

check('nwpu-sso recognizes input buttons and onclick CAS handoffs', () => {
    const doc = setupPage('https://zizhu.nwpu.edu.cn/login', [
        makeEl('input', {attrs: {type: 'password'}}),
        makeEl('input', {value: '统一身份认证平台登录', attrs: {type: 'button'}})
    ]);
    const inspected = S.inspect(doc, 'https://zizhu.nwpu.edu.cn/login');
    assert.equal(inspected.phase, 'sso_button');
});

console.log(passed + ' balance sync checks passed');
