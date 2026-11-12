(function () {
    'use strict';

    const display = window.WishbatchDisplay;

    function start() {
        const root = document.querySelector('[data-live-campaign]');
        if (!root) return;

        let stopped = false;
        let inFlight = false;
        let pollTimer;
        let clockTimer;
        let snapshot;
        let receivedAt;
        let expiredDeadline;
        let refreshAtExpiry = false;
        let invalidated = false;

        function text(field, value) {
            root.querySelectorAll('[data-live-field="' + field + '"]').forEach(node => {
                node.textContent = value;
            });
        }

        function reload() {
            root.querySelectorAll('[data-live-reload]').forEach(node => { node.hidden = false; });
        }

        function invalidate(actions) {
            invalidated = true;
            actions.forEach(action => {
                root.querySelectorAll('[data-live-action="' + action + '"]').forEach(area => {
                    area.hidden = true;
                    area.querySelectorAll('button').forEach(button => { button.disabled = true; });
                });
            });
            reload();
        }

        function tick() {
            if (stopped || document.hidden || !snapshot || !snapshot.campaign) return;
            const campaign = snapshot.campaign;
            const deadline = campaign.state === 'OPEN' ? campaign.closesAt :
                campaign.state === 'AWAITING_CONFIRMATION' ? campaign.confirmDeadline : null;
            text('deadlineLabel', campaign.state === 'AWAITING_CONFIRMATION' ?
                'to confirm production' : 'in the pledge window');
            if (deadline) {
                const remaining = Math.max(0, Date.parse(deadline) - Date.parse(snapshot.observedAt) -
                    (Date.now() - receivedAt));
                const seconds = Math.ceil(remaining / 1000);
                text('timeLeft', Math.floor(seconds / 3600) + 'h ' + Math.floor(seconds % 3600 / 60) +
                    'm ' + seconds % 60 + 's');
                if (remaining === 0 && expiredDeadline !== deadline) {
                    expiredDeadline = deadline;
                    invalidate(['pledge', 'confirmation', 'campaign']);
                    text('message', 'The deadline has passed. Refresh to review the current server state.');
                    if (inFlight) refreshAtExpiry = true;
                    else poll();
                }
            } else {
                text('timeLeft', 'No active deadline');
                text('deadlineLabel', '');
            }
            clockTimer = setTimeout(tick, 1000);
        }

        function render(next) {
            if (next.availability === 'NO_CAMPAIGN' && next.campaign === null) {
                snapshot = next;
                invalidate(['pledge', 'confirmation', 'campaign']);
                text('message', 'No published campaign is available. Refresh to review this workspace.');
                text('observedAt', display.time(next.observedAt));
                return;
            }
            const campaign = next.campaign;
            const counts = campaign.counts;
            const progress = campaign.progress;
            const decimals = [counts.statedInterest, counts.authorizedHolds, counts.paid,
                progress.units, progress.minimum, campaign.capacityLeft];
            if (next.availability !== 'CAMPAIGN' || !Number.isFinite(Date.parse(next.observedAt)) ||
                decimals.some(value => typeof value !== 'string' || !/^\d+$/.test(value)) ||
                [campaign.closesAt, campaign.confirmDeadline].some(value => value !== null &&
                    !Number.isFinite(Date.parse(value)))) throw new Error('Invalid campaign status');

            if (campaign.id !== root.dataset.campaignId) {
                invalidate(['pledge', 'confirmation', 'campaign']);
            }
            if (!campaign.pledgesOpen && root.dataset.initialState === 'OPEN') invalidate(['pledge']);
            if (campaign.state !== root.dataset.initialState) invalidate(['campaign', 'confirmation']);
            if (campaign.state !== root.dataset.initialState ||
                counts.authorizedHolds !== root.dataset.initialHolds ||
                counts.paid !== root.dataset.initialPaid ||
                campaign.capacityLeft !== root.dataset.initialCapacity ||
                campaign.confirmationVersion !== root.dataset.confirmationVersion) invalidate(['confirmation']);

            snapshot = next;
            receivedAt = Date.now();
            text('statedInterest', counts.statedInterest);
            text('authorizedHolds', counts.authorizedHolds);
            const terminal = campaign.state === 'FUNDED' || campaign.state === 'NOT_FUNDED';
            root.querySelectorAll('[data-live-hold-card]').forEach(node => {
                node.classList.toggle('count-authorized', !terminal);
            });
            root.querySelectorAll('[data-live-hold-progress]').forEach(node => { node.hidden = terminal; });
            text('holdCaption', terminal ? 'Current holds after settlement. Review payment records.' :
                'Units with a payment hold. Not paid yet.');
            text('paid', counts.paid);
            text('progressUnits', progress.units);
            text('progressMinimum', progress.minimum);
            text('progressBasis', progress.basis === 'PAID' ? 'paid' : 'authorized holds');
            text('capacityLeft', campaign.capacityLeft);
            text('state', display.state(campaign.state));
            text('closesAt', display.time(campaign.closesAt));
            text('confirmDeadline', display.time(campaign.confirmDeadline));
            text('observedAt', display.time(next.observedAt));
            text('warning', campaign.fundedPaidShortfall ?
                'The campaign was funded at settlement, but current paid units are below the minimum. Review payments before production.' : '');
            text('attention', campaign.needsAttention ? 'Payments need attention. Refresh to review the current details.' : '');
            text('message', invalidated ? 'The campaign has changed. Refresh before taking an action.' : 'Current server values.');
            clearTimeout(clockTimer);
            tick();
        }

        async function poll() {
            if (stopped || document.hidden || inFlight) return;
            clearTimeout(pollTimer);
            inFlight = true;
            try {
                const response = await fetch(root.dataset.statusUrl, {cache: 'no-store', credentials: 'same-origin'});
                if (stopped) return;
                if (response.status === 404) {
                    stop();
                    invalidate(['pledge', 'confirmation', 'campaign']);
                    text('message', 'This workspace is unavailable. Refresh to review the page.');
                    return;
                }
                if (!response.ok) throw new Error('Refresh failed');
                const next = await response.json();
                if (!stopped) render(next);
            } catch (_) {
                if (!stopped) {
                    text('message', 'Values may be stale. Refresh the page or wait for the next update.');
                    reload();
                }
            } finally {
                inFlight = false;
                if (!stopped && !document.hidden) {
                    pollTimer = setTimeout(poll, refreshAtExpiry ? 0 : 10000);
                    refreshAtExpiry = false;
                }
            }
        }

        function stop() {
            stopped = true;
            clearTimeout(pollTimer);
            clearTimeout(clockTimer);
        }

        document.addEventListener('visibilitychange', function () {
            clearTimeout(pollTimer);
            clearTimeout(clockTimer);
            if (!document.hidden && !stopped) {
                tick();
                poll();
            }
        });
        window.addEventListener('pagehide', stop);
        poll();
    }

    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
})();
