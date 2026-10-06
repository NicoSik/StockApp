/**
 * Application shell: theme, routing, the watchlist rail, polling and
 * shortcuts. Each screen lives in its own module - detail.js, portfolio.js and
 * holdings.js - and reads shared state from state.js.
 *
 * There is no framework here on purpose - a dependency-free page loads
 * instantly and will still run in five years.
 */

import { api } from './api.js';
import { drawSparkline } from './sparkline.js';
import { SearchPalette } from './palette.js';
import { applyLiveQuote, renderDetailView, selectRange } from './detail.js';
import { renderPortfolioView } from './portfolio.js';
import { renderHoldingsView, teardownHoldingsChart } from './holdings.js';
import { toast } from './toast.js';
import { escapeHtml, qs, qsa, setHtml, setText } from './dom.js';
import { RANGES, safeStore, state } from './state.js';
import * as fmt from './format.js';

const QUOTE_POLL_MS = 15000;
const CLOCK_POLL_MS = 60000;
const ALERT_POLL_MS = 60000;

const main = qs('#main');

// ============================================================== theme & chrome

function initTheme() {
    qs('#theme-toggle').addEventListener('click', () => {
        const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
        document.documentElement.dataset.theme = next;
        safeStore('ticker.theme', next);
        // Sparklines are baked pixels; the chart repaints itself via observer.
        renderRail();
    });

    qs('#palette-toggle').addEventListener('click', () => {
        const root = document.documentElement;
        const enabled = root.dataset.palette === 'accessible';
        if (enabled) {
            delete root.dataset.palette;
            safeStore('ticker.palette', 'default');
            toast('Standard green/red palette.', 'info');
        } else {
            root.dataset.palette = 'accessible';
            safeStore('ticker.palette', 'accessible');
            toast('Colour-blind-safe palette: blue is up, orange is down.', 'info');
        }
        renderRail();
    });
}

function renderClock(clock) {
    const pill = qs('#market-pill');
    if (!clock) return;
    pill.dataset.session = clock.session;
    const labels = { OPEN: 'Market open', PRE: 'Pre-market', AFTER: 'After hours', CLOSED: 'Market closed' };
    setText(qs('#market-pill-text'), labels[clock.session] ?? 'Market status unknown');
    pill.title = clock.nextOpen ? `Next open ${fmt.dateTime(clock.nextOpen)} ET` : 'US equities session';
}

// ===================================================================== routing

function parseRoute(pathname) {
    const path = pathname.replace(/^\/+|\/+$/g, '');
    if (path === 'portfolio') return { name: 'portfolio' };
    if (path === 'holdings') return { name: 'holdings' };
    if (path === '') return { name: 'home' };
    return { name: 'detail', symbol: decodeURIComponent(path).toUpperCase() };
}

function navigate(path, { replace = false } = {}) {
    if (replace) history.replaceState({}, '', path);
    else history.pushState({}, '', path);
    handleRoute();
}

function handleRoute() {
    const route = parseRoute(location.pathname);

    if (route.name === 'home') {
        // Land on the first watched symbol; fall back to the portfolio.
        const first = state.rows[0]?.symbol ?? currentWatchlist()?.symbols?.[0];
        navigate(first ? `/${first}` : '/portfolio', { replace: true });
        return;
    }

    state.route = route;
    qsa('[data-route]').forEach((link) => {
        link.toggleAttribute('aria-current', link.dataset.route === route.name);
        if (link.dataset.route === route.name) link.setAttribute('aria-current', 'page');
        else link.removeAttribute('aria-current');
    });

    teardownChart();
    if (route.name === 'portfolio') renderPortfolioView(main, { navigate });
    else if (route.name === 'holdings') renderHoldingsView(main);
    else renderDetailView(main, route.symbol, { onWatchlistChanged: loadWatchlists });

    markActiveRow();
}

function initRouting() {
    window.addEventListener('popstate', handleRoute);
    document.addEventListener('click', (event) => {
        const link = event.target.closest('a[data-link]');
        if (!link) return;
        // Let modified clicks open a new tab as the user expects.
        if (event.metaKey || event.ctrlKey || event.shiftKey || event.button !== 0) return;
        event.preventDefault();
        navigate(link.getAttribute('href'));
    });
}

function teardownChart() {
    state.chart?.destroy();
    state.chart = null;
    // The holdings view owns its own chart instance, so it has to be told too -
    // otherwise its canvas listeners and observers outlive the page.
    teardownHoldingsChart();
    state.candleRequest?.abort();
    state.candleRequest = null;
}

// ================================================================== rail

function currentWatchlist() {
    return state.watchlists.find((list) => list.id === state.activeWatchlistId) ?? state.watchlists[0] ?? null;
}

async function loadWatchlists() {
    state.watchlists = await api.watchlists();
    if (!state.watchlists.some((list) => list.id === state.activeWatchlistId)) {
        state.activeWatchlistId = state.watchlists[0]?.id ?? null;
    }
    setText(qs('#rail-title'), currentWatchlist()?.name ?? 'Watchlist');
    await refreshRows();
}

async function refreshRows() {
    const symbols = currentWatchlist()?.symbols ?? [];
    setText(qs('#rail-count'), symbols.length ? String(symbols.length) : '');
    if (symbols.length === 0) {
        state.rows = [];
        setHtml(qs('#watchlist'), '<p class="rail__empty">No symbols yet.<br>Search to add one.</p>');
        return;
    }
    try {
        state.rows = await api.rows(symbols);
        renderRail();
    } catch (error) {
        if (state.rows.length === 0) {
            setHtml(qs('#watchlist'), `<p class="rail__empty">${escapeHtml(error.message)}</p>`);
        }
    }
}

