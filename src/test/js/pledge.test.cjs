const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pledge.js'), 'utf8');

function browser({sdk = true, sdkReject = false, sdkRender = null, responses = [], csrf = true, quotePage = null} = {}) {
    const elements = Object.fromEntries([
        'pledge-checkout', 'pledge-feedback', 'pledge-status-link', 'quantity', 'paypal-button-container',
        'pledge-total', 'pledge-quantity', 'pledge-units', 'update-total'
    ].map(id => [id, {hidden: false, disabled: false, textContent: '', addEventListener(event, callback) { this[event] = callback; }}]));
    const action = {hidden: false};
    elements['pledge-checkout'].closest = () => action;
    elements.quantity.value = '2';
    elements.quantity.form = {action: 'https://example.test/w/token/offer'};
    elements['pledge-checkout'].dataset = {createUrl: '/w/token/api/pledges', quantity: '2', campaignId: 'campaign'};
    const calls = [];
    let handlers;
    let redirected;
    const context = {
        URL, URLSearchParams, DOMParser: class { parseFromString(text) { return typeof quotePage === 'function' ? quotePage(text) : quotePage; } }, FormData: class { constructor() { return new URLSearchParams('quantity=3'); } },
        document: {
            getElementById: id => elements[id],
            querySelector: selector => csrf ? {content: selector === 'meta[name="_csrf"]' ? 'synthetic-csrf' : 'X-CSRF-TOKEN'} : null
        },
        window: {
            location: {href: 'https://example.test/', assign: url => { redirected = url; }},
            paypal: sdk ? {Buttons: options => {
                handlers = options;
                return {render: () => sdkRender || (sdkReject ? Promise.reject(new Error('SDK failed')) : Promise.resolve())};
            }} : undefined
        },
        fetch: async (url, options) => {
            calls.push({url, options});
            const response = await responses.shift();
            if (response instanceof Error) throw response;
            return {ok: response.ok, json: async () => response.body, text: async () => response.text || ''};
        }
    };
    vm.runInNewContext(script, context);
    return {elements, action, calls, get handlers() { return handlers; }, get redirected() { return redirected; }};
}

const created = {
    ok: true,
    body: {pledgeId: 'pledge', orderId: 'sandbox-order', statusUrl: '/w/token/pledges/pledge', message: 'Not counted yet.'}
};

test('creates the displayed quantity and campaign without an amount, then authorizes and redirects', async () => {
    const app = browser({responses: [created, {ok: true, body: {statusUrl: created.body.statusUrl}}]});
    assert.equal(await app.handlers.createOrder(), 'sandbox-order');
    assert.deepEqual(JSON.parse(app.calls[0].options.body), {quantity: 2, campaignId: 'campaign'});
    assert.equal(app.calls[0].url, '/w/token/api/pledges');
    assert.equal(app.calls[0].options.method, 'POST');
    assert.equal(app.calls[0].options.headers['X-CSRF-TOKEN'], 'synthetic-csrf');
    await app.handlers.onApprove({orderID: 'sandbox-order'});
    assert.equal(app.calls[1].url, '/w/token/api/pledges/pledge/authorize');
    assert.equal(app.calls[1].options.headers['X-CSRF-TOKEN'], 'synthetic-csrf');
    assert.equal(app.redirected, created.body.statusUrl);
});

test('missing request verification requires a reload without creating an order', async () => {
    const app = browser({csrf: false});
    await assert.rejects(app.handlers.createOrder());
    app.handlers.onError();
    assert.equal(app.calls.length, 0);
    assert.match(app.elements['pledge-feedback'].textContent, /Reload the page/);
    assert.equal(app.elements.quantity.disabled, false);
});

