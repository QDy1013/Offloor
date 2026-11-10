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
        const html = await ticketPost('PayCashier', {outTradeNo:order, prepayId:prepay, empno:account}, account);
        if (typeof html !== 'string' || !html.trim()) throw new Error('cashier');
        return {html};
    }
    const api = {count, trips, collect, start, orderState, quote, submit, prepare, cashier};
    if (typeof module !== 'undefined' && module.exports) module.exports = api;
    else root.NwpuBus = api;
})(typeof window === 'undefined' ? globalThis : window);