function renderRail() {
    const container = qs('#watchlist');
    if (!container || state.rows.length === 0) return;

    container.innerHTML = state.rows
        .map((row) => {
            const quote = row.quote;
            const dir = fmt.direction(quote?.changePercent);
            return `
            <a class="watch-row" role="listitem" href="/${escapeHtml(row.symbol)}" data-link
               data-symbol="${escapeHtml(row.symbol)}">
                <span>
                    <span class="watch-row__symbol">${escapeHtml(row.symbol)}</span>
                    <span class="watch-row__company">${escapeHtml(row.company)}</span>
                </span>
                <canvas class="watch-row__spark" width="68" height="30" aria-hidden="true"
                        data-spark="${escapeHtml(row.symbol)}"></canvas>
                <span class="watch-row__figures">
                    <span class="watch-row__price">${fmt.price(quote?.price)}</span><br>
                    <span class="watch-row__change ${dir}">${fmt.signedPercent(quote?.changePercent)}</span>
                </span>
            </a>`;
        })
        .join('');

    // Canvases must exist in the document before they can be measured.
    for (const row of state.rows) {
        const canvas = container.querySelector(`[data-spark="${cssEscape(row.symbol)}"]`);
        if (canvas && row.spark) drawSparkline(canvas, row.spark.points, row.spark.baseline);
    }
    markActiveRow();
}

function markActiveRow() {
    qsa('.watch-row').forEach((row) => {
        const active = state.route.name === 'detail' && row.dataset.symbol === state.route.symbol;
        row.setAttribute('aria-current', active ? 'true' : 'false');
    });
}

const cssEscape = (value) => (window.CSS?.escape ? CSS.escape(value) : value.replace(/["\\]/g, '\\$&'));

// ================================================================== polling

/**
 * Polls only while the tab is visible.
 *
 * A background tab that keeps requesting quotes burns the API rate limit and
 * the user's battery for pixels nobody is looking at.
 */
function startPolling() {
    let quoteTimer = null;
    let clockTimer = null;
    let alertTimer = null;

    const tick = async () => {
        if (document.hidden) return;
        await refreshRows();
        if (state.route.name === 'detail' && state.detail) {
            try {
                const quotes = await api.quotes([state.route.symbol]);
                const quote = quotes[state.route.symbol];
                // Do not fight the user: leave the header alone while scrubbing.
                if (quote && state.chart?.activeIndex === null) {
                    applyLiveQuote(quote);
                }
            } catch {
                /* Transient; the next tick will catch up. */
            }
        }
    };

    const checkAlerts = async () => {
        if (document.hidden) return;
        try {
            const fired = await api.evaluateAlerts();
            for (const alert of fired) {
                if (state.announcedAlerts.has(alert.id)) continue;
                state.announcedAlerts.add(alert.id);
                toast(
                    `${alert.symbol} is ${alert.direction.toLowerCase()} ${fmt.usd(alert.threshold)} — now ${fmt.usd(alert.triggeredPrice)}.`,
                    'success',
                );
            }
        } catch {
            /* Alerts retry on the next interval. */
        }
    };

    const refreshClock = async () => {
        if (document.hidden) return;
        try {
            renderClock(await api.clock());
        } catch {
            /* Keep whatever the pill last showed. */
        }
    };

    const start = () => {
        stop();
        quoteTimer = setInterval(tick, QUOTE_POLL_MS);
        clockTimer = setInterval(refreshClock, CLOCK_POLL_MS);
        alertTimer = setInterval(checkAlerts, ALERT_POLL_MS);
    };
    const stop = () => {
        clearInterval(quoteTimer);
        clearInterval(clockTimer);
        clearInterval(alertTimer);
    };

    document.addEventListener('visibilitychange', () => {
        if (document.hidden) {
            stop();
        } else {
            // Catch up immediately rather than waiting a full interval.
            tick();
            refreshClock();
            start();
        }
    });
    start();
}

// ================================================================ shortcuts

function initShortcuts() {
    document.addEventListener('keydown', (event) => {
        if (event.metaKey || event.ctrlKey || event.altKey) return;
        const target = event.target;
        if (target.tagName === 'INPUT' || target.tagName === 'SELECT' || target.tagName === 'TEXTAREA') return;

        // 1-6 jump between chart ranges, matching the pill order.
        const index = Number(event.key) - 1;
        if (state.route.name === 'detail' && index >= 0 && index < RANGES.length) {
            event.preventDefault();
            selectRange(state.route.symbol, RANGES[index]);
            return;
        }
        if (event.key === 'p' && state.route.name !== 'portfolio') {
            event.preventDefault();
            navigate('/portfolio');
        }
    });
}

// ================================================================= bootstrap

async function start() {
    initTheme();
    initRouting();
    initShortcuts();

    const palette = new SearchPalette((symbol) => navigate(`/${symbol}`));
    qs('#search-trigger').addEventListener('click', () => palette.open());

    try {
        state.meta = await api.meta();
        renderClock(state.meta.clock);
    } catch (error) {
        toast(`Cannot reach the server: ${error.message}`, 'error');
    }

    try {
        await loadWatchlists();
    } catch (error) {
        setHtml(qs('#watchlist'), `<p class="rail__empty">${escapeHtml(error.message)}</p>`);
    }

    handleRoute();
    startPolling();
}

start();