test('cancel and pre-approval errors allow another attempt without authorizing', async () => {
    const app = browser({responses: [created, created]});
    await app.handlers.createOrder();
    app.handlers.onCancel();
    assert.match(app.elements['pledge-feedback'].textContent, /No pledge counted.*try again/);
    assert.equal(app.elements.quantity.disabled, false);
    await app.handlers.createOrder();
    app.handlers.onError();
    assert.match(app.elements['pledge-feedback'].textContent, /No pledge counted.*try again/);
    assert.equal(app.calls.length, 2);
});

test('server rejection wording survives the SDK error callback', async () => {
    const message = 'There is not enough capacity for this pledge.';
    const app = browser({responses: [{ok: false, body: {message}}]});
    await assert.rejects(app.handlers.createOrder());
    app.handlers.onError();
    assert.equal(app.elements['pledge-feedback'].textContent, message);
    assert.equal(app.elements.quantity.disabled, false);
});

test('post-approval rejection displays the server release outcome and status link', async () => {
    const message = 'Hold released by PayPal.';
    const app = browser({responses: [created, {ok: false, body: {message, statusUrl: created.body.statusUrl}}]});
    await app.handlers.createOrder();
    await app.handlers.onApprove({orderID: 'sandbox-order'});
    app.handlers.onError();
    assert.equal(app.elements['pledge-feedback'].textContent, message);
    assert.equal(app.elements['pledge-status-link'].href, created.body.statusUrl);
    assert.equal(app.elements['paypal-button-container'].hidden, true);
    assert.equal(app.redirected, undefined);
});

test('an uncertain approval never claims no hold or release and prevents a fresh order', async () => {
    const app = browser({responses: [created, new Error('Disconnected')]});
    await app.handlers.createOrder();
    await app.handlers.onApprove({orderID: 'sandbox-order'});
    app.handlers.onError();
    assert.match(app.elements['pledge-feedback'].textContent, /Check this pledge status before trying again/);
    assert.doesNotMatch(app.elements['pledge-feedback'].textContent, /No pledge counted|released/);
    assert.equal(app.elements['paypal-button-container'].hidden, true);
    assert.equal(app.elements['pledge-status-link'].hidden, false);
});

test('an unexpected provider order does not authorize the stored pledge', async () => {
    const app = browser({responses: [created]});
    await app.handlers.createOrder();
    await app.handlers.onApprove({orderID: 'different-order'});
    assert.equal(app.calls.length, 1);
    assert.equal(app.elements['paypal-button-container'].hidden, true);
});

test('quantity changes hide checkout until a server quote is available', async () => {
    const app = browser({responses: [new Error('offline')]});
    app.elements.quantity.value = '3';
    await app.elements.quantity.change();
    assert.equal(app.elements['paypal-button-container'].hidden, true);
    assert.equal(app.elements['update-total'].hidden, false);
    assert.match(app.elements['pledge-feedback'].textContent, /Update total/);
    await assert.rejects(app.handlers.createOrder(), /Quote is not current/);
});

test('a matching server quote updates the displayed amount and order quantity', async () => {
    const quotePage = {
        getElementById(id) {
            return id === 'pledge-checkout' ? {dataset: {campaignId: 'campaign', quantity: '3'}} : {textContent: 'USD 48.00'};
        },
        querySelector() { return null; }
    };
    const app = browser({responses: [{ok: true}, created], quotePage});
    app.elements.quantity.value = '3';
    await app.elements.quantity.change();
    assert.equal(app.elements['pledge-total'].textContent, 'USD 48.00');
    assert.equal(app.elements['update-total'].hidden, true);
    assert.equal(app.elements['paypal-button-container'].hidden, false);
    assert.equal(app.calls[0].options.headers.Accept, 'text/html');
    await app.handlers.createOrder();
    assert.equal(JSON.parse(app.calls[1].options.body).quantity, 3);
});

test('SDK load failure has a retry explanation without creating a pledge', () => {
    const app = browser({sdk: false});
    assert.match(app.elements['pledge-feedback'].textContent, /No pledge counted.*try again/);
    assert.equal(app.elements['update-total'].hidden, false);
    assert.equal(app.calls.length, 0);
});

