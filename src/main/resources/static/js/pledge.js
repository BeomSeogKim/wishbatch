(() => {
    const panel = document.getElementById('offer-commitment');
    if (!panel) return;
    const state = panel.querySelector('[data-live-field="state"]');
    if (state) {
        function showSettlement() {
            const settled = /^(?:Funded|Not funded)\b/i.test(state.textContent.trim());
            panel.classList.toggle('offer-settled', settled);
            panel.querySelectorAll('[data-offer-hold-progress]').forEach(block => { block.hidden = settled; });
        }
        new MutationObserver(showSettlement).observe(state, {childList: true, characterData: true, subtree: true});
        showSettlement();
    }
    panel.querySelectorAll('[data-offer-progress]').forEach(progress => {
        const count = panel.querySelector('[data-live-field="' + progress.dataset.offerProgress + '"]');
        if (!count) return;
        new MutationObserver(() => { progress.value = count.textContent; })
            .observe(count, {childList: true, characterData: true, subtree: true});
    });
})();

(() => {
    const checkout = document.getElementById('pledge-checkout');
    if (!checkout) return;
    const feedback = document.getElementById('pledge-feedback');
    const statusLink = document.getElementById('pledge-status-link');
    const quantity = document.getElementById('quantity');
    const container = document.getElementById('paypal-button-container');
    const updateTotal = document.getElementById('update-total');
    const action = checkout.closest('[data-live-action="pledge"]');
    let pledge = null;
    let approvalStarted = false;
    let serverMessage = false;

    function show(message, statusUrl) {
        feedback.textContent = message;
        statusLink.hidden = !statusUrl;
        if (statusUrl) statusLink.href = statusUrl;
    }

    function uncertainApproval() {
        container.hidden = true;
        show('We could not confirm the authorization result. Check this pledge status before trying again.', pledge?.statusUrl);
    }

    function requestHeaders() {
        const token = document.querySelector('meta[name="_csrf"]')?.content;
        const header = document.querySelector('meta[name="_csrf_header"]')?.content;
        if (!token || !header) {
            show('This page expired. Reload the page before trying checkout again.', pledge?.statusUrl);
            serverMessage = true;
            throw new Error('Missing request verification');
        }
        return {'Content-Type': 'application/json', [header]: token};
    }

    let quoteVersion = 0;
    let autoQuoteAvailable = false;
    let quotePendingOrFailed = false;
    quantity.addEventListener('change', async () => {
        const version = ++quoteVersion;
        quotePendingOrFailed = true;
        container.hidden = true;
        const bar = document.querySelector('.offer-mobile-bar');
        if (bar) bar.hidden = true;
        document.getElementById('pledge-total').textContent = 'Updating…';
        show('Updating the server-calculated total.');
        try {
            const form = quantity.form;
            const url = new URL(form.action, window.location.href);
            url.search = new URLSearchParams(new FormData(form)).toString();
            const response = await fetch(url.toString(), {headers: {'Accept': 'text/html'}});
            if (!response.ok) throw new Error('Quote unavailable');
            const page = new DOMParser().parseFromString(await response.text(), 'text/html');
            if (version !== quoteVersion || action.hidden) return;
            const next = page.getElementById('pledge-checkout');
            const total = page.getElementById('pledge-total');
            if (!next || !total || next.dataset.campaignId !== checkout.dataset.campaignId || next.dataset.quantity !== quantity.value) {
                if (updateTotal) updateTotal.hidden = false;
                show(page.querySelector('.offer-pledge .notice-warning')?.textContent || 'This quote is unavailable. Reload the page to try again.');
                return;
            }
            quotePendingOrFailed = false;
            if (updateTotal && autoQuoteAvailable) updateTotal.hidden = true;
            checkout.dataset.quantity = next.dataset.quantity;
            document.getElementById('pledge-total').textContent = total.textContent;
            document.getElementById('pledge-quantity').textContent = quantity.value;
            document.getElementById('pledge-units').textContent = quantity.value === '1' ? 'unit' : 'units';
            const nextBar = page.querySelector('.offer-mobile-bar');
            if (bar && nextBar) {
                bar.querySelector('div').replaceWith(nextBar.querySelector('div'));
                bar.hidden = false;
            }
            if (window.paypal) {
                container.hidden = false;
                show('Continue with PayPal to authorize this amount in the sandbox.');
            } else {
                show('PayPal could not load. No pledge counted. Reload this page to try again.');
            }
        } catch (_) {
            if (version === quoteVersion) {
                if (updateTotal) updateTotal.hidden = false;
                show('Could not update the total. Use Update total to try again.');
            }
        }
    });

    if (!window.paypal) {
        show('PayPal could not load. No pledge counted. Reload this page to try again.');
        return;
    }

    const rendered = window.paypal.Buttons({
        async createOrder() {
            if (action.hidden || container.hidden || quantity.value !== checkout.dataset.quantity) throw new Error('Quote is not current');
            pledge = null;
            approvalStarted = false;
            serverMessage = false;
            quantity.disabled = true;
            show('Creating your sandbox order. No pledge has counted yet.');
            try {
                const response = await fetch(checkout.dataset.createUrl, {
                    method: 'POST',
                    headers: requestHeaders(),
                    body: JSON.stringify({quantity: Number(checkout.dataset.quantity), campaignId: checkout.dataset.campaignId,
                        wishId: checkout.dataset.wishId || undefined})
                });
                const result = await response.json();
                if (!response.ok) {
                    serverMessage = true;
                    show(result.message, result.statusUrl);
                    throw new Error('Order was not created');
                }
                pledge = result;
                show(result.message, result.statusUrl);
                return result.orderId;
            } catch (error) {
                quantity.disabled = false;
                throw error;
            }
        },
        async onApprove(data) {
            approvalStarted = true;
            if (!pledge || data.orderID !== pledge.orderId) {
                uncertainApproval();
                return;
            }
            show('Checking your authorization with PayPal.', pledge.statusUrl);
            try {
                const response = await fetch(`${checkout.dataset.createUrl}/${pledge.pledgeId}/authorize`, {
                    method: 'POST', headers: requestHeaders()
                });
                const result = await response.json();
                if (response.ok) {
                    window.location.assign(result.statusUrl);
                } else {
                    serverMessage = true;
                    container.hidden = true;
                    show(result.message, result.statusUrl);
                }
            } catch (_) {
                uncertainApproval();
            }
        },
        onCancel() {
            if (approvalStarted) {
                uncertainApproval();
                return;
            }
            quantity.disabled = false;
            show('Approval cancelled. No pledge counted. You can try again.');
        },
        onError() {
            if (approvalStarted) {
                if (!serverMessage) uncertainApproval();
                return;
            }
            quantity.disabled = false;
            if (!serverMessage) show('PayPal checkout could not finish. No pledge counted. You can try again.');
        }
    }).render('#paypal-button-container');
    rendered.then(() => {
        autoQuoteAvailable = typeof fetch === 'function' && typeof URL === 'function' &&
            typeof URLSearchParams === 'function' && typeof FormData === 'function' && typeof DOMParser === 'function';
        if (updateTotal && autoQuoteAvailable && !quotePendingOrFailed) updateTotal.hidden = true;
    }).catch(() => {
        if (updateTotal) updateTotal.hidden = false;
        show('PayPal could not load. No pledge counted. Reload this page to try again.');
    });
})();
