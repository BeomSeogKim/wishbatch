const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/offer-price.js'), 'utf8');

function browser(fetch, {amountPreview = true} = {}) {
    const element = () => ({
        textContent: '', hidden: false, children: [], dataset: {},
        addEventListener(event, callback) { this[event] = callback; },
        setAttribute(name, value) { this[name] = value; },
        replaceChildren() { this.children = []; },
        append(...children) { this.children.push(...children); }
    });
    const ids = ['offer-price-check', 'unitPrice', 'price-check-summary', 'price-check-categories',
        'price-excluded', 'price-check-amount', 'price-warning', 'minBatch', 'offer-demand-pending',
        'demand-price-chart', 'demand-explorer', 'seal-state'];
    const elements = Object.fromEntries(ids.map(id => [id, element()]));
    elements['offer-price-check'].dataset.url = '/w/token/maker/offer/price-check?run=run-id';
    elements.unitPrice.form = element();
    elements['offer-demand-pending'].hidden = true;
    if (!amountPreview) delete elements['price-check-amount'];
    let timer;
    vm.runInNewContext(script, {
        document: {getElementById: id => elements[id], createElement: element},
        window: {location: {origin: 'https://example.test'}},
        URL, AbortController, fetch,
        setTimeout: callback => { timer = callback; }, clearTimeout() {}
    });
    return {elements, async input(value) {
        elements.unitPrice.value = value;
        elements.unitPrice.input();
        return timer();
    }};
}

test('decimal input is sent unchanged and only server formatted money and warnings are shown', async () => {
    let raw;
    const app = browser(async url => {
        raw = url.searchParams.get('unitPrice');
        return {ok: true, json: async () => ({formattedPrice: 'USD 6.50', belowCost: true,
            interest: 2, conditional: 1, excludedByPrice: 0, notServed: 0, excludedWishes: []})};
    });
    await app.input('6.50');
    assert.equal(raw, '6.50');
    assert.equal(app.elements['price-check-summary'].textContent, 'Interested in this offer: 2; 0 excluded by price');
    assert.equal(app.elements['price-check-amount'].textContent, 'USD 6.50 per unit');
    assert.equal(app.elements['price-warning'].hidden, false);
    assert.equal(app.elements['offer-demand-pending'].hidden, false);
    assert.equal(app.elements['demand-price-chart'].hidden, true);
});

test('minimum and term edits invalidate their previews without computing money or fetching', () => {
    const app = browser(() => { throw new Error('Minimum edit must not fetch a price'); });
    app.elements.minBatch.input();
    assert.equal(app.elements['offer-demand-pending'].hidden, false);
    assert.equal(app.elements['demand-price-chart'].hidden, true);
    app.elements.unitPrice.form.input();
    assert.match(app.elements['seal-state'].textContent, /Terms changed/);
});

test('a rejected price clears checked money and below cost status', async () => {
    const app = browser(async () => ({ok: false}));
    await app.input('6.500');
    assert.equal(app.elements['price-check-amount'].textContent, 'Price has not been checked.');
    assert.equal(app.elements['price-warning'].hidden, true);
    assert.match(app.elements['price-check-summary'].textContent, /Check this price/);
});

test('page without an amount preview retains its warning with the current price response', async () => {
    const app = browser(async () => ({ok: true, json: async () => ({interest: 1, conditional: 0,
        excludedByPrice: 0, notServed: 0, excludedWishes: []})}), {amountPreview: false});
    app.elements['price-warning'].hidden = false;
    await app.input('6.50');
    assert.equal(app.elements['price-warning'].hidden, false);
    assert.match(app.elements['price-check-summary'].textContent, /Interested in this offer: 1/);
});
