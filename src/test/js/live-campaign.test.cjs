const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/live-campaign.js'), 'utf8');
const origin = Date.parse('2026-01-01T12:00:00Z');

function status(changes = {}) {
    return {
        availability: 'CAMPAIGN', observedAt: new Date(origin).toISOString(),
        campaign: {
            id: 'campaign-a', state: 'OPEN',
            counts: {statedInterest: '40', authorizedHolds: '3', paid: '0'},
            progress: {units: '3', minimum: '5', basis: 'HOLDS'},
            capacityLeft: '7', closesAt: new Date(origin + 60000).toISOString(),
            confirmDeadline: new Date(origin + 3600000).toISOString(),
            pledgesOpen: true, fundedPaidShortfall: false, needsAttention: false,
            confirmationVersion: 'initial-summary', ...changes
        }
    };
}

function browser({hidden = false, readyState = 'complete', hasRoot = true} = {}) {
    let now = origin + 86400000;
    let timerId = 0;
    const timers = new Map();
    const listeners = {};
    const fields = Object.fromEntries(['statedInterest', 'authorizedHolds', 'paid', 'progressUnits',
        'progressMinimum', 'progressBasis', 'capacityLeft', 'state', 'timeLeft', 'deadlineLabel',
        'closesAt', 'confirmDeadline', 'observedAt', 'message', 'warning', 'attention', 'holdCaption'].map(field =>
        [field, {textContent: 'server-' + field}]));
    const values = {quantity: '2', total: 'USD 12.00', campaignId: 'campaign-a', pledgeCount: '2',
        units: '3', amount: '1200', acknowledged: true, termsSeal: 'sealed-terms'};
    const inputs = Object.fromEntries(Object.entries(values).map(([name, value]) => [name, {value, disabled: false}]));
    const buttons = ['pledge', 'confirmation', 'campaign'].map(() => ({disabled: false}));
    const actions = Object.fromEntries(['pledge', 'confirmation', 'campaign'].map((name, index) => [name, {
        hidden: false,
        querySelectorAll(selector) {
            assert.equal(selector, 'button');
            return [buttons[index]];
        }
    }]));
    const holdCard = {classList: {toggle(name, active) { this.active = active; }}};
    const holdProgress = {hidden: false};
    const reload = {hidden: true};
    const root = {
        dataset: {statusUrl: '/w/workspace-a/api/campaign-status', campaignId: 'campaign-a',
            initialState: 'OPEN', initialHolds: '3', initialPaid: '0', initialCapacity: '7',
            confirmationVersion: 'initial-summary'},
        querySelectorAll(selector) {
            if (selector === '[data-live-reload]') return [reload];
            if (selector === '[data-live-hold-card]') return [holdCard];
            if (selector === '[data-live-hold-progress]') return [holdProgress];
            const field = selector.match(/^\[data-live-field="([a-zA-Z]+)"\]$/);
            if (field) return [fields[field[1]]];
            const action = selector.match(/^\[data-live-action="([a-z]+)"\]$/);
            if (action) return [actions[action[1]]];
            throw new Error('Unexpected selector: ' + selector);
        }
    };
    const document = {
        readyState, hidden,
        querySelector(selector) { assert.equal(selector, '[data-live-campaign]'); return hasRoot ? root : null; },
        addEventListener(event, callback) { listeners[event] = callback; }
    };
    const requests = [];
    const fetch = (url, options) => new Promise((resolve, reject) => { requests.push({url, options, resolve, reject}); });
    const context = {
        document, window: {addEventListener(event, callback) { listeners[event] = callback; }},
        fetch, Date: class extends Date { static now() { return now; } },
        setTimeout(callback, delay) { timers.set(++timerId, {callback, due: now + delay}); return timerId; },
        clearTimeout(id) { timers.delete(id); }
    };
    vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/human-display.js'), 'utf8'), context);
    vm.runInNewContext(script, context);
    async function flush() { for (let i = 0; i < 8; i++) await Promise.resolve(); }
    async function respond(body = status(), code = 200) {
        const request = requests.find(item => !item.finished);
        assert.ok(request, 'a request is pending');
        request.finished = true;
        request.resolve({status: code, ok: code >= 200 && code < 300, json: async () => body});
        await flush();
    }
    async function advance(ms) {
        const target = now + ms;
        while (true) {
            const next = [...timers].filter(([, timer]) => timer.due <= target)
                .sort((a, b) => a[1].due - b[1].due)[0];
            if (!next) break;
            now = next[1].due;
            timers.delete(next[0]);
            next[1].callback();
            await flush();
        }
        now = target;
    }
    async function visibility(hidden) {
        document.hidden = hidden;
        listeners.visibilitychange();
        await flush();
    }
    return {fields, holdCard, holdProgress, actions, buttons, inputs, values, root, reload, requests, listeners, timers,
        respond, advance, visibility, flush};
}

