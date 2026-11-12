const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/offer-price.js'), 'utf8');

function browser(fetch) {
    const element = () => ({
        textContent: '', children: [],
        addEventListener(event, callback) { this[event] = callback; },
        replaceChildren() { this.children = []; },
        append(...children) { this.children.push(...children); }
    });
    const elements = Object.fromEntries(['offer-price-check', 'unitPrice', 'price-check-summary',
        'price-check-categories', 'price-excluded'].map(id => [id, element()]));
    elements['offer-price-check'].dataset = {url: '/w/token/maker/offer/price-check?run=run-id'};
    let timer;
    vm.runInNewContext(script, {
        document: {getElementById: id => elements[id], createElement: element},
        window: {location: {origin: 'https://example.test'}},
        URL, AbortController, fetch,
        setTimeout: callback => { timer = callback; }, clearTimeout: () => { timer = null; }
    });
    return {elements, input(value) { elements.unitPrice.value = value; elements.unitPrice.input(); },
        flush: () => timer()};
}

function response(fit, excludedByPrice = []) {
    return {ok: true, json: async () => ({fit, compatible: fit, depends: 0, excludedByPrice})};
}

test('requests exact input from the read-only endpoint and renders server counts and escaped evidence', async () => {
    const calls = [];
    const app = browser(async (url, options) => {
        calls.push({url, options});
        return response(2, [{wishId: 'wish-id', limit: 'USD 10.00', quotes: ['<script>untrusted</script>'],
            sourceUrl: '/w/token/maker/proposal?run=run-id#wish-wish-id'}]);
    });
    app.input('9223372036854775807');
    await app.flush();
    assert.equal(calls[0].url.searchParams.get('unitPrice'), '9223372036854775807');
    assert.equal(calls[0].url.searchParams.get('run'), 'run-id');
    assert.equal(calls[0].options.method, undefined);
    assert.match(app.elements['price-check-summary'].textContent, /2 wishes fit this price, 1 are excluded/);
    const item = app.elements['price-excluded'].children[0];
    assert.equal(item.children[0].href, '/w/token/maker/proposal?run=run-id#wish-wish-id');
    assert.equal(item.children[2].textContent, '<script>untrusted</script>');
});

test('late responses cannot overwrite the newest price check', async () => {
    let completeFirst;
    let calls = 0;
    const app = browser(async () => ++calls === 1 ? new Promise(resolve => { completeFirst = resolve; }) : response(1));
    app.input('1000');
    const first = app.flush();
    app.input('1200');
    await app.flush();
    completeFirst(response(9));
    await first;
    assert.match(app.elements['price-check-summary'].textContent, /^1 wishes fit/);
});

test('failed checks clear outdated counts and point to the server-rendered fallback', async () => {
    const app = browser(async () => ({ok: false}));
    app.elements['price-excluded'].append('Outdated evidence');
    app.input('invalid');
    await app.flush();
    assert.equal(app.elements['price-excluded'].children.length, 0);
    assert.equal(app.elements['price-check-categories'].textContent, '');
    assert.match(app.elements['price-check-summary'].textContent, /Check this price/);
});
