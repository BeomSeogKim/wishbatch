const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');

const script = fs.readFileSync(path.join(__dirname, '../../main/resources/templates/books/ledger.js'), 'utf8');

function browser({available = true, fail = false, readyState = 'complete'} = {}) {
    const element = (textContent = '', entries = []) => ({
        textContent, hidden: false, style: {}, attributes: {}, listeners: {},
        addEventListener(event, callback) { this.listeners[event] = callback; },
        setAttribute(name, value) { this.attributes[name] = value; },
        getAttribute(name) { return this.attributes[name] || null; },
        querySelector(selector) { assert.equal(selector, 'a'); return textContent === 'pledge-id' ? {cloneNode() { return {href: '/w/synthetic/pledges/pledge-id/trace'}; }} : null; },
        querySelectorAll(selector) { assert.equal(selector, 'li'); return entries.map(textContent => ({textContent})); }
    });
    const elements = Object.fromEntries(['books-fallback', 'books-grid', 'books-search', 'books-export',
        'books-fallback-toggle', 'books-controls'].map(id => [id, element()]));
    elements['books-grid'].hidden = true;
    elements['books-controls'].hidden = true;
    elements['books-fallback-toggle'].attributes['aria-expanded'] = 'false';
    elements['books-fallback-toggle'].textContent = 'Show accessible table';
    const headers = ['Pledge', 'Units', 'Amount', 'Local before', 'PayPal before', 'Match before',
        'Local after', 'PayPal after', 'Match after'];
    elements['books-table'] = {
        tHead: {rows: [{cells: headers.map(header => element(` ${header} `))}]},
        tBodies: [{rows: [{cells: [
            element('pledge-id'), element('3'), element('USD 19.50'), element('CAPTURE_PENDING'),
            element('', [' ORDER order-id: COMPLETED ', ' CAPTURE capture-id: PENDING ']), element('MATCH'),
            element('CAPTURED'), element('', ['CAPTURE capture-id: COMPLETED']), element('MATCH')
        ]}]}]
    };
    const calls = {create: 0, filter: [], export: [], theme: []};
    const api = {
        setGridOption(name, value) { calls.filter.push({name, value}); },
        exportDataAsCsv(options) { calls.export.push(options); }
    };
    const window = available ? {agGrid: {
        themeQuartz: {withParams(params) { calls.theme.push(params); return 'synthetic-theme'; }},
        createGrid(grid, options) {
            calls.create++;
            calls.grid = grid;
            calls.options = options;
            if (fail) throw new Error('synthetic grid failure');
            return api;
        }
    }} : {};
    window.WishbatchDisplay = {state: value => ({MATCH: 'Match', UNKNOWN: 'Unknown'}[value] || value), shortId: value => value.slice(0, 8) + '…'};
    const documentListeners = {};
    const document = {
        readyState,
        getElementById: id => elements[id],
        addEventListener(event, callback) { documentListeners[event] = callback; }
    };
    vm.runInNewContext(script, {document, window});
    return {elements, calls, documentListeners};
}

test('missing grid library preserves the accessible server-rendered table', () => {
    const {elements, calls} = browser({available: false});
    assert.equal(calls.create, 0);
    assert.equal(elements['books-fallback'].hidden, false);
    assert.equal(elements['books-grid'].hidden, true);
    assert.equal(elements['books-controls'].hidden, true);
});

test('grid supports sorting filters safe CSV and the accessible table toggle', () => {
    const {elements, calls} = browser();
    assert.equal(calls.create, 1);
    assert.equal(calls.grid, elements['books-grid']);
    assert.equal(elements['books-grid'].hidden, false);
    assert.equal(elements['books-fallback'].hidden, true);
    assert.equal(elements['books-controls'].hidden, false);
    const options = calls.options;
    assert.equal(options.defaultColDef.sortable, true);
    assert.equal(options.defaultColDef.filter, true);
    assert.equal(options.defaultColDef.floatingFilter, true);
    assert.equal(options.columnDefs[0].field, 'matchBefore');
    assert.equal(options.columnDefs[0].pinned, 'left');
    assert.equal(options.columnDefs[0].valueFormatter({value: 'UNKNOWN'}), 'Unknown');
    assert.equal(options.columnDefs[0].valueFormatter({value: 'MATCH'}), 'Match');
    assert.equal(options.columnDefs[2].filter, 'agNumberColumnFilter');
    assert.equal(options.columnDefs[5].headerName, 'PayPal before');
    assert.equal(options.columnDefs[5].tooltipField, 'providerBefore');
    assert.equal(options.enableBrowserTooltips, true);
    assert.equal(options.domLayout, 'autoHeight');
    assert.equal(calls.theme[0].backgroundColor, 'var(--surface)');
    assert.equal(options.columnDefs.find(column => column.field === 'pledge').cellRenderer({value: 'pledge-id', data: options.rowData[0]}).href, '/w/synthetic/pledges/pledge-id/trace');
    assert.equal(options.rowData[0].units, 3);
    assert.equal(options.rowData[0].amount, 'USD 19.50');
    assert.equal(options.rowData[0].providerBefore, 'ORDER order-id: COMPLETED; CAPTURE capture-id: PENDING');
    assert.equal(options.pagination, true);
    assert.equal(options.paginationPageSize, 20);
    assert.equal(options.loadThemeGoogleFonts, false);
    elements['books-search'].listeners.input({target: {value: 'PENDING'}});
    assert.deepEqual(calls.filter, [{name: 'quickFilterText', value: 'PENDING'}]);
    elements['books-export'].listeners.click();
    assert.equal(calls.export[0].fileName, 'payment-books.csv');
    const csv = options.defaultCsvExportParams.processCellCallback;
    assert.equal(csv({value: 'USD 19.50'}), 'USD 19.50');
    assert.equal(csv({value: null}), '');
    for (const value of ['=formula()', '+command', '-command', '@reference', '\tformula', '\rformula', '\nformula']) {
        assert.equal(csv({value}), "'" + value);
    }
    const toggle = elements['books-fallback-toggle'];
    toggle.listeners.click({target: toggle});
    assert.equal(elements['books-fallback'].hidden, false);
    assert.equal(toggle.attributes['aria-expanded'], 'true');
    assert.equal(toggle.textContent, 'Hide accessible table');
    toggle.listeners.click({target: toggle});
    assert.equal(elements['books-fallback'].hidden, true);
    assert.equal(toggle.attributes['aria-expanded'], 'false');
    assert.equal(toggle.textContent, 'Show accessible table');
});

test('grid initialization failures restore the accessible table', () => {
    const {elements, calls} = browser({fail: true});
    assert.equal(calls.create, 1);
    assert.equal(elements['books-fallback'].hidden, false);
    assert.equal(elements['books-grid'].hidden, true);
    assert.equal(elements['books-controls'].hidden, true);
});

test('loading documents wait for DOMContentLoaded before reading the ledger', () => {
    const {elements, calls, documentListeners} = browser({readyState: 'loading'});
    assert.equal(calls.create, 0);
    assert.equal(elements['books-fallback'].hidden, false);
    assert.equal(typeof documentListeners.DOMContentLoaded, 'function');
    documentListeners.DOMContentLoaded();
    assert.equal(calls.create, 1);
    assert.equal(elements['books-fallback'].hidden, true);
});