test('updates separate decimal counts losslessly using text and server-relative time', async () => {
    const page = browser();
    const large = '9007199254740993';
    await page.respond(status({counts: {statedInterest: '40', authorizedHolds: large, paid: '0'},
        progress: {units: large, minimum: '5', basis: 'HOLDS'}}));
    assert.equal(page.fields.authorizedHolds.textContent, large);
    assert.equal(page.fields.progressUnits.textContent, large);
    assert.equal(page.fields.paid.textContent, '0');
    assert.equal(page.fields.capacityLeft.textContent, '7');
    assert.equal(page.fields.progressBasis.textContent, 'authorized holds');
    assert.equal(page.fields.observedAt.textContent, '1 Jan 2026, 12:00 UTC');
    assert.equal(page.fields.closesAt.textContent, '1 Jan 2026, 12:01 UTC');
    assert.equal(page.fields.confirmDeadline.textContent, '1 Jan 2026, 13:00 UTC');
    assert.equal(page.fields.timeLeft.textContent, '0h 1m 0s');
    await page.advance(1000);
    assert.equal(page.fields.timeLeft.textContent, '0h 0m 59s');
    assert.equal(page.requests[0].url, '/w/workspace-a/api/campaign-status');
    assert.equal(page.requests[0].options.cache, 'no-store');
    assert.equal(page.requests[0].options.credentials, 'same-origin');
});

test('only one request is in flight and completed requests wait ten seconds', async () => {
    const page = browser();
    await page.advance(30000);
    await page.visibility(true);
    await page.visibility(false);
    assert.equal(page.requests.length, 1);
    await page.respond();
    await page.advance(9999);
    assert.equal(page.requests.length, 1);
    await page.advance(1);
    assert.equal(page.requests.length, 2);
    await page.advance(30000);
    assert.equal(page.requests.length, 2);
});

test('failure preserves successful values and observation time with a visible stale message', async () => {
    const page = browser();
    await page.respond();
    const previous = page.fields.authorizedHolds.textContent;
    const observed = page.fields.observedAt.textContent;
    await page.advance(10000);
    page.requests[1].reject(new Error('offline'));
    await page.flush();
    assert.equal(page.fields.authorizedHolds.textContent, previous);
    assert.equal(page.fields.observedAt.textContent, observed);
    assert.match(page.fields.message.textContent, /stale/);
    assert.equal(page.reload.hidden, false);
    await page.advance(9999);
    assert.equal(page.requests.length, 2);
    await page.advance(1);
    assert.equal(page.requests.length, 3);
});

test('hidden pages pause polling and resume with one fresh request', async () => {
    const page = browser({hidden: true});
    assert.equal(page.requests.length, 0);
    await page.visibility(false);
    await page.respond();
    await page.visibility(true);
    await page.advance(30000);
    assert.equal(page.requests.length, 1);
    await page.visibility(false);
    assert.equal(page.requests.length, 2);
    await page.respond();
    await page.advance(10000);
    assert.equal(page.requests.length, 3);
});

test('identity and terminal changes hide money actions without ever re-enabling them', async () => {
    const page = browser();
    page.buttons[0].disabled = true;
    await page.respond(status({id: 'campaign-b', state: 'FUNDED', pledgesOpen: false,
        counts: {statedInterest: '40', authorizedHolds: '0', paid: '3'},
        progress: {units: '3', minimum: '5', basis: 'PAID'}, fundedPaidShortfall: true, needsAttention: true}));
    assert.ok(Object.values(page.actions).every(action => action.hidden));
    assert.ok(page.buttons.every(button => button.disabled));
    assert.equal(page.reload.hidden, false);
    assert.equal(page.fields.state.textContent, 'Funded');
    assert.equal(page.fields.timeLeft.textContent, 'No active deadline');
    assert.equal(page.holdCard.classList.active, false);
    assert.equal(page.holdProgress.hidden, true);
    assert.match(page.fields.warning.textContent, /below the minimum/);
    assert.match(page.fields.attention.textContent, /need attention/);
    assert.equal(page.fields.progressBasis.textContent, 'paid');
    await page.advance(10000);
    await page.respond();
    assert.ok(page.buttons.every(button => button.disabled));
    assert.ok(Object.values(page.actions).every(action => action.hidden));
});

