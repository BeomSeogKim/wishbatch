(() => {
    const checkout = document.getElementById('pledge-checkout');
    if (!checkout) return;
    const feedback = document.getElementById('pledge-feedback');
    const statusLink = document.getElementById('pledge-status-link');
    const quantity = document.getElementById('quantity');
    const container = document.getElementById('paypal-button-container');
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

    quantity.addEventListener('change', () => {
        container.hidden = true;
        show('Select Update total to review the server-calculated amount before authorizing.');
    });

    if (!window.paypal) {
        show('PayPal could not load. No pledge counted. Reload this page to try again.');
        return;
    }

    window.paypal.Buttons({
        async createOrder() {
            pledge = null;
            approvalStarted = false;
            serverMessage = false;
            quantity.disabled = true;
            show('Creating your sandbox order. No pledge has counted yet.');
            try {
                const response = await fetch(checkout.dataset.createUrl, {
                    method: 'POST',
                    headers: requestHeaders(),
                    body: JSON.stringify({quantity: Number(checkout.dataset.quantity), campaignId: checkout.dataset.campaignId})
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
    }).render('#paypal-button-container').catch(() => {
        show('PayPal could not load. No pledge counted. Reload this page to try again.');
    });
})();
