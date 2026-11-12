(() => {
    'use strict';
    const count = value => value != null && /^(0|[1-9][0-9]*)$/.test(value) && Number.isSafeInteger(Number(value))
        ? Number(value) : null;
    if (typeof MutationObserver === 'function') {
        document.querySelectorAll('progress[data-count-field]').forEach(progress => {
            const source = document.querySelector('[data-live-field="' + progress.dataset.countField + '"]');
            if (!source) return;
            const update = () => {
                const value = count(source.textContent.trim());
                if (value !== null) progress.value = value;
            };
            update();
            new MutationObserver(update).observe(source, { childList: true, characterData: true, subtree: true });
        });
    }

    const current = document.getElementById('campaign-charts');
    const api = window.agCharts?.AgCharts;
    if (!current || !api) return;

    const stages = [
        { stage: 'Stated interest (requests)', label: 'Interest (requests)', count: count(current.dataset.interest) },
        { stage: 'Authorized holds (units)', label: 'Holds (units)', count: count(current.dataset.authorized) },
        { stage: 'Completed payments (units)', label: 'Paid (units)', count: count(current.dataset.paid) },
    ];
    const history = Array.from(document.querySelectorAll('#count-history tbody tr')).map((row, index) => ({
        checkpointOrder: String(index + 1),
        checkpoint: `${row.dataset.recordedAt} · ${Array.from(row.querySelectorAll('[data-source-type]')).map(source => source.textContent).join(', ')}`,
        anchor: row.id,
        interest: count(row.dataset.interest),
        authorized: count(row.dataset.authorized),
        paid: count(row.dataset.paid),
    }));
    const status = document.getElementById('chart-status');
    const show = (id, options) => {
        const container = document.getElementById(id);
        if (!container) return;
        try {
            container.hidden = false;
            api.create({ container, height: 320, ...options });
        } catch (_) {
            container.hidden = true;
            if (status) status.textContent = 'A chart could not load. Exact counts and recorded sources remain available in the tables.';
        }
    };
    show('current-stages-chart', {
        title: { text: 'Current stages' },
        subtitle: { text: 'Different units; no conversion rate' },
        data: stages,
        legend: { enabled: false },
        series: [{ type: 'bar', direction: 'horizontal', xKey: 'label', yKey: 'count', yName: 'Count (requests or units)' }],
    });
    if (!history.some(point => point.interest !== null || point.authorized !== null || point.paid !== null)) return;
    show('recorded-counts-chart', {
        title: { text: 'Recorded checkpoints' },
        subtitle: { text: 'Checkpoint order; unavailable values are gaps' },
        legend: { position: 'bottom', orientation: 'vertical', maxHeight: 100, spacing: 16 },
        data: history,
        series: [
            ['interest', 'Stated interest (requests)'],
            ['authorized', 'Authorized holds (units)'],
            ['paid', 'Completed payments (units)'],
        ].map(([yKey, yName]) => ({
            type: 'line', xKey: 'checkpointOrder', yKey, yName,
            strokeWidth: 0, marker: { enabled: true }, connectMissingData: false,
            listeners: { seriesNodeClick: event => document.getElementById(event.datum.anchor)?.focus() },
        })),
    });
})();
