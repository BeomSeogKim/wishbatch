(function () {
    'use strict';
    const root = document.getElementById('analysis-progress');
    if (!root || !['QUEUED', 'RUNNING'].includes(root.dataset.status)) return;
    const element = id => document.getElementById(id);
    const terminal = status => ['COMPLETED', 'FAILED', 'INTERRUPTED'].includes(status);
    function list(id, values) {
        const target = element(id);
        target.replaceChildren();
        values.forEach(value => {
            const item = document.createElement('li');
            item.textContent = value;
            target.appendChild(item);
        });
    }
    async function poll() {
        try {
            const response = await fetch(root.dataset.pollUrl, {headers: {Accept: 'application/json'}, credentials: 'same-origin'});
            if (!response.ok) throw new Error('Progress unavailable');
            const job = await response.json();
            element('progress-status').textContent = job.status;
            element('progress-processed').textContent = job.processedWishes;
            element('progress-batches').textContent = job.completedBatches;
            element('progress-checkpoint-warning').hidden = !job.checkpointIncomplete;
            list('progress-validation', Object.entries(job.validationCounts).map(([rule, count]) => `${rule}: ${count}`));
            list('progress-events', job.events.map(event => `${event.sequence}. ${event.stage}` +
                (event.reading == null ? '' : ` · reading ${event.reading}`) +
                (event.batch == null ? '' : ` · batch ${event.batch}/${event.totalBatches}`) +
                ` · ${event.processedWishes} distinct wishes processed` +
                Object.entries(event.validationCounts).map(([rule, count]) => ` · ${rule}: ${count}`).join('')));
            if (terminal(job.status)) {
                element('analysis-progress-start').disabled = false;
                element('progress-failure').hidden = job.status === 'COMPLETED';
                if (job.status === 'COMPLETED') {
                    job.finalCounts.forEach(counts => {
                        const row = root.querySelector(`[data-option-id="${counts.optionId}"]`);
                        if (row) ['compatible', 'depends', 'notServed', 'notARequest'].forEach(key => {
                            row.querySelector(`[data-count="${key}"]`).textContent = counts[key];
                        });
                    });
                    element('progress-final').hidden = false;
                    if (job.runId) {
                        element('progress-review').href = `${root.dataset.proposalUrl}?run=${encodeURIComponent(job.runId)}`;
                        element('progress-review').hidden = false;
                    }
                }
                return;
            }
            if (!['QUEUED', 'RUNNING'].includes(job.status)) throw new Error('Unknown progress status');
            window.setTimeout(poll, 1000);
        } catch (_) {
            element('progress-feedback').textContent = 'Live updates paused. Refresh recorded progress to check this attempt.';
        }
    }
    poll();
}());
