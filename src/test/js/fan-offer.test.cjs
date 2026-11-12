const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pledge.js'), 'utf8');

function offer() {
    const state = {textContent: 'Open'};
    const count = {textContent: '1'};
    const progress = {dataset: {offerProgress: 'authorizedHolds'}, value: 1};
    const holdProgress = {hidden: false};
    const classes = new Set();
    const observers = new Map();
    const panel = {
        classList: {toggle(name, enabled) { if (enabled) classes.add(name); else classes.delete(name); }},
        querySelector(selector) { return selector === '[data-live-field="state"]' ? state : count; },
        querySelectorAll(selector) { return selector === '[data-offer-progress]' ? [progress] : [holdProgress]; }
    };
    vm.runInNewContext(script, {
        document: {getElementById(id) { return id === 'offer-commitment' ? panel : null; }},
        MutationObserver: class {
            constructor(callback) { this.callback = callback; }
            observe(node) { observers.set(node, this.callback); }
        }
    });
    return {state, count, progress, holdProgress, classes, observers};
}

test('updated server counts update progress without checkout or money calculations', () => {
    const page = offer();
    page.count.textContent = '3';
    page.observers.get(page.count)();
    assert.equal(page.progress.value, '3');
});

test('settled live labels neutralize holds and hide their minimum bar', () => {
    for (const label of ['Funded', 'Funded (historical settlement)', 'Not funded']) {
        const page = offer();
        page.state.textContent = label;
        page.observers.get(page.state)();
        assert.equal(page.holdProgress.hidden, true);
        assert.equal(page.classes.has('offer-settled'), true);
    }
});

test('awaiting maker confirmation keeps the hold minimum visible', () => {
    const page = offer();
    page.state.textContent = 'Awaiting maker confirmation';
    page.observers.get(page.state)();
    assert.equal(page.holdProgress.hidden, false);
    assert.equal(page.classes.has('offer-settled'), false);
});
