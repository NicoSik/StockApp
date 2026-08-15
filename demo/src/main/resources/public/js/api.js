/**
 * The only place that talks to the server.
 *
 * Every response funnels through `request`, so error handling, JSON parsing and
 * request cancellation are defined once. The server always answers a failure
 * with `{ "error": "..." }`, and that message is written to be shown to a
 * person, so it is surfaced verbatim.
 */

export class ApiError extends Error {
    constructor(message, status) {
        super(message);
        this.name = 'ApiError';
        this.status = status;
    }
}

async function request(path, options = {}) {
    let response;
    // FormData must set its own Content-Type, because only the browser knows
    // the multipart boundary. Declaring JSON here would corrupt the upload.
    const isMultipart = options.body instanceof FormData;
    try {
        response = await fetch(path, {
            headers: {
                Accept: 'application/json',
                ...(options.body && !isMultipart ? { 'Content-Type': 'application/json' } : {}),
            },
            ...options,
        });
    } catch (cause) {
        // fetch only rejects on a network-level failure, never on 4xx/5xx.
        if (cause.name === 'AbortError') throw cause;
        throw new ApiError('Cannot reach the server. Is it still running?', 0);
    }

    if (response.status === 204) return null;

    const text = await response.text();
    let body = null;
    if (text) {
        try {
            body = JSON.parse(text);
        } catch {
            throw new ApiError('The server sent a malformed response.', response.status);
        }
    }

    if (!response.ok) {
        throw new ApiError(body?.error ?? `Request failed (${response.status}).`, response.status);
    }
    return body;
}

const query = (params) => {
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params)) {
        if (value !== undefined && value !== null && value !== '') search.set(key, value);
    }
    const string = search.toString();
    return string ? `?${string}` : '';
};

const post = (path, body) => request(path, { method: 'POST', body: JSON.stringify(body ?? {}) });

export const api = {
    meta: () => request('/api/meta'),
    clock: () => request('/api/market/clock'),

    search: (q, signal) => request(`/api/search${query({ q, limit: 12 })}`, { signal }),
    quotes: (symbols) => request(`/api/quotes${query({ symbols: symbols.join(',') })}`),
    /** Quote + sparkline for many symbols in one round trip. */
    rows: (symbols) => (symbols.length ? request(`/api/rows${query({ symbols: symbols.join(',') })}`) : []),

    stock: (symbol) => request(`/api/stocks/${encodeURIComponent(symbol)}`),
    candles: (symbol, range, signal) =>
        request(`/api/stocks/${encodeURIComponent(symbol)}/candles${query({ range })}`, { signal }),

    watchlists: () => request('/api/watchlists'),
    createWatchlist: (name) => post('/api/watchlists', { name }),
    deleteWatchlist: (id) => request(`/api/watchlists/${id}`, { method: 'DELETE' }),
    addToWatchlist: (id, symbol) => post(`/api/watchlists/${id}/items`, { symbol }),
    removeFromWatchlist: (id, symbol) =>
        request(`/api/watchlists/${id}/items/${encodeURIComponent(symbol)}`, { method: 'DELETE' }),

    portfolio: () => request('/api/portfolio'),
    portfolioHistory: (range) => request(`/api/portfolio/history${query({ range })}`),
    trades: (limit = 25) => request(`/api/portfolio/trades${query({ limit })}`),
    order: (symbol, side, quantity) => post('/api/portfolio/orders', { symbol, side, quantity }),
    resetPortfolio: () => post('/api/portfolio/reset'),

    // --- Multi-broker aggregator (real holdings, NOK) ----------------------
    holdings: () => request('/api/holdings'),
    holdingsHistory: () => request('/api/holdings/history'),
    previewImport: (file) => {
        const form = new FormData();
        form.append('file', file);
        return request('/api/holdings/import/preview', { method: 'POST', body: form });
    },
    commitImport: (previewId, overrides, skip) =>
        post('/api/holdings/import/commit', { previewId, overrides, skip }),
    lookupInstrument: (q, currency) => request(`/api/holdings/lookup${query({ q, currency })}`),
    etoroStatus: () => request('/api/holdings/etoro/status'),
    etoroSync: () => post('/api/holdings/etoro/sync'),

    alerts: () => request('/api/alerts'),
    createAlert: (symbol, direction, threshold) => post('/api/alerts', { symbol, direction, threshold }),
    deleteAlert: (id) => request(`/api/alerts/${id}`, { method: 'DELETE' }),
    evaluateAlerts: () => post('/api/alerts/evaluate'),
};
