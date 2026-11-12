const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const script = fs.readFileSync('src/main/resources/static/js/campaign-charts.js', 'utf8');

function setup() {
    const source = { textContent: '3' };
    const progress = { dataset: { countField: 'authorizedHolds' }, value: 0, max: 5 };
    let update;
    let observed;
    class MutationObserver {
        constructor(callback) { update = callback; }
        observe(node, options) { observed = { node, options }; }
    }
    vm.runInNewContext(script, {
        document: {
            querySelectorAll: selector => selector === 'progress[data-count-field]' ? [progress] : [],
            querySelector: selector => selector === '[data-live-field="authorizedHolds"]' ? source : null,
            getElementById: () => null,
        },
        window: {},
        MutationObserver,
    });
    return { source, progress, observed, update: () => update() };
}

test('native progress follows displayed polling counts even when the chart CDN is unavailable', () => {
    const result = setup();
    assert.equal(result.progress.value, 3);
    assert.equal(result.observed.node, result.source);
    assert.equal(result.observed.options.subtree, true);
    result.source.textContent = '7';
    result.update();
    assert.equal(result.progress.value, 7);
    assert.equal(result.progress.max, 5);
    result.source.textContent = '0';
    result.update();
    assert.equal(result.progress.value, 0);
});

test('invalid displayed counts do not overwrite the last valid progress value', () => {
    const result = setup();
    for (const value of ['Unavailable', '-1', '1.5', '9007199254740993', '<script>']) {
        result.source.textContent = value;
        result.update();
        assert.equal(result.progress.value, 3);
    }
});
