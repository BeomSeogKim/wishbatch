(() => {
    const root = document.querySelector('[data-wish-workbench]');
    if (!root || !window.agGrid?.createGrid) return;
    const table = root.querySelector('[data-workbench-table]');
    const container = root.querySelector('[data-workbench-grid]');
    const filters = root.querySelector('[data-workbench-filters]');
    const bulk = root.querySelector('[data-workbench-bulk]');
    const status = root.querySelector('[data-workbench-status]');
    const evidenceFilter = root.querySelector('[data-workbench-evidence-filter]');
    const clearEvidence = root.querySelector('[data-workbench-clear-evidence]');
    if (!table || !container || !filters || !bulk || !status) return;

    const rows = Array.from(table.querySelectorAll('tbody tr[data-wish-id]')).map((element, index) => {
        const row = {id: element.dataset.wishId, index, createdAt: element.dataset.createdAt || '', labelsByOption: []};
        for (const field of ['text', 'labels', 'evidence', 'flags', 'source', 'language', 'hidden', 'provenance']) {
            const text = element.querySelector(`[data-field="${field}"]`)?.textContent || '';
            row[field] = field === 'text' || field === 'evidence' ? text : text.trim();
        }
        row.labelsByOption = Array.from(element.querySelectorAll('[data-option-id][data-label]')).map(label => ({
            optionId: label.dataset.optionId,
            label: label.dataset.label,
        }));
        row.labels = Array.from(element.querySelectorAll('[data-option-id][data-label]'))
            .map(label => label.textContent.trim()).join('\n\n');
        return row;
    });
    const checkboxes = Array.from(bulk.querySelectorAll('input[name="wishIds"]'));
    const selectableIds = new Set(checkboxes.filter(checkbox => !checkbox.disabled).map(checkbox => checkbox.value));
    const optionNames = new Map(Array.from(filters.querySelector('[name="optionId"]')?.options || [])
        .filter(option => option.value).map(option => [option.value, option.textContent.trim()]));
    const labels = [['COMPATIBLE', 'Compatible'], ['CONDITIONAL', 'Depends'], ['INCOMPATIBLE', 'Not served'],
        ['NOT_A_REQUEST', 'Not a request'], ['UNANALYZED', 'Unanalyzed']];
    let evidenceIds = null;
    let active = false;
    let api;
    let currentSort = value('sort');

    function value(name) {
        return filters.querySelector(`[name="${name}"]`)?.value || '';
    }

    function normalize(label) {
        return label.toLowerCase().replace(/[_ -]/g, '');
    }

    function matchingRows() {
        const optionId = value('optionId');
        const label = normalize(value('label'));
        const query = value('q').trim().toLowerCase();
        const selected = rows.filter(row => {
            const labels = row.labelsByOption.filter(item => !optionId || item.optionId === optionId);
            return (!evidenceIds || evidenceIds.has(row.id)) &&
                (!label || labels.some(item => normalize(item.label) === label)) &&
                (!optionId || labels.length > 0) &&
                (!query || [row.id, row.text, row.labels, row.evidence, row.flags, row.source,
                    row.language, row.hidden, row.provenance].join(' ').toLowerCase().includes(query));
        });
        const sort = value('sort');
        if (sort === 'newest' || sort === 'oldest') {
            selected.sort((left, right) => (sort === 'newest' ? -1 : 1) *
                (left.createdAt.localeCompare(right.createdAt) || left.index - right.index));
        } else if (sort === 'label') {
            const labelKey = row => row.labelsByOption.filter(item => !optionId || item.optionId === optionId)
                .map(item => item.label).sort().join(' ');
            selected.sort((left, right) => labelKey(left).localeCompare(labelKey(right)) || left.index - right.index);
        } else if (['id', 'text', 'source', 'language', 'labels'].includes(sort)) {
            selected.sort((left, right) => left[sort].localeCompare(right[sort]) || left.index - right.index);
        }
        return selected;
    }

    function selectionChanged(gridApi) {
        const selectedIds = new Set(gridApi.getSelectedRows().filter(row => selectableIds.has(row.id)).map(row => row.id));
        for (const checkbox of checkboxes) checkbox.checked = !checkbox.disabled && selectedIds.has(checkbox.value);
        const optionIds = value('optionId') ? [value('optionId')] : Array.from(new Set(rows.flatMap(row =>
            row.labelsByOption.map(item => item.optionId))));
        const visible = matchingRows();
        const groups = optionIds.map(optionId => {
            const counts = labels.map(([code, title]) => {
                const ids = new Set(visible.filter(row => row.labelsByOption.some(item =>
                    item.optionId === optionId && item.label === code)).map(row => row.id));
                return `${title}: ${ids.size}`;
            });
            return `${optionNames.get(optionId) || optionId} (${counts.join(', ')})`;
        });
        status.textContent = `${gridApi.getDisplayedRowCount()} wishes shown; ${selectedIds.size} selected. ` +
            `Stated interest, not purchases. ${groups.join('; ')}`;
    }

    function fallback() {
        active = false;
        table.hidden = false;
        container.hidden = true;
        if (evidenceFilter) evidenceFilter.hidden = true;
        status.textContent = 'Interactive review could not load. Use the table and forms below.';
    }

    function refresh() {
        if (!active) return;
        try {
            const visible = matchingRows();
            // A changed evidence/filter scope must not submit invisible selections.
            api.deselectAll();
            if (currentSort !== value('sort')) {
                api.applyColumnState({defaultState: {sort: null}});
                currentSort = value('sort');
            }
            api.setGridOption('rowData', visible);
            selectionChanged(api);
            if (evidenceFilter) {
                evidenceFilter.hidden = evidenceIds === null;
                const description = evidenceFilter.querySelector('[data-workbench-evidence-description]');
                if (description) description.textContent = `Evidence filter: ${evidenceIds?.size || 0} wish IDs. Clear to review all wishes.`;
            }
        } catch (_) {
            fallback();
        }
    }

    function textCell(params) {
        const element = document.createElement('span');
        element.textContent = params.value == null ? '' : String(params.value);
        return element;
    }

    try {
        container.hidden = false;
        api = window.agGrid.createGrid(container, {
            rowData: matchingRows(),
            getRowId: params => params.data.id,
            defaultColDef: {sortable: true, resizable: true, minWidth: 140, cellRenderer: textCell},
            columnDefs: [
                {field: 'id', headerName: 'Wish ID', minWidth: 180},
                {field: 'text', headerName: 'Wish', minWidth: 300, flex: 2, wrapText: true, autoHeight: true},
                {field: 'labels', headerName: 'Option labels', minWidth: 240, wrapText: true, autoHeight: true},
                {field: 'evidence', headerName: 'Quoted evidence', minWidth: 280, wrapText: true, autoHeight: true},
                {field: 'flags', headerName: 'Review flags', minWidth: 220, wrapText: true, autoHeight: true},
                {field: 'source', headerName: 'Source'},
                {field: 'language', headerName: 'Language'},
                {field: 'hidden', headerName: 'Visibility'},
                {field: 'provenance', headerName: 'Review provenance', minWidth: 220, wrapText: true, autoHeight: true},
            ],
            rowSelection: {mode: 'multiRow', selectAll: 'filtered',
                isRowSelectable: node => selectableIds.has(node.data?.id)},
            onSelectionChanged: event => selectionChanged(event.api),
            enableCellTextSelection: true,
            ensureDomOrder: true,
        });
        if (!api?.setGridOption || !api?.getSelectedRows) throw new Error('Grid unavailable');
        active = true;
        table.hidden = true;
        selectionChanged(api);
    } catch (_) {
        fallback();
        return;
    }

    filters.addEventListener('submit', event => {
        if (!active) return;
        event.preventDefault();
        refresh();
    });
    filters.addEventListener('input', refresh);
    filters.addEventListener('change', refresh);
    window.addEventListener('wishbatch:filter-wishes', event => {
        if (!active || !Array.isArray(event.detail?.wishIds)) return;
        const knownIds = new Set(rows.map(row => row.id));
        evidenceIds = new Set(event.detail.wishIds.filter(id => typeof id === 'string' && knownIds.has(id)));
        refresh();
    });
    clearEvidence?.addEventListener('click', event => {
        if (!active) return;
        event.preventDefault();
        evidenceIds = null;
        refresh();
    });
})();
