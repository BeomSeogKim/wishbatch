const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const script = fs.readFileSync('src/main/resources/static/js/campaign-charts.js', 'utf8');

function setup({ cdn = true, fail = false, missingHistory = false, points = [], current = { interest: '1', authorized: '0', paid: '3' } } = {}) {
    const calls = [];
    const containers = {
        'campaign-charts': { dataset: current },
        'current-stages-chart': { hidden: true },
        'recorded-counts-chart': { hidden: true },
        'chart-status': { textContent: 'Tables remain available.' },
    };
    if (missingHistory) {
        delete containers['recorded-counts-chart'];
        delete containers['chart-status'];
    }
    const rows = points.map((point, index) => {
        const row = { id: `checkpoint-${index + 1}`, dataset: point,
            focus() { this.focused = true; },
            querySelectorAll() { return [{ textContent: point.source || 'PLEDGE_STATE' }]; },
        };
        containers[row.id] = row;
        return row;
    });
    for (const element of Object.values(containers)) {
        Object.defineProperty(element, 'innerHTML', { set() { throw new Error('HTML insertion is forbidden'); } });
    }
    const api = { create(options) { calls.push(options); if (fail) throw new Error('Chart failed'); } };
    vm.runInNewContext(script, {
        document: { getElementById: id => containers[id], querySelectorAll: () => rows },
        window: cdn ? { agCharts: { AgCharts: api } } : {},
    });
    return { calls, containers, rows };
}

test('Community bar and marker-only history use server counts with distinct units and source links', () => {
    const result = setup({ points: [
        { recordedAt: '2026-01-01T00:00:00Z', interest: '1', source: 'CAMPAIGN_STATE' },
        { recordedAt: '2026-01-01T00:00:01Z', authorized: '0', paid: '0', source: 'PLEDGE_CREATED' },
        { recordedAt: '2026-01-01T00:00:02Z', authorized: '3', paid: '0' },
        { recordedAt: '2026-01-01T00:00:03Z', authorized: '0', paid: '0' },
        { recordedAt: '2026-01-01T00:00:04Z', authorized: '0', paid: '3' },
    ] });
    assert.equal(result.calls.length, 2);
    const [bar, history] = result.calls;
    assert.equal(bar.title.text, 'Current stages');
    assert.equal(bar.series[0].type, 'bar');
    assert.deepEqual(Array.from(bar.data, row => row.count), [1, 0, 3]);
    assert.equal(bar.data[0].stage, 'Interested in this offer (requests)');
    assert.equal(history.series[0].yName, 'Interested in this offer (requests)');
    assert.match(bar.data[1].stage, /units/);
    assert.deepEqual(Array.from(history.data, row => row.authorized), [null, 0, 3, 0, 0]);
    assert.deepEqual(Array.from(history.data, row => row.paid), [null, 0, 0, 0, 3]);
    assert.deepEqual(Array.from(history.data, row => row.interest), [1, null, null, null, null]);
    for (const series of history.series) {
        assert.equal(series.type, 'line');
        assert.equal(series.strokeWidth, 0);
        assert.equal(series.connectMissingData, false);
        series.listeners.seriesNodeClick({ datum: history.data[2] });
    }
    assert.equal(result.rows[2].focused, true);
    assert.match(history.data[2].checkpoint, /2026-01-01T00:00:02Z.*PLEDGE_STATE/);
});

test('gaps remain null and historical interest is never copied from current interest', () => {
    const result = setup({ points: [
        { recordedAt: '2026-01-01T00:00:00Z', authorized: '3', paid: '0' },
        { recordedAt: '2026-01-01T00:00:01Z' },
    ] });
    assert.equal(result.calls[1].data[1].authorized, null);
    assert.equal(result.calls[1].data[1].paid, null);
    assert.equal(result.calls[1].data[0].interest, null);
    assert.equal(result.calls[1].data[1].interest, null);
});

test('empty or entirely unavailable history is not replaced with a zero chart', () => {
    for (const points of [[], [{ recordedAt: '2026-01-01T00:00:00Z' }]]) {
        const result = setup({ points });
        assert.equal(result.calls.length, 1);
        assert.equal(result.containers['recorded-counts-chart'].hidden, true);
    }
});

test('missing CDN and failed chart leave server tables and messages available', () => {
    const missing = setup({ cdn: false });
    assert.equal(missing.calls.length, 0);
    assert.equal(missing.containers['current-stages-chart'].hidden, true);
    const failed = setup({ fail: true });
    assert.equal(failed.containers['current-stages-chart'].hidden, true);
    assert.match(failed.containers['chart-status'].textContent, /tables/);
});

test('plain text and invalid numbers never become HTML or operational counts', () => {
    const attack = '<img src=x onerror=alert(1)>';
    const result = setup({ current: { interest: attack, authorized: '9007199254740993', paid: '-1' }, points: [
        { recordedAt: '2026-01-01T00:00:00Z', paid: '3', source: attack },
    ] });
    assert.deepEqual(Array.from(result.calls[0].data, row => row.count), [null, null, null]);
    assert.equal(result.calls[1].data[0].checkpoint, `2026-01-01T00:00:00Z · ${attack}`);
    assert.equal(result.containers['chart-status'].textContent, 'Tables remain available.');
});

test('current chart renders when the history section is absent', () => {
    const result = setup({ missingHistory: true });
    assert.equal(result.calls.length, 1);
    assert.equal(result.calls[0].title.text, 'Current stages');
    assert.equal(result.containers['current-stages-chart'].hidden, false);
});
