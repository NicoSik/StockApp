/**
 * State shared between the shell and the views.
 *
 * One object that views read from and write to. The shell owns routing and
 * teardown; each view owns its own markup.
 */

export const RANGES = ['1D', '1W', '1M', '3M', '1Y', '5Y'];

export const state = {
    route: { name: 'detail', symbol: null },
    meta: null,
    watchlists: [],
    activeWatchlistId: null,
    rows: [],
    portfolio: null,
    detail: null,
    range: readStored('ticker.range') || '1D',
    /** The chart on screen, whichever view drew it; destroyed on navigation. */
    chart: null,
    candleRequest: null,
    /** Alert ids already announced, so a fired alert is not re-toasted every poll. */
    announcedAlerts: new Set(),
};

export function safeStore(key, value) {
    try {
        localStorage.setItem(key, value);
    } catch {
        /* Private browsing; the preference simply will not persist. */
    }
}

function readStored(key) {
    try {
        return localStorage.getItem(key);
    } catch {
        return null;
    }
}
