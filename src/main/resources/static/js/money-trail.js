(() => {
    const table = document.querySelector('[data-money-trail] table');
    if (!table || !window.agGrid || typeof window.agGrid.createGrid !== 'function') return;

    const headers = Array.from(table.querySelectorAll('thead th'), cell => cell.textContent.trim());
    const rows = Array.from(table.querySelectorAll('tbody tr'), row =>
        Array.from(row.cells, cell => cell.textContent.trim()));
    const grid = document.createElement('div');
    grid.style.height = '400px';
    table.before(grid);
    try {
        const api = window.agGrid.createGrid(grid, {
            columnDefs: headers.map((header, index) => ({ headerName: header, field: String(index), sortable: true, filter: true })),
            rowData: rows.map(row => Object.assign({}, row)),
        });
        const button = document.createElement('button');
        button.type = 'button';
        button.textContent = 'Export grid CSV';
        button.addEventListener('click', () => api.exportDataAsCsv({
            processCellCallback: params => {
                const value = String(params.value ?? '');
                return /^[\t\r\n]|^\s*[=+\-@]/.test(value) ? "'" + value : value;
            },
        }));
        grid.before(button);
        table.hidden = true;
    } catch (_) {
        grid.remove();
    }
})();
