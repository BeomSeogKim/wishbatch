const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pledge.js'), 'utf8');

function browser({sdk = true, responses = [], csrf = true} = {}) {
    const elements = Object.fromEntries([
        'pledge-checkout', 'pledge-feedback', 'pledge-status-link', 'quantity', 'paypal-button-container'
    ].map(id => [id, {hidden: false, disabled: false, textContent: '', addEventListener(event, callback) { this[event] = callback; }}]));
    elements['pledge-checkout'].dataset = {createUrl: '/w/token/api/pledges', quantity: '2', campaignId: 'campaign'};
    const calls = [];
    let handlers;
    let redirected;
    const context = {
        document: {
            getElementById: id => elements[id],
            querySelector: selector => csrf ? {content: selector === 'meta[name="_csrf"]' ? 'synthetic-csrf' : 'X-CSRF-TOKEN'} : null
        },
        window: {
            location: {assign: url => { redirected = url; }},
            paypal: sdk ? {Buttons: options => {
                handlers = options;
                return {render: () => Promise.resolve()};
            }} : undefined
        },
        fetch: async (url, options) => {
            calls.push({url, options});
            const response = responses.shift();
            if (response instanceof Error) throw response;
            return {ok: response.ok, json: async () => response.body};
        }
    };
    vm.runInNewContext(script, context);
    return {elements, calls, get handlers() { return handlers; }, get redirected() { return redirected; }};
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

test('quantity changes require a new server quote before showing the button', () => {
    const app = browser();
    app.elements.quantity.change();
    assert.equal(app.elements['paypal-button-container'].hidden, true);
    assert.match(app.elements['pledge-feedback'].textContent, /Update total/);
    assert.equal(app.calls.length, 0);
});

test('SDK load failure has a retry explanation without creating a pledge', () => {
    const app = browser({sdk: false});
    assert.match(app.elements['pledge-feedback'].textContent, /No pledge counted.*try again/);
    assert.equal(app.calls.length, 0);
});

test('carries the selected wish into the pledge request', async () => {
    const app = browser({responses: [created]});
    app.elements['pledge-checkout'].dataset.wishId = 'selected-wish';
    await app.handlers.createOrder();
    assert.equal(JSON.parse(app.calls[0].options.body).wishId, 'selected-wish');
});
