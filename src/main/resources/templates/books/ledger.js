function initializeBooksLedger() {
    const table = document.getElementById('books-table');
    if (!table || !window.agGrid?.createGrid) return;
    const fallback = document.getElementById('books-fallback');
    const grid = document.getElementById('books-grid');
    const fields = ['pledge', 'units', 'amount', 'localBefore', 'providerBefore', 'matchBefore', 'localAfter', 'providerAfter', 'matchAfter'];
    const display = window.WishbatchDisplay;
    const columnDefs = Array.from(table.tHead.rows[0].cells, (cell, index) => ({
        field: fields[index], headerName: cell.textContent.trim(), flex: index === 4 || index === 7 ? 2 : 1, minWidth: index === 4 || index === 7 ? 130 : 85,
        ...(index === 1 ? { filter: 'agNumberColumnFilter' } : {}),
        ...([5, 8].includes(index) ? { pinned: index === 5 ? 'left' : 'right', lockPinned: true } : {}),
        ...([3, 5, 6, 8].includes(index) ? { valueFormatter: params => display.state(params.value) } : {}),
        ...(index === 0 ? { valueFormatter: params => display.shortId(params.value), tooltipField: 'pledge' } : {}),
        ...([4, 7].includes(index) ? { tooltipField: fields[index] } : {})
    }));
    columnDefs[0].cellRenderer = params => params.data.traceLink?.cloneNode(true) ?? params.value;
    columnDefs.unshift(columnDefs.splice(5, 1)[0]);
    const rowData = Array.from(table.tBodies[0].rows, row => Object.fromEntries(fields.map((field, index) => {
        const cell = row.cells[index];
        const entries = Array.from(cell.querySelectorAll('li'), item => item.textContent.trim());
        return [field, index === 1 ? Number(cell.textContent) : entries.length ? entries.join('; ') : (cell.getAttribute('title') || cell.textContent.trim())];
    })));
    rowData.forEach((row, index) => { row.traceLink = table.tBodies[0].rows[index].cells[0].querySelector('a'); });
    try {
        grid.hidden = false;
        const api = window.agGrid.createGrid(grid, {
            columnDefs, rowData,
            defaultColDef: { sortable: true, filter: true, resizable: true, floatingFilter: true },
            theme: window.agGrid.themeQuartz.withParams({ fontFamily: 'inherit', backgroundColor: 'var(--surface)', foregroundColor: 'var(--ink)', headerBackgroundColor: 'var(--surface-soft)', borderColor: 'var(--line)', accentColor: 'var(--accent)' }),
            domLayout: 'autoHeight',
            loadThemeGoogleFonts: false,
            pagination: true, paginationPageSize: 20, paginationPageSizeSelector: [20, 50, 100],
            enableCellTextSelection: true,
            enableBrowserTooltips: true,
            defaultCsvExportParams: {
                processCellCallback: params => {
                    const value = String(params.value ?? '');
                    return /^[=+\-@\t\r\n]/.test(value) ? "'" + value : value;
                }
            }
        });
        document.getElementById('books-search').addEventListener('input', event => api.setGridOption('quickFilterText', event.target.value));
        document.getElementById('books-export').addEventListener('click', () => api.exportDataAsCsv({ fileName: 'payment-books.csv' }));
        document.getElementById('books-fallback-toggle').addEventListener('click', event => {
            fallback.hidden = !fallback.hidden;
            event.target.setAttribute('aria-expanded', String(!fallback.hidden));
            event.target.textContent = fallback.hidden ? 'Show accessible table' : 'Hide accessible table';
        });
        fallback.hidden = true;
        document.getElementById('books-controls').hidden = false;
    } catch {
        grid.hidden = true;
        fallback.hidden = false;
    }
}

if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initializeBooksLedger);
else initializeBooksLedger();