test('stale confirmation summaries keep all inputs and approval terms untouched', async () => {
    const page = browser();
    await page.respond(status({confirmationVersion: 'replacement-summary'}));
    assert.equal(page.actions.confirmation.hidden, true);
    assert.equal(page.actions.pledge.hidden, false);
    for (const [name, value] of Object.entries(page.values)) {
        assert.equal(page.inputs[name].value, value);
        assert.equal(page.inputs[name].disabled, false);
    }
    assert.equal(page.reload.hidden, false);
});

test('closure invalidates checkout without altering a selected quantity or total', async () => {
    const page = browser();
    await page.respond(status({pledgesOpen: false}));
    assert.equal(page.actions.pledge.hidden, true);
    assert.equal(page.inputs.quantity.value, '2');
    assert.equal(page.inputs.total.value, 'USD 12.00');
    assert.equal(page.inputs.termsSeal.value, 'sealed-terms');
});

test('expiry clamps to zero and requests fresh state once without changing campaign state', async () => {
    const page = browser();
    await page.respond(status({closesAt: new Date(origin + 1000).toISOString()}));
    await page.advance(1000);
    assert.equal(page.requests.length, 2);
    assert.equal(page.fields.timeLeft.textContent, '0h 0m 0s');
    assert.equal(page.fields.state.textContent, 'Open');
    assert.equal(page.actions.pledge.hidden, true);
    await page.respond(status({closesAt: new Date(origin + 1000).toISOString()}));
    await page.advance(1000);
    assert.equal(page.requests.length, 2);
});

test('an expiry during an outstanding request queues only one fresh observation', async () => {
    const page = browser();
    const first = status({closesAt: new Date(origin + 11000).toISOString()});
    await page.respond(first);
    await page.advance(11000);
    assert.equal(page.requests.length, 2);
    await page.respond(first);
    await page.advance(0);
    assert.equal(page.requests.length, 3);
    await page.respond(first);
    await page.advance(1000);
    assert.equal(page.requests.length, 3);
});

test('an unchanged historical funded result does not claim a new campaign change', async () => {
    const page = browser();
    page.root.dataset.initialState = 'FUNDED';
    await page.respond(status({state: 'FUNDED', pledgesOpen: false}));
    assert.equal(page.fields.message.textContent, 'Current server values.');
    assert.equal(page.reload.hidden, true);
});

test('404 stops polling and hides invalid actions', async () => {
    const page = browser();
    await page.respond(null, 404);
    await page.advance(120000);
    await page.visibility(true);
    await page.visibility(false);
    assert.equal(page.requests.length, 1);
    assert.equal(page.actions.pledge.hidden, true);
    assert.match(page.fields.message.textContent, /unavailable/);
});

test('page exit stops the loop and ignores an outstanding response', async () => {
    const page = browser();
    page.listeners.pagehide();
    await page.respond();
    await page.advance(120000);
    assert.equal(page.requests.length, 1);
    assert.equal(page.fields.authorizedHolds.textContent, 'server-authorizedHolds');
});

test('no campaign is explicit and does not invent zero counts', async () => {
    const page = browser();
    await page.respond({availability: 'NO_CAMPAIGN', campaign: null, observedAt: new Date(origin).toISOString()});
    assert.equal(page.fields.authorizedHolds.textContent, 'server-authorizedHolds');
    assert.match(page.fields.message.textContent, /No published campaign/);
    assert.equal(page.actions.pledge.hidden, true);
});

test('invalid decimal payload retains the server fallback', async () => {
    const page = browser();
    await page.respond(status({counts: {statedInterest: '40', authorizedHolds: 9007199254740992, paid: '0'}}));
    assert.equal(page.fields.authorizedHolds.textContent, 'server-authorizedHolds');
    assert.match(page.fields.message.textContent, /stale/);
});

test('missing bindings are inert and loading documents wait for the DOM', async () => {
    const absent = browser({hasRoot: false});
    assert.equal(absent.requests.length, 0);
    const loading = browser({readyState: 'loading'});
    assert.equal(loading.requests.length, 0);
    loading.listeners.DOMContentLoaded();
    assert.equal(loading.requests.length, 1);
    await loading.respond();
});
