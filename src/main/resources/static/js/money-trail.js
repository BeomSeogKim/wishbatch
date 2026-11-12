(() => {
    const root = document.querySelector('[data-money-comparison]');
    const table = root?.querySelector('[data-comparison-table]');
    if (!table || !window.agGrid?.createGrid) return;
    const grid = root.querySelector('[data-comparison-grid]');
    const fallback = root.querySelector('[data-comparison-fallback]');
    const headers = Array.from(table.tHead.rows[0].cells, cell => cell.textContent.trim());
    const rows = Array.from(table.tBodies[0].rows, row =>
        Object.fromEntries(Array.from(row.cells, (cell, index) => [String(index), cell.textContent.trim()])));
    try {
        grid.hidden = false;
        grid.style.height = '32rem';
        const api = window.agGrid.createGrid(grid, {
            columnDefs: headers.map((header, index) => ({ headerName: header, field: String(index), minWidth: index === 5 ? 350 : 150 })),
            rowData: rows,
            defaultColDef: { sortable: true, filter: true, resizable: true },
            theme: window.agGrid.themeQuartz.withParams({ fontFamily: 'inherit' }),
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
