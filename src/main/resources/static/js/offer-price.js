(() => {
    const check = document.getElementById('offer-price-check');
    if (!check) return;
    const price = document.getElementById('unitPrice');
    const summary = document.getElementById('price-check-summary');
    const categories = document.getElementById('price-check-categories');
    const excluded = document.getElementById('price-excluded');
    const amount = document.getElementById('price-check-amount');
    const warning = document.getElementById('price-warning');
    const minimum = document.getElementById('minBatch');
    const pending = document.getElementById('offer-demand-pending');
    const sealState = document.getElementById('seal-state');
    const markPending = () => {
        if (pending) pending.hidden = false;
        const chart = document.getElementById('demand-price-chart');
        if (chart) chart.hidden = true;
        const demand = document.getElementById('demand-explorer');
        if (demand) demand.setAttribute('aria-label', 'Previous demand check. Refresh required.');
    };
    minimum?.addEventListener('input', markPending);
    price.form?.addEventListener('input', () => {
        if (sealState) sealState.textContent = 'Terms changed. Use Check this price to refresh this preview before publishing.';
    });
    let timer;
    let request;

    price.addEventListener('input', () => {
        clearTimeout(timer);
        request?.abort();
        markPending();
        if (amount) amount.textContent = 'Checking price…';
        if (warning && amount) warning.hidden = true;
        summary.textContent = 'Checking this price…';
        categories.textContent = '';
        excluded.replaceChildren();
        timer = setTimeout(async () => {
            const current = new AbortController();
            request = current;
            const url = new URL(check.dataset.url, window.location.origin);
            url.searchParams.set('unitPrice', price.value);
            try {
                const response = await fetch(url, {signal: current.signal, headers: {Accept: 'application/json'}});
                if (!response.ok) throw new Error('Price check unavailable');
                const result = await response.json();
                if (current.signal.aborted) return;
                if (amount) amount.textContent = `${result.formattedPrice} per unit`;
                if (warning && amount) warning.hidden = !result.belowCost;
                summary.textContent = `${result.fit} wishes fit this price, ${result.excludedByPrice.length} are excluded by their stated limit`;
                categories.textContent = `${result.compatible} Compatible; ${result.depends} Depends. Other conditions still need review. Wishes are stated interest, not orders.`;
                for (const wish of result.excludedByPrice) {
                    const item = document.createElement('li');
                    const link = document.createElement('a');
                    link.href = wish.sourceUrl;
                    link.textContent = 'View wish';
                    item.append(link, ` — stated limit: ${wish.limit} `);
                    for (const quote of wish.quotes) {
                        const evidence = document.createElement('q');
                        evidence.textContent = quote;
                        item.append(evidence);
                    }
                    excluded.append(item);
                }
            } catch (_) {
                if (current.signal.aborted) return;
                if (amount) amount.textContent = 'Price has not been checked.';
                summary.textContent = 'Enter a positive price with up to two decimal places, then use Check this price if the live check is unavailable.';
            }
        }, 200);
    });
})();
