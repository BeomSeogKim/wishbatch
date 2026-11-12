const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const script = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/wish-workbench.js'), 'utf8');

function element(properties = {}) {
    return Object.assign({
        hidden: false,
        textContent: '',
        handlers: {},
        querySelector() { return null; },
        addEventListener(name, handler) { this.handlers[name] = handler; },
    }, properties);
}

function browser({cdn = true, failCreate = false, sourceRows} = {}) {
    const data = sourceRows || [
        {id: 'one', text: 'A small print <img src=x onerror=alert(1)>', source: 'ONSITE', language: 'Unknown',
            labels: 'Print: Compatible; Card: Not served', labelsByOption: [['print', 'COMPATIBLE'], ['card', 'INCOMPATIBLE']],
            evidence: '"A small print"', flags: '', createdAt: '2026-11-11T00:00:00Z'},
        {id: 'two', text: 'A postcard if matte', source: 'IMPORTED', language: 'Unknown',
            labels: 'Print: Depends; Card: Compatible', labelsByOption: [['print', 'CONDITIONAL'], ['card', 'COMPATIBLE']],
            evidence: '"if matte"', flags: 'Second reading', createdAt: '2026-11-12T00:00:00Z'},
        {id: 'three', text: 'Lovely work', source: 'ONSITE', language: 'Unknown',
            labels: 'Not a request', labelsByOption: [['print', 'NOT_A_REQUEST'], ['card', 'NOT_A_REQUEST']],
            evidence: '', flags: 'Validation V2', createdAt: '2026-11-10T00:00:00Z'},
    ];
    const rowElements = data.map(row => element({
        dataset: {wishId: row.id, createdAt: row.createdAt},
        querySelector(selector) {
            const field = selector.match(/^\[data-field="(.+)"\]$/)?.[1];
            return field ? element({textContent: row[field] || ''}) : null;
        },
        querySelectorAll: () => row.labelsByOption.map(([optionId, label], index) => ({
            dataset: {optionId, label}, textContent: row.labels.split(';')[index] || row.labels,
        })),
    }));
    const table = element({querySelectorAll: () => rowElements});
    const container = element({hidden: true});
    const controls = Object.fromEntries(['optionId', 'label', 'sort', 'q'].map(name => [name, {value: ''}]));
    const filters = element({querySelector: selector => controls[selector.match(/^\[name="(.+)"\]$/)?.[1]]});
    const checkboxes = data.map(row => ({value: row.id, checked: false, disabled: !!row.disabled}));
    const bulk = element({querySelectorAll: () => checkboxes});
    const status = element();
    const description = element();
    const evidenceFilter = element({hidden: true, querySelector: () => description});
    const clearEvidence = element();
    const elements = {'data-workbench-table': table, 'data-workbench-grid': container,
        'data-workbench-filters': filters, 'data-workbench-bulk': bulk, 'data-workbench-status': status,
        'data-workbench-evidence-filter': evidenceFilter, 'data-workbench-clear-evidence': clearEvidence};
    const root = element({querySelector: selector => elements[selector.slice(1, -1)]});
    let options;
    let displayed = [];
    let selected = [];
    let failUpdate = false;
    const api = {
        getSelectedRows: () => selected,
        getDisplayedRowCount: () => displayed.length,
        deselectAll() { selected = []; options.onSelectionChanged({api}); },
        applyColumnState(state) { assert.deepEqual(JSON.parse(JSON.stringify(state)), {defaultState: {sort: null}}); },
        setGridOption(name, value) {
            assert.equal(name, 'rowData');
            if (failUpdate) throw new Error('Grid update failed');
            displayed = value;
        },
    };
    const window = element({agGrid: cdn ? {createGrid(target, settings) {
        assert.equal(target, container);
        if (failCreate) throw new Error('Grid startup failed');
        options = settings;
        displayed = settings.rowData;
        return api;
    }} : undefined});
    const document = {
        querySelector: () => root,
        createElement: () => {
            const result = element();
            Object.defineProperty(result, 'innerHTML', {set() { throw new Error('Unsafe HTML assignment'); }});
            return result;
        },
    };
    vm.runInNewContext(script, {document, window});
    return {table, container, filters, controls, checkboxes, status, evidenceFilter, description, clearEvidence,
        window, get options() { return options; }, get displayed() { return displayed; },
        failUpdates() { failUpdate = true; },
        select(ids) {
            selected = displayed.filter(row => ids.includes(row.id));
            options.onSelectionChanged({api});
        },
        filter(values) {
            for (const [name, value] of Object.entries(values)) controls[name].value = value;
            filters.handlers.input();
        },
        evidence(wishIds) { window.handlers['wishbatch:filter-wishes']({detail: {wishIds}}); },
    };
}

test('missing or failed CDN keeps server forms and table usable', () => {
    for (const config of [{cdn: false}, {failCreate: true}]) {
        const app = browser(config);
        assert.equal(app.table.hidden, false);
        assert.equal(app.container.hidden, true);
        assert.equal(app.filters.handlers.submit, undefined);
        assert.equal(app.checkboxes.length, 3);
    }
});

test('source text and quoted evidence retain exact whitespace in grid data', () => {
    const app = browser({sourceRows: [{id: 'spaced', text: 'A matte  print',
        evidence: ' matte  print ', labels: 'Print: Compatible', labelsByOption: [['print', 'COMPATIBLE']]}]});
    assert.equal(app.displayed[0].text, 'A matte  print');
    assert.equal(app.displayed[0].evidence, ' matte  print ');
});

