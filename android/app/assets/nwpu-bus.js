/* School-page session and WAF; booking writes run only after explicit native confirmation. */
(function (root) {
    'use strict';
    function count(value) {
        const s = String(value == null ? '' : value).trim();
        return /^\d+$/.test(s) && Number.isSafeInteger(Number(s)) ? Number(s) : null;
    }
    function trips(items, route) {
        return items.filter(x => x && /^([01]\d|2[0-3]):[0-5]\d$/.test(x.fcsj)).map(x => {
            const booked = count(x.yyrs), capacity = count(x.kyyrs);
            return {id: String(x.objId || ''), route: String(route.Name || ''), time: x.fcsj, booked, capacity,
                remaining: booked !== null && capacity !== null && capacity >= booked ? capacity - booked : null,
                open: x.sfkyy === true || x.sfkyy === 'true', deadline: String(x.yyjzxx || ''), note: String(x.bcms || '')};
        });
    }
    async function collect(date, account) {
        if (root.location.origin !== 'https://hq-bus.nwpu.edu.cn' || !root.location.pathname.startsWith('/h5/')) throw new Error('origin');
        // Use the currently linked account, never an old NO left after switching accounts.
        if (!account) throw new Error('identity');
        const post = async (path, fields) => {
            const response = await root.fetch(path, {method: 'POST', credentials: 'same-origin',
                headers: {'Content-Type': 'application/x-www-form-urlencoded', Accept: 'application/json'},
                body: new URLSearchParams(fields).toString() + '&'});
            if (!response.ok) throw new Error('network');
            const json = await response.json();
            if (!(json.isSuccess === true || json.isSuccess === 'true')) throw new Error('session');
            return json.data;
        };
        const data = await post('/api/GetRouteByType', {type: '通勤车', no: account});
        if (!data || !Array.isArray(data.filteredBusRoutes)) throw new Error('format');
        const result = [];
        const parts = date.split('-').map(Number), rq = parts[0] + '/' + parts[1] + '/' + parts[2];
        for (const route of data.filteredBusRoutes) {
            if (!route.Objid) throw new Error('format');
            const items = await post('/api/GetReserveInfoList', {rq, xllx: '通勤车', xlId: route.Objid, gh: account, no: account});
            if (!Array.isArray(items)) throw new Error('format');
            result.push(...trips(items, route));
        }
        return result;
    }
    function start(date, account) {
        if (root.__louBus) return;
        root.__louBus = {phase: 'loading'};
        collect(date, account).then(items => { root.__louBus = {phase: 'ready', items}; })
            .catch(() => { root.__louBus = {phase: 'error'}; });
    }
    async function ticketPost(name, fields, account) {
        if (root.location.origin !== 'https://hq-bus.nwpu.edu.cn' || !root.location.pathname.startsWith('/h5/')) throw new Error('origin');
        if (!account) throw new Error('identity');
        const response = await root.fetch('/api/' + name, {method:'POST', credentials:'same-origin',
            headers:{'Content-Type':'application/x-www-form-urlencoded', Accept:'application/json'},
            body:new URLSearchParams({...fields, no:account}).toString() + '&'});
        if (!response.ok) throw new Error('network');
        const json = await response.json();
        if (!(json.isSuccess === true || json.isSuccess === 'true')) throw new Error('rejected');
        return json.data;
    }
    function schoolDate(date) {
        if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) throw new Error('date');
        return date.split('-').map(Number).join('/');
    }
    function orderState(data) {
        if (!data || typeof data !== 'object') throw new Error('format');
        const order = String(data.yydh || ''), status = String(data.yyzt || '');
        const fee = String(data.fy == null ? '' : data.fy);
        return {order, status, fee, notice:String(data.yyxz || ''),
            paid:!!order && ['待核验','已核验'].includes(status),
            payable:!!order && status === '待支付',
            canSubmit:!order && !status && /^\d+(?:\.\d{1,2})?$/.test(fee)};
    }
    async function quote(trip, date, account) {
        if (!trip || !trip.id || !/^([01]\d|2[0-3]):[0-5]\d$/.test(trip.time)) throw new Error('trip');
        return orderState(await ticketPost('GetAppointmentOrder', {YYRGH:account, CCRQ:schoolDate(date), objid:trip.id, SJD:trip.time}, account));
    }
    async function submit(trip, date, account, expectedFee, agreed) {
        if (agreed !== true) throw new Error('agreement');
        const current = await quote(trip,date,account);
        if (!current.canSubmit) return current;
        if (current.fee !== expectedFee) throw new Error('price_changed');
        // A failed/ambiguous write is never retried. The caller must query the order.
        const key = account + ':' + date + ':' + trip.id;
        const submitted = root.__louTicketSubmitted || (root.__louTicketSubmitted = new Set());
        if (submitted.has(key)) throw new Error('already_submitted');
        submitted.add(key);
        const order = await ticketPost('ConfirmAppointment', {PBGLID:trip.id, date:schoolDate(date), account}, account);
        if (typeof order !== 'string' || !order.trim() || /error/i.test(order)) throw new Error('order');
        return {order:order.trim(), status:'正在生成支付单', paid:false, payable:false, canSubmit:false};
    }
    async function prepare(trip, date, account, order) {
        const current = await quote(trip,date,account);
        if (current.paid) return current;
        if (!current.payable || current.order !== order) return current;
        const id = await ticketPost('GetPrePayOrderNo', {orderNo:order, date:schoolDate(date), schedulingMngID:trip.id}, account);
        // '0' means free booking in the school UI. Still verify the real order status.
        if (id === '0' || id === 0) return await quote(trip,date,account);
        if (typeof id !== 'string' || !id) return {...current, preparing:true};
        return {...current, prepay:id};
    }
    async function cashier(account, order, prepay) {
        if (!order || !prepay || prepay === '0') throw new Error('payment');
        const payload = await ticketPost('PayCashier', {outTradeNo:order, prepayId:prepay, empno:account}, account);
        if (typeof payload === 'string') {
            const raw = payload.trim();
            if (!raw) throw new Error('cashier');
            if (/^(?:https?:\/\/|weixin:|alipays?:|uppay:|upwrp:|intent:)/i.test(raw)) return {url:raw};
            return {html:raw};
        }
        if (payload && typeof payload === 'object') {
            const url = typeof payload.url === 'string' ? payload.url.trim() : '';
            const html = typeof payload.html === 'string' ? payload.html.trim() : '';
            if (url) return {url};
            if (html) return {html};
        }
        throw new Error('cashier');
    }
    function shortTime(value) {
        const m = String(value == null ? '' : value).match(/([01]\d|2[0-3]):[0-5]\d/);
        return m ? m[0] : '';
    }
    function activeTicket(state) {
        if (!state || !state.order) return false;
        if (state.paid || state.payable) return true;
        return !!state.status && !/(?:取消|过期|作废|失败|退)/.test(state.status);
    }
    async function myAppointments(account) {
        if (!account) return {ok: false, items: []};
        let anyOk = false;
        const seen = new Set();
        const items = [];
        for (const st of ['待核验', '待支付', '已核验']) {
            try {
                const data = await ticketPost('GetMyAppointment', {YYRGH: account, YYZT: st}, account);
                if (!data || !Array.isArray(data.list)) continue;
                anyOk = true;
                for (const d of data.list) {
                    if (!d || typeof d !== 'object') continue;
                    const order = String(d.yydh || '').trim();
                    const qrCode = String(d.objid || '').trim();
                    const status = String(d.ZT || st).trim();
                    if ((!order && !qrCode) || /(?:取消|过期|作废|失败|退)/.test(status)) continue;
                    const dedup = order || ('obj:' + qrCode);
                    if (seen.has(dedup)) continue;
                    seen.add(dedup);
                    items.push({
                        id: '',
                        date: '',
                        time: shortTime(d.fcsj),
                        route: String(d.xl || (d.qd && d.zd ? d.qd + '到' + d.zd : '')),
                        from: String(d.qd || ''),
                        to: String(d.zd || ''),
                        place: String(d.fcdd || ''),
                        order,
                        qrCode,
                        prepay: String(d.yktyfddh || '').trim(),
                        bookedAt: String(d.yysj || '').replace('T', ' '),
                        fee: String(d.fy == null ? '' : d.fy),
                        status,
                        payRemainMin: d.syzfsj == null ? -1 : Number(d.syzfsj),
                        paid: ['待核验', '已核验'].includes(status),
                        payable: status === '待支付'
                    });
                }
            } catch (_) {
                // 单状态接口失败时继续其余状态
            }
        }
        return {ok: anyOk, items};
    }
    async function orders(trips, date, account, timeFilter) {
        if (!Array.isArray(trips) || !account) return [];
        const candidates = trips.filter(t => t && t.id && /^([01]\d|2[0-3]):[0-5]\d$/.test(t.time)
                && t.booked !== 0 && (!timeFilter || timeFilter.has(t.time)));
        if (!candidates.length) return [];
        const found = [];
        let cursor = 0;
        const workers = Math.min(4, candidates.length);
        await Promise.all(Array.from({length: workers}, async () => {
            while (cursor < candidates.length) {
                const trip = candidates[cursor++];
                try {
                    const state = await quote(trip, date, account);
                    if (activeTicket(state)) {
                        found.push({
                            id: String(trip.id),
                            date,
                            time: String(trip.time),
                            route: String(trip.route || ''),
                            order: String(state.order),
                            status: String(state.status || ''),
                            fee: String(state.fee || ''),
                            paid: !!state.paid,
                            payable: !!state.payable
                        });
                    }
                } catch (_) {
                    // 单班次查询失败时静默跳过，避免中断其余班次检查
                }
            }
        }));
        return found;
    }
    async function scanDates(dates, currentDate, currentTrips, account) {
        if (!Array.isArray(dates) || !account) return {results: [], extraTickets: []};
        const mine = await myAppointments(account);
        const byOrder = new Map();
        const placed = new Set();
        let wantedTimes = null;
        if (mine.ok) {
            for (const item of mine.items) {
                if (item.order) byOrder.set(item.order, item);
            }
            if (mine.items.length === 0) {
                return {
                    results: dates.filter(d => /^\d{4}-\d{2}-\d{2}$/.test(d)).map(d => ({date: d, ok: true, tickets: []})),
                    extraTickets: []
                };
            }
            wantedTimes = new Set(mine.items.map(x => x.time).filter(Boolean));
            if (!wantedTimes.size) wantedTimes = null;
        }
        const orderedDates = dates.filter(d => /^\d{4}-\d{2}-\d{2}$/.test(d));
        if (currentDate && orderedDates.includes(currentDate)) {
            orderedDates.sort((a, b) => (a === currentDate ? -1 : b === currentDate ? 1 : a.localeCompare(b)));
        }
        const results = [];
        for (const d of orderedDates) {
            if (mine.ok && placed.size >= mine.items.length) {
                results.push({date: d, ok: true, tickets: []});
                continue;
            }
            try {
                const dayTrips = (d === currentDate && Array.isArray(currentTrips))
                    ? currentTrips
                    : await collect(d, account);
                const rawTickets = await orders(dayTrips, d, account, wantedTimes);
                const merged = rawTickets.map(t => {
                    const m = byOrder.get(t.order);
                    if (!m) return t;
                    placed.add(m.order || ('obj:' + m.qrCode));
                    return {...m, ...t, route: t.route || m.route, fee: t.fee || m.fee, status: t.status || m.status};
                });
                results.push({date: d, ok: true, tickets: merged});
            } catch (_) {
                results.push({date: d, ok: false, tickets: []});
            }
        }
        const extraTickets = mine.ok
            ? mine.items.filter(m => !placed.has(m.order || ('obj:' + m.qrCode)) && (m.payable || m.status === '待核验'))
            : [];
        return {results, extraTickets};
    }
    const api = {count, trips, collect, start, orderState, quote, submit, prepare, cashier, myAppointments, orders, scanDates};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.NwpuBus = api;
})(typeof window === 'undefined' ? globalThis : window);
