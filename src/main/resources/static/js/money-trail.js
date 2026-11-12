(() => {
    const root = document.querySelector('[data-money-comparison]');
    const table = root?.querySelector('[data-comparison-table]');
    if (!table || !window.agGrid?.createGrid) return;
    const grid = root.querySelector('[data-comparison-grid]');
    const fallback = root.querySelector('[data-comparison-fallback]');
    const headers = Array.from(table.tHead.rows[0].cells, cell => cell.textContent.trim());
    const rows = Array.from(table.tBodies[0].rows, row =>
        Object.fromEntries(Array.from(row.cells).flatMap((cell, index) => [
            [String(index), cell.textContent.trim()], [index + 'Title', cell.title || ''],
            [index + 'Details', cell.querySelector?.('details')],
        ])));
    rows.forEach((row, index) => { row.traceLink = table.tBodies[0].rows[index].cells[0].querySelector('a'); });
    try {
        grid.hidden = false;
        const api = window.agGrid.createGrid(grid, {
            columnDefs: headers.map((header, index) => ({ headerName: header, field: String(index), minWidth: index === 5 ? 350 : 150,
                pinned: header === 'Match at check' ? 'left' : undefined,
                wrapText: true, autoHeight: true,
                cellRenderer: params => {
                    if (index === 0 && params.data.traceLink) return params.data.traceLink.cloneNode(true);
                    const details = params.data[index + 'Details'];
                    if (details) return details.cloneNode(true);
                    const span = document.createElement('span');
                    span.textContent = params.value;
                    span.title = params.data[index + 'Title'];
                    return span;
                } })),
            rowData: rows,
            defaultColDef: { sortable: true, filter: true, resizable: true },
            theme: window.agGrid.themeQuartz.withParams({ fontFamily: 'inherit', browserColorScheme: 'dark',
                backgroundColor: 'var(--surface)', foregroundColor: 'var(--ink)', headerBackgroundColor: 'var(--surface-soft)',
                headerTextColor: 'var(--ink)', borderColor: 'var(--line)' }),
            domLayout: 'autoHeight',
            loadThemeGoogleFonts: false,
            pagination: true,
            paginationPageSize: 20,
            enableCellTextSelection: true,
        });
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = 'Export grid CSV';
        button.addEventListener('click', () => api.exportDataAsCsv({
            fileName: 'payment-comparison.csv',
            processCellCallback: params => {
                const value = String(params.value ?? '');
                return /^[\t\r\n]|^\s*[=+\-@]/.test(value) ? "'" + value : value;
            },
        }));
        grid.before(button);
        fallback.hidden = true;
    } catch (_) {
        grid.hidden = true;
        fallback.hidden = false;
    }
})();