test('option and label filters use the same option pair with searchable evidence and flags', () => {
    const app = browser();
    assert.equal(app.table.hidden, true);
    app.filter({optionId: 'print', label: 'COMPATIBLE'});
    assert.deepEqual(Array.from(app.displayed, row => row.id), ['one']);
    app.filter({optionId: 'card', label: 'COMPATIBLE'});
    assert.deepEqual(Array.from(app.displayed, row => row.id), ['two']);
    app.filter({optionId: '', label: '', q: 'SECOND READING'});
    assert.deepEqual(Array.from(app.displayed, row => row.id), ['two']);
    app.filter({q: '"A small print"'});
    assert.deepEqual(Array.from(app.displayed, row => row.id), ['one']);
});

test('grid selection mirrors form checkboxes and filter changes clear invisible selections', () => {
    const app = browser();
    app.select(['one', 'two']);
    assert.deepEqual(app.checkboxes.map(input => input.checked), [true, true, false]);
    assert.match(app.status.textContent, /3 wishes shown; 2 selected/);
    app.filter({q: 'postcard'});
    assert.deepEqual(app.checkboxes.map(input => input.checked), [false, false, false]);
    app.select(['two']);
    assert.deepEqual(app.checkboxes.filter(input => input.checked).map(input => input.value), ['two']);
    assert.match(app.status.textContent, /1 wishes shown; 1 selected.*not purchases/);
    assert.equal(app.options.rowSelection.mode, 'multiRow');
    assert.equal(app.options.rowSelection.selectAll, 'filtered');
});

test('unanalyzed disabled wishes cannot be selected or included in bulk submission', () => {
    const app = browser({sourceRows: [
        {id: 'analyzed', text: 'Analyzed wish', labels: 'Compatible', labelsByOption: [['option', 'COMPATIBLE']]},
        {id: 'new', text: 'New wish', labels: 'Unanalyzed', labelsByOption: [['option', 'UNANALYZED']], disabled: true},
    ]});
    assert.equal(app.options.rowSelection.isRowSelectable({data: {id: 'analyzed'}}), true);
    assert.equal(app.options.rowSelection.isRowSelectable({data: {id: 'new'}}), false);
    app.select(['analyzed', 'new']);
    assert.deepEqual(app.checkboxes.map(input => input.checked), [true, false]);
    assert.equal(app.checkboxes[1].disabled, true);
    assert.match(app.status.textContent, /2 wishes shown; 1 selected/);
    assert.match(app.status.textContent, /Unanalyzed: 1/);
});

test('group counts are unique wish IDs per option and follow the current filter', () => {
    const app = browser();
    assert.match(app.status.textContent, /print \(Compatible: 1, Depends: 1, Not served: 0, Not a request: 1, Unanalyzed: 0\)/);
    assert.match(app.status.textContent, /card \(Compatible: 1, Depends: 0, Not served: 1, Not a request: 1, Unanalyzed: 0\)/);
    app.filter({optionId: 'print', label: 'COMPATIBLE'});
    assert.match(app.status.textContent, /print \(Compatible: 1, Depends: 0/);
    assert.doesNotMatch(app.status.textContent, /card \(/);
});

test('chart evidence event intersects filters and visibly resets without trusting unknown IDs', () => {
    const app = browser();
    app.evidence(['one', 'one', 'foreign', 42]);
    assert.deepEqual(Array.from(app.displayed, row => row.id), ['one']);
    assert.equal(app.evidenceFilter.hidden, false);
    assert.match(app.description.textContent, /Evidence filter: 1 wish IDs/);
    app.filter({optionId: 'card', label: 'COMPATIBLE'});
    assert.equal(app.displayed.length, 0);
    app.clearEvidence.handlers.click({preventDefault() {}});
    assert.equal(app.evidenceFilter.hidden, true);
    assert.deepEqual(Array.from(app.displayed, row => row.id), ['two']);
    app.evidence([]);
    assert.equal(app.evidenceFilter.hidden, false);
    assert.equal(app.displayed.length, 0);
});

test('sorting uses table timestamps and wish content stays literal text', () => {
    const app = browser();
    app.filter({sort: 'newest'});
    assert.deepEqual(Array.from(app.displayed, row => row.id), ['two', 'one', 'three']);
    app.filter({sort: 'oldest'});
    assert.deepEqual(Array.from(app.displayed, row => row.id), ['three', 'one', 'two']);
    const malicious = '<img src=x onerror=alert(1)>';
    const rendered = app.options.defaultColDef.cellRenderer({value: malicious});
    assert.equal(rendered.textContent, malicious);
    assert.equal(app.options.enableCellTextSelection, true);
    assert.equal(app.options.ensureDomOrder, true);
    assert.equal(app.options.defaultColDef.sortable, true);
});

test('a runtime enhancement failure restores server GET submit and table', () => {
    const app = browser();
    app.failUpdates();
    app.filter({q: 'print'});
    assert.equal(app.table.hidden, false);
    assert.equal(app.container.hidden, true);
    let prevented = false;
    app.filters.handlers.submit({preventDefault() { prevented = true; }});
    assert.equal(prevented, false);
    assert.match(app.status.textContent, /Use the table and forms/);
});

test('two hundred rows filter and select without losing source IDs', () => {
    const app = browser({sourceRows: Array.from({length: 200}, (_, index) => ({
        id: `wish-${index}`, text: `Wish ${index}`, labels: 'Compatible',
        labelsByOption: [['option', 'COMPATIBLE']], source: index % 2 ? 'ONSITE' : 'IMPORTED',
    }))});
    assert.equal(app.displayed.length, 200);
    app.filter({q: 'IMPORTED'});
    assert.equal(app.displayed.length, 100);
    app.select(app.displayed.map(row => row.id));
    assert.equal(app.checkboxes.filter(input => input.checked).length, 100);
});