test('carries the selected wish into the pledge request', async () => {
    const app = browser({responses: [created]});
    app.elements['pledge-checkout'].dataset.wishId = 'selected-wish';
    await app.handlers.createOrder();
    assert.equal(JSON.parse(app.calls[0].options.body).wishId, 'selected-wish');
});

function quotePage(quantity, campaign = 'campaign') {
    return {
        getElementById(id) {
            return id === 'pledge-checkout' ? (quantity == null ? null : {dataset: {campaignId: campaign, quantity}}) : {textContent: `USD ${quantity}.00`};
        },
        querySelector() { return null; }
    };
}

test('the newest quantity wins when quote responses arrive out of order', async () => {
    let resolveFirst;
    const first = new Promise(resolve => { resolveFirst = resolve; });
    const app = browser({responses: [first, {ok: true, text: '4'}], quotePage: text => quotePage(text)});
    app.elements.quantity.value = '3';
    const earlier = app.elements.quantity.change();
    app.elements.quantity.value = '4';
    await app.elements.quantity.change();
    resolveFirst({ok: true, text: '3'});
    await earlier;
    assert.equal(app.elements['pledge-checkout'].dataset.quantity, '4');
    assert.equal(app.elements['pledge-total'].textContent, 'USD 4.00');
});

test('mismatched or unavailable quotes never enable checkout', async () => {
    for (const page of [quotePage('3', 'another'), quotePage('4'), quotePage(null)]) {
        const app = browser({responses: [{ok: true}], quotePage: page});
        app.elements.quantity.value = '3';
        await app.elements.quantity.change();
        assert.equal(app.elements['paypal-button-container'].hidden, true);
        await assert.rejects(app.handlers.createOrder(), /Quote is not current/);
    }
});

test('a late quote cannot restore checkout after live invalidation', async () => {
    let resolveQuote;
    const pending = new Promise(resolve => { resolveQuote = resolve; });
    const app = browser({responses: [pending], quotePage: quotePage('3')});
    app.elements.quantity.value = '3';
    const update = app.elements.quantity.change();
    app.action.hidden = true;
    resolveQuote({ok: true});
    await update;
    assert.equal(app.elements['paypal-button-container'].hidden, true);
    assert.equal(app.elements['pledge-checkout'].dataset.quantity, '2');
    await assert.rejects(app.handlers.createOrder(), /Quote is not current/);
});

test('a quote cannot restore checkout when the PayPal SDK failed to load', async () => {
    const app = browser({sdk: false, responses: [{ok: true}], quotePage: quotePage('3')});
    app.elements.quantity.value = '3';
    await app.elements.quantity.change();
    assert.equal(app.elements['paypal-button-container'].hidden, true);
    assert.match(app.elements['pledge-feedback'].textContent, /PayPal could not load/);
    assert.equal(app.elements['update-total'].hidden, false);
});

test('manual total remains available until automatic quote support initializes', async () => {
    const app = browser();
    await Promise.resolve();
    assert.equal(app.elements['update-total'].hidden, true);
    assert.equal(browser({sdk: false}).elements['update-total'].hidden, false);
});

test('manual total returns when PayPal rendering fails', async () => {
    const app = browser({sdkReject: true});
    await Promise.resolve();
    await Promise.resolve();
    assert.equal(app.elements['update-total'].hidden, false);
});

test('a late SDK render cannot hide the manual retry after a quote failure', async () => {
    let finishRender;
    const sdkRender = new Promise(resolve => { finishRender = resolve; });
    const app = browser({sdkRender, responses: [new Error('offline')]});
    app.elements.quantity.value = '3';
    await app.elements.quantity.change();
    finishRender();
    await Promise.resolve();
    assert.equal(app.elements['update-total'].hidden, false);
});
