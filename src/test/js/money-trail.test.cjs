const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');

const script = fs.readFileSync('src/main/resources/static/js/money-trail.js', 'utf8');

function setup(agGrid) {
    const grid = { hidden: true, style: {}, before(element) { this.button = element; } };
    const fallback = { hidden: false };
    const table = {
        tHead: { rows: [{ cells: [{ textContent: 'Pledge ID' }, { textContent: 'Status' }] }] },
        tBodies: [{ rows: [{ cells: [{ textContent: '=unsafe' }, { textContent: 'MATCH' }] }] }],
    };
    const root = { querySelector(selector) {
        return { '[data-comparison-table]': table, '[data-comparison-grid]': grid,
            '[data-comparison-fallback]': fallback }[selector];
    } };
    const document = {
        querySelector: () => root,
        createElement: () => ({ addEventListener(name, handler) { this.handler = handler; } }),
    };
    vm.runInNewContext(script, { document, window: { agGrid } });
    return { grid, fallback };
}

test('grid enhances the table and protects exported formula values', () => {
    let options;
    let exportParams;
    const agGrid = {
        themeQuartz: { withParams: () => ({}) },
        createGrid: (_, config) => { options = config; return { exportDataAsCsv: params => { exportParams = params; } }; },
    };
    const { grid, fallback } = setup(agGrid);
    assert.equal(grid.hidden, false);
    assert.equal(fallback.hidden, true);
    assert.equal(options.rowData[0]['1'], 'MATCH');
    grid.button.handler();
    assert.equal(exportParams.processCellCallback({ value: ' =1+1' }), "' =1+1");
    assert.equal(exportParams.processCellCallback({ value: 'normal' }), 'normal');
});

test('table remains available when the grid is missing or fails', () => {
    assert.equal(setup(undefined).fallback.hidden, false);
    const result = setup({ themeQuartz: { withParams: () => ({}) }, createGrid: () => { throw new Error('failed'); } });
    assert.equal(result.grid.hidden, true);
    assert.equal(result.fallback.hidden, false);
});
