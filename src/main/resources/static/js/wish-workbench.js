(() => {
    const root = document.querySelector('[data-wish-workbench]');
    if (!root) return;
    const table = root.querySelector('[data-workbench-table]');
    const container = root.querySelector('[data-workbench-grid]');
    const filters = root.querySelector('[data-workbench-filters]');
    const bulk = root.querySelector('[data-workbench-bulk]');
    const status = root.querySelector('[data-workbench-status]');
    const evidenceFilter = root.querySelector('[data-workbench-evidence-filter]');
    const clearEvidence = root.querySelector('[data-workbench-clear-evidence]');
    if (!table || !container || !filters || !bulk || !status) return;

    const detail = root.querySelector('[data-workbench-detail]');
    let detailOrigin;
    const closeDetail = root.querySelector('[data-workbench-detail-close]');
    const detailContent = root.querySelector('[data-workbench-detail-content]');
    const apply = filters.querySelector('[data-workbench-apply]');
    const priority = filters.querySelector('[data-workbench-priority]');
    const initialSort = filters.querySelector('[name="sort"]')?.value || '';

    const rows = Array.from(table.querySelectorAll('tbody tr[data-wish-id]')).map((element, index) => {
        const row = {id: element.dataset.wishId, detailUrl: element.dataset.detailUrl, index, createdAt: element.dataset.createdAt || '', labelsByOption: []};
        for (const field of ['text', 'labels', 'evidence', 'flags', 'source', 'language', 'hidden', 'provenance']) {
            const text = element.querySelector(`[data-field="${field}"]`)?.textContent || '';
            row[field] = field === 'text' || field === 'evidence' ? text : text.trim();
        }
        row.labelsByOption = Array.from(element.querySelectorAll('[data-option-id][data-label]')).map(label => ({
            optionId: label.dataset.optionId,
            label: label.dataset.label,
            reason: label.dataset.reason || '',
            displayLabel: label.dataset.displayLabel || '',
        }));
        row.labels = Array.from(element.querySelectorAll('[data-option-id][data-label]'))
            .map(label => label.textContent.trim()).join('\n\n');
        row.quotes = Array.from(element.querySelectorAll('[data-field="evidence"] q')).map(quote => quote.textContent);
        row.evidenceNotes = Array.from(element.querySelectorAll('[data-field="evidence"] span')).map(note => note.textContent).join('\n');
        row.flagItems = Array.from(element.querySelectorAll('[data-workbench-flag]')).map(flag => flag.textContent);
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
    if (priority && rows.some(row => row.flagItems.length)) {
        priority.hidden = false;
        if (value('sort') === 'id' && filters.dataset.workbenchSortExplicit !== 'true') filters.querySelector('[name="sort"]').value = 'flags';
    }
    let detailRequest = 0;
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
        if (sort === 'flags') {
            selected.sort((left, right) => Number(right.flagItems.length > 0) - Number(left.flagItems.length > 0) || left.index - right.index);
        } else if (sort === 'newest' || sort === 'oldest') {
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
        status.textContent = `${gridApi.getDisplayedRowCount()} wishes shown; ${selectedIds.size} selected. Stated interest, not purchases.`;
        status.title = groups.join('; ');
        const counts = root.querySelector('[data-workbench-counts]');
        if (counts) counts.replaceChildren(...groups.map(group => {
            const paragraph = document.createElement('p');
            paragraph.textContent = group;
            return paragraph;
        }));
    }

    function fallback() {
        active = false;
        for (const checkbox of checkboxes) checkbox.checked = false;
        if (apply) apply.hidden = false;
        if (priority) priority.hidden = true;
        filters.querySelector('[name="sort"]').value = initialSort;
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
            for (const checkbox of checkboxes) checkbox.checked = false;
            detailRequest++;
            if (detail) detail.hidden = true;
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

    function showDetail(row, origin = document.activeElement) {
        if (!detail || !detailContent) return;
        detailOrigin = origin;
        detailContent.replaceChildren();
        const add = (tag, text) => {
            const element = document.createElement(tag);
            element.textContent = text;
            detailContent.append(element);
        };
        add('h3', 'Source wish');
        add('p', row.text);
        add('p', `Wish ID: ${row.id}`);
        add('p', `Source: ${row.source} · Language: ${row.language} · ${row.hidden}`);
        add('h3', 'Option labels and reasons');
        for (const item of row.labelsByOption) {
            add('p', `${optionNames.get(item.optionId) || item.optionId}: ${item.displayLabel} — ${item.reason}`);
        }
        add('h3', 'Grounded quotes');
        for (const quote of row.quotes) add('q', quote);
        if (row.evidenceNotes) add('p', row.evidenceNotes);
        add('h3', 'Review flags and validation');
        add('p', row.flags);
        add('h3', 'Review provenance');
        add('p', row.provenance);
        detail.hidden = false;
        detail.open = true;
        detail.querySelector('summary')?.focus();
        detail.scrollIntoView({block: 'start'});
        const request = ++detailRequest;
        if (row.detailUrl) {
            const loading = document.createElement('p');
            loading.textContent = 'Loading recorded readings and reading aid…';
            detailContent.append(loading);
            detailContent.setAttribute('aria-busy', 'true');
            window.fetch(row.detailUrl).then(response => {
                if (!response.ok) throw new Error('Detail unavailable');
                return response.text();
            }).then(html => {
                if (request !== detailRequest) return;
                const page = new DOMParser().parseFromString(html, 'text/html');
                const recorded = page.querySelector('[data-workbench-recorded-detail]');
                if (!recorded) throw new Error('Detail unavailable');
                detailContent.replaceChildren(recorded);
                detailContent.setAttribute('aria-busy', 'false');
            }).catch(() => {
                if (request !== detailRequest) return;
                loading.remove();
                detailContent.setAttribute('aria-busy', 'false');
                add('p', 'Recorded readings and reading aid could not load here. Open the detail page to retry.');
                const link = document.createElement('a');
                link.href = row.detailUrl;
                link.textContent = 'Open wish detail page';
                detailContent.append(link);
            });
        }
    }

    function wishCell(params) {
        const button = document.createElement('button');
        button.type = 'button';
        button.className = 'workbench-wish workbench-compact';
        button.textContent = params.data.text;
        button.title = 'Open full wish evidence and review history';
        button.addEventListener('click', event => { event?.stopPropagation(); showDetail(params.data, button); });
        button.addEventListener('keydown', event => {
            if (event.key !== 'Enter') return;
            event.preventDefault();
            event.stopPropagation();
            showDetail(params.data, button);
        });
        return button;
    }

    function labelCell(params) {
        const item = params.data.labelsByOption.find(item => item.optionId === params.colDef.colId);
        const chip = textCell({value: item?.displayLabel || ''});
        chip.className = `workbench-chip workbench-chip-${item?.label || 'UNANALYZED'}`;
        chip.title = item?.reason || '';
        return chip;
    }

    function evidenceCell(params) {
        const quote = textCell({value: params.data.quotes[0]});
        const element = document.createElement(params.data.quotes.length ? 'q' : 'span');
        element.textContent = params.data.quotes.length ? quote.textContent : params.data.evidence;
        element.className = 'workbench-compact';
        element.title = params.data.evidence;
        return element;
    }

    function flagCell(params) {
        const flags = params.data.flagItems;
        const chip = textCell({value: flags.length ? `${flags[0].split(':')[0]}${flags.length > 1 ? ` +${flags.length - 1}` : ''}` : ''});
        chip.className = flags.length ? 'workbench-chip workbench-chip-flag workbench-compact' : '';
        chip.title = flags.join('\n');
        return chip;
    }

    closeDetail?.addEventListener('click', () => {
        detailRequest++;
        detail.hidden = true;
        detailOrigin?.focus();
    });

    table.addEventListener('click', event => {
        if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        if (event.target.closest?.('input, button, select')) return;
        const element = event.target.closest?.('[data-wish-id]');
        const row = rows.find(row => row.id === element?.dataset.wishId);
        if (row) { event.preventDefault(); showDetail(row, element.querySelector('.workbench-source-link')); }
    });
    if (!window.agGrid?.createGrid) return;

    try {
        const theme = window.agGrid.themeQuartz.withParams({
            backgroundColor: 'var(--surface)', foregroundColor: 'var(--ink)',
            headerBackgroundColor: 'var(--surface-soft)', headerTextColor: 'var(--ink)',
            borderColor: 'var(--line)', accentColor: 'var(--accent)',
            selectedRowBackgroundColor: 'var(--accent-soft)', rowHoverColor: 'var(--surface-soft)',
            chromeBackgroundColor: 'var(--surface-soft)', subtleTextColor: 'var(--muted)',
            inputBorder: {color: 'var(--control-line)'}, focusShadow: '0 0 0 2px var(--focus)',
            fontFamily: 'var(--font)', fontSize: 14, spacing: 6, browserColorScheme: 'inherit',
            wrapperBorderRadius: 8,
        });
        container.hidden = false;
        api = window.agGrid.createGrid(container, {
            theme,
            rowData: matchingRows(),
            getRowId: params => params.data.id,
            rowHeight: 64,
            headerHeight: 48,
            defaultColDef: {sortable: true, resizable: true, minWidth: 140, cellRenderer: textCell},
            columnDefs: [
                {field: 'text', headerName: 'Wish', headerTooltip: 'Press Enter for full wish details', pinned: 'left', lockPinned: true,
                    width: window.matchMedia?.('(max-width: 600px)').matches ? 220 : 360, minWidth: 220, cellRenderer: wishCell},
                ...Array.from(optionNames, ([optionId, name]) => ({
                    colId: optionId, headerName: name, headerTooltip: name, width: 180, minWidth: 170, wrapHeaderText: true, autoHeaderHeight: true,
                    valueGetter: params => params.data.labelsByOption.find(item => item.optionId === optionId)?.displayLabel || '',
                    cellRenderer: labelCell,
                })),
                {field: 'evidence', headerName: 'Quoted evidence', width: 240, cellRenderer: evidenceCell},
                {field: 'flags', headerName: 'Review flags', width: 160, cellRenderer: flagCell},
                {field: 'source', headerName: 'Source', width: 110, minWidth: 100},
                {field: 'hidden', headerName: 'Visibility', width: 110, minWidth: 100},
            ],
            selectionColumnDef: {width: 44, minWidth: 44, maxWidth: 44, pinned: 'left'},
            onRowClicked: event => {
                if (!event.event?.target?.closest?.('input, button, a, [role=checkbox]')) showDetail(event.data);
            },
            onCellKeyDown: event => {
                if (event.event?.key === 'Enter' && event.colDef.colId !== 'ag-Grid-SelectionColumn' &&
                    !event.event.target?.closest?.('button, input')) {
                    event.event.preventDefault();
                    showDetail(event.data);
                }
            },
            rowSelection: {mode: 'multiRow', selectAll: 'filtered',
                isRowSelectable: node => selectableIds.has(node.data?.id)},
            onSelectionChanged: event => selectionChanged(event.api),
            enableCellTextSelection: true,
            ensureDomOrder: true,
        });
        if (!api?.setGridOption || !api?.getSelectedRows) throw new Error('Grid unavailable');
        active = true;
        table.hidden = true;
        if (apply) apply.hidden = true;
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
    function filterLabel(optionId, label) {
        if (!active || !optionNames.has(optionId) || !labels.some(([code]) => code === label)) return false;
        filters.querySelector('[name="optionId"]').value = optionId;
        filters.querySelector('[name="label"]').value = label;
        filters.querySelector('[name="q"]').value = '';
        evidenceIds = null;
        refresh();
        return active;
    }

    // Links retain the GET query fallback until the local grid is available.
    window.addEventListener('wishbatch:filter-label', event => {
        filterLabel(event.detail?.optionId, event.detail?.label);
    });
    document.addEventListener('click', event => {
        const link = event.target.closest?.('[data-workbench-label-link]');
        if (!link || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
        if (filterLabel(link.dataset.optionId, link.dataset.label)) {
            event.preventDefault();
            root.scrollIntoView({block: 'start'});
            filters.querySelector('[name="label"]').focus();
        }
    });
    clearEvidence?.addEventListener('click', event => {
        if (!active) return;
        event.preventDefault();
        evidenceIds = null;
        refresh();
    });
})();
