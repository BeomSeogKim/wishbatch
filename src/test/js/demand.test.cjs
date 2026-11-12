const { readFileSync } = require('node:fs');
const vm = require('node:vm');
const test = require('node:test');
const assert = require('node:assert/strict');

const template = readFileSync('src/main/resources/templates/maker/demand.html', 'utf8');
const script = template.match(/<script th:inline="javascript">([\s\S]*?)<\/script>/)[1]
    .replace(/\/\*\[\[\$\{demand.points\}\]\]\*\/ \[\]/, JSON.stringify([
        { priceMinor: 1000, compatible: 1, minimum: 2, wishIds: ['wish-a'] },
        { priceMinor: 1001, compatible: 0, minimum: 2, wishIds: [] },
    ]))
    .replace(/\/\*\[\[\$\{demand.alternatives\}\]\]\*\/ \[\]/, JSON.stringify([
        { optionName: 'Cards', compatible: 1, wishIds: ['wish-a'] },
    ]))
    .replace(/\/\*\[\[\$\{demand.currency\}\]\]\*\/ 'USD'/, "'USD'");

function render({ charts = true, workbench = true, gridActive = true, fail = false } = {}) {
    const configs = [], events = [], buttons = [
        { hidden: true, dataset: { wishIds: 'wish-a' }, addEventListener(_, fn) { this.click = fn; } },
        { hidden: true, dataset: { wishIds: '' }, addEventListener(_, fn) { this.click = fn; } },
    ];
    const nodes = {
        'demand-price-chart': { hidden: true },
        'demand-alternative-chart': { hidden: true, dataset: {} },
        'demand-exact-data': { open: true },
        'demand-alternatives': {
            open: false,
            addEventListener(type, listener) { this[type] = () => listener.call(this); },
        },
        'demand-chart-status': { textContent: 'Fallback data below' },
    };
    if (workbench) nodes['wish-workbench'] = { scrollIntoView() {} };
    const chartApi = { AgCharts: { create(config) { if (fail) throw Error('Unavailable'); configs.push(config); } } };
    const window = { dispatchEvent: event => events.push(event) };
    if (charts) window.agCharts = chartApi;
    vm.runInNewContext(script, {
        document: {
            addEventListener(_, fn) { fn(); },
            getElementById: id => nodes[id],
            querySelector: () => workbench ? { hidden: !gridActive } : null,
            querySelectorAll: () => buttons,
        },
        window,
        agCharts: chartApi,
        CustomEvent: function (type, options) { this.type = type; this.detail = options.detail; },
    });
    return { configs, events, buttons, nodes };
}

test('chart points and alternatives emit exact wish IDs including empty selection', () => {
    const page = render();
    assert.equal(page.configs.length, 1);
    assert.equal(page.nodes['demand-exact-data'].open, false);
    page.nodes['demand-alternatives'].open = true;
    page.nodes['demand-alternatives'].toggle();
    assert.equal(page.configs.length, 2);
    assert.equal(page.configs[0].series[1].yKey, 'minimum');
    page.configs[0].series[0].listeners.seriesNodeClick({ datum: page.configs[0].data[0] });
    page.configs[1].series[0].listeners.seriesNodeClick({ datum: page.configs[1].data[0] });
    page.buttons[1].click();
    assert.deepEqual(JSON.parse(JSON.stringify(page.events)), [
        { type: 'wishbatch:filter-wishes', detail: { wishIds: ['wish-a'] } },
        { type: 'wishbatch:filter-wishes', detail: { wishIds: ['wish-a'] } },
        { type: 'wishbatch:filter-wishes', detail: { wishIds: [] } },
    ]);
});

test('blocked CDN preserves fallback and workbench evidence buttons', () => {
    const page = render({ charts: false });
    assert.equal(page.configs.length, 0);
    assert.equal(page.nodes['demand-exact-data'].open, true);
    assert.equal(page.nodes['demand-price-chart'].hidden, true);
    assert.equal(page.nodes['demand-chart-status'].textContent, 'Fallback data below');
    assert.equal(page.buttons[0].hidden, false);
    page.buttons[0].click();
    assert.equal(page.events[0].detail.wishIds[0], 'wish-a');
});

test('offer without workbench hides filter buttons and chart failure preserves data', () => {
    const page = render({ workbench: false, fail: true });
    assert.equal(page.buttons[0].hidden, true);
    assert.equal(page.nodes['demand-price-chart'].hidden, true);
    assert.equal(page.nodes['demand-alternative-chart'].hidden, true);
    assert.equal(page.nodes['demand-exact-data'].open, true);
});

test('inactive grid fallback hides actions whose event listener is unavailable', () => {
    const page = render({ gridActive: false });
    assert.equal(page.buttons[0].hidden, true);
    assert.equal(page.nodes['demand-chart-status'].textContent, 'Exact evidence for the chart points and options is below.');
});
