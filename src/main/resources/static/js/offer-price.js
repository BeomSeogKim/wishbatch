(() => {
    const check = document.getElementById('offer-price-check');
    if (!check) return;
    const price = document.getElementById('unitPrice');
    const summary = document.getElementById('price-check-summary');
    const categories = document.getElementById('price-check-categories');
    const excluded = document.getElementById('price-excluded');
    let timer;
    let request;

    price.addEventListener('input', () => {
        clearTimeout(timer);
        request?.abort();
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
                summary.textContent = 'Enter a positive whole-number price, then use Check this price if the live check is unavailable.';
            }
        }, 200);
    });
})();
