/**
 * Application shell: theme, routing, the watchlist rail, and the two views.
 *
 * State lives in one `state` object and views read from it. There is no
 * framework here on purpose - the app is a rail plus two screens, and a
 * dependency-free page loads instantly and will still run in five years.
 */

import { api, ApiError } from './api.js';
import { Chart } from './chart.js';
import { drawSparkline } from './sparkline.js';
import { SearchPalette } from './palette.js';
import { renderHoldingsView, teardownHoldingsChart } from './holdings.js';
import { toast } from './toast.js';
import { escapeHtml, qs, qsa, setDirectionClass, setHtml, setText } from './dom.js';
import * as fmt from './format.js';

const RANGES = ['1D', '1W', '1M', '3M', '1Y', '5Y'];
const QUOTE_POLL_MS = 15000;
const CLOCK_POLL_MS = 60000;
const ALERT_POLL_MS = 60000;

const state = {
    route: { name: 'detail', symbol: null },
    meta: null,
    watchlists: [],
    activeWatchlistId: null,
    rows: [],
    portfolio: null,
    detail: null,
    range: localStorage.getItem('ticker.range') || '1D',
    chart: null,
    candleRequest: null,
    /** Alert ids already announced, so a fired alert is not re-toasted every poll. */
    announcedAlerts: new Set(),
};

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

function safeStore(key, value) {
    try {
        localStorage.setItem(key, value);
    } catch {
        /* Private browsing; the preference simply will not persist. */
    }
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
    if (route.name === 'portfolio') renderPortfolioView();
    else if (route.name === 'holdings') renderHoldingsView(main);
    else renderDetailView(route.symbol);

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

// ============================================================== detail view

async function renderDetailView(symbol) {
    setHtml(main, detailSkeleton(symbol));

    let detail;
    try {
        detail = await api.stock(symbol);
    } catch (error) {
        setHtml(main, errorPanel(error, symbol));
        return;
    }
    state.detail = detail;

    setHtml(main, detailMarkup(detail));
    bindDetail(detail);
    updateQuoteDisplay(detail.quote);
    await loadCandles(detail.stock.symbol, state.range);
    refreshPortfolioContext();
}

function detailSkeleton(symbol) {
    return `
    <section>
        <p class="hero__eyebrow"><span class="skeleton">Loading company</span></p>
        <h1 class="hero__symbol">${escapeHtml(symbol)}</h1>
        <p class="hero__price skeleton">$000.00</p>
        <div class="chart"><div class="chart__empty">Loading chart…</div></div>
    </section>`;
}

function errorPanel(error, symbol) {
    const notFound = error instanceof ApiError && error.status === 404;
    return `
    <div class="empty">
        <p class="empty__title">${notFound ? `${escapeHtml(symbol)} is not in the database` : 'Something went wrong'}</p>
        <p class="note">${escapeHtml(error.message)}</p>
        ${notFound ? '<p class="note">Try searching for the company name instead.</p>' : ''}
    </div>`;
}

function detailMarkup(detail) {
    const { stock } = detail;
    return `
    <section aria-labelledby="detail-heading">
        <p class="hero__eyebrow">
            <span>${escapeHtml(stock.company)}</span>
            <span class="hero__exchange">${escapeHtml(stock.market)}</span>
        </p>
        <h1 class="hero__symbol" id="detail-heading">${escapeHtml(stock.symbol)}</h1>
        <p class="hero__price" id="d-price">${fmt.EMPTY}</p>
        <p class="hero__change">
            <span id="d-change" class="flat">${fmt.EMPTY}</span>
            <span class="hero__change-label" id="d-change-label"></span>
        </p>

        <div class="hero__actions">
            <button type="button" class="watch-toggle" id="d-watch" aria-pressed="false">
                <span id="d-watch-label">Add to watchlist</span>
            </button>
        </div>

        <div class="chart">
            <canvas class="chart__canvas" id="d-canvas" tabindex="0" role="img"
                    aria-label="Price chart for ${escapeHtml(stock.symbol)}. Use arrow keys to read values."></canvas>
            <div class="chart__tooltip" id="d-tooltip" aria-hidden="true"></div>
            <div class="chart__price-tag" id="d-price-tag" aria-hidden="true"></div>
            <div class="chart__empty" id="d-chart-empty" hidden></div>
        </div>

        <div class="ranges" role="group" aria-label="Chart range">
            ${RANGES.map(
                (range, index) => `
                <button type="button" class="range-pill" data-range="${range}"
                        aria-pressed="${range === state.range}"
                        title="Press ${index + 1}">${range}</button>`,
            ).join('')}
        </div>

        <section class="section">
            <h2 class="section__title">Session</h2>
            <div class="stats" id="d-stats"></div>
            <div class="range-bar" id="d-range-bar" hidden>
                <div class="range-bar__track">
                    <div class="range-bar__marker" id="d-range-marker"></div>
                </div>
                <div class="range-bar__ends">
                    <span id="d-range-low"></span>
                    <span>Day range</span>
                    <span id="d-range-high"></span>
                </div>
            </div>
        </section>

        <div class="cards">
            <div class="card">
                <div class="card__title">
                    <span>Trade</span>
                    <span class="note" id="d-buying-power"></span>
                </div>
                <p class="note" id="d-position"></p>
                <label class="field">
                    <span class="field__label">Shares</span>
                    <input class="input" id="d-quantity" type="number" min="0" step="any"
                           inputmode="decimal" placeholder="0" value="1">
                </label>
                <div class="estimate">
                    <span>Estimated cost</span>
                    <span class="estimate__value" id="d-estimate">${fmt.EMPTY}</span>
                </div>
                <div class="button-row">
                    <button type="button" class="button button--buy" id="d-buy">Buy</button>
                    <button type="button" class="button button--sell" id="d-sell">Sell</button>
                </div>
                <p class="note" style="margin-top:var(--space-3)">
                    Simulated. Fills at the last trade price; nothing is sent to a broker.
                </p>
            </div>

            <div class="card">
                <div class="card__title"><span>Price alert</span></div>
                <div class="field__row">
                    <label class="field">
                        <span class="field__label">When price is</span>
                        <select class="input select" id="d-alert-direction">
                            <option value="ABOVE">Above</option>
                            <option value="BELOW">Below</option>
                        </select>
                    </label>
                    <label class="field">
                        <span class="field__label">Threshold</span>
                        <input class="input" id="d-alert-threshold" type="number" min="0" step="any"
                               inputmode="decimal" placeholder="0.00">
                    </label>
                </div>
                <button type="button" class="button" id="d-alert-create" style="width:100%">Create alert</button>
                <ul id="d-alert-list" style="margin-top:var(--space-4)"></ul>
            </div>
        </div>
    </section>`;
}

function bindDetail(detail) {
    const symbol = detail.stock.symbol;

    state.chart = new Chart(qs('#d-canvas'), {
        tooltip: qs('#d-tooltip'),
        priceTag: qs('#d-price-tag'),
        formatValue: (value) => fmt.price(value),
        onScrub: (info) => {
            if (info) {
                // Two comparisons, because they answer different questions and
                // both get asked. The coloured figure is measured against the
                // range's baseline - that is what the line and the dashed
                // reference actually depict, and it means scrubbing to the far
                // right agrees with the headline instead of collapsing to zero.
                // The trailing note is the distance from today, which is the
                // one a person can check against the price they already know.
                updatePriceLine(info.price, info.change, info.changePercent,
                    `${state.baselineLabel} · ${describeVsToday(info.price)}`);
            } else {
                restoreRangeSummary();
            }
        },
    });

    qsa('.range-pill').forEach((pill) => {
        pill.addEventListener('click', () => selectRange(symbol, pill.dataset.range));
    });

    qs('#d-watch').addEventListener('click', () => toggleWatch(symbol));
    qs('#d-buy').addEventListener('click', () => placeOrder(symbol, 'BUY'));
    qs('#d-sell').addEventListener('click', () => placeOrder(symbol, 'SELL'));
    qs('#d-quantity').addEventListener('input', updateEstimate);
    qs('#d-alert-create').addEventListener('click', () => createAlert(symbol));

    updateWatchButton(detail.watchlistIds ?? []);
    renderPositionNote(detail.position);
    renderAlertList(symbol);
}

function selectRange(symbol, range) {
    if (!RANGES.includes(range)) return;
    state.range = range;
    safeStore('ticker.range', range);
    qsa('.range-pill').forEach((pill) => pill.setAttribute('aria-pressed', String(pill.dataset.range === range)));
    loadCandles(symbol, range);
}

async function loadCandles(symbol, range) {
    state.candleRequest?.abort();
    const controller = new AbortController();
    state.candleRequest = controller;

    const emptyEl = qs('#d-chart-empty');
    try {
        const candles = await api.candles(symbol, range, controller.signal);
        if (controller.signal.aborted || state.route.symbol !== symbol) return;

        if (!candles.points?.length) {
            if (emptyEl) {
                emptyEl.hidden = false;
                emptyEl.textContent = 'No price history available for this range.';
            }
            state.chart?.setData({ points: [], baseline: null, range });
            return;
        }
        if (emptyEl) emptyEl.hidden = true;

        // What every change on this range is measured against. For 1D that is
        // the previous session's close; for anything longer it is the first
        // point in the window, which is a date the user cannot otherwise see.
        state.baselineLabel = range === '1D'
            ? 'vs previous close'
            : `vs ${fmt.axisLabel(candles.points[0].time, range === '1W' ? '1M' : range)}`;

        state.chart?.setData({ points: candles.points, baseline: candles.baseline, range });
        updateRangeChangeLabel(candles, range);

        if (candles.source === 'database') {
            toast('Showing stored daily history — live data is unavailable right now.', 'info');
        }
    } catch (error) {
        if (error.name === 'AbortError') return;
        if (emptyEl) {
            emptyEl.hidden = false;
            emptyEl.textContent = error.message;
        }
    }
}

/**
 * For 1D the header shows today's move against the previous close; for every
 * other range it shows the move across the window, which is what the line
 * actually depicts.
 */
function updateRangeChangeLabel(candles, range) {
    if (range === '1D') {
        updateQuoteDisplay(state.detail?.quote);
        return;
    }
    const points = candles.points;
    const last = points[points.length - 1].close;
    const baseline = Number.isFinite(candles.baseline) ? candles.baseline : points[0].close;
    const change = last - baseline;
    // Not scrubbing: the change spans the whole window, so "Past 5Y" is exact.
    // Remembered so that releasing the cursor restores this rather than the
    // day's move - a 5Y chart that reads "Today +$0.63" the moment you stop
    // hovering is describing a different chart than the one on screen.
    state.rangeSummary = {
        price: last,
        change,
        changePercent: baseline ? (change / baseline) * 100 : null,
        label: `Past ${range}`,
    };
    updatePriceLine(last, change, state.rangeSummary.changePercent, `Past ${range}`);
}

/**
 * How the hovered price sits relative to the current one.
 *
 * <p>Written as "$176.38 below today" rather than a signed number: the sign on
 * the coloured figure beside it already means something else (the move since
 * the baseline), and two differently-signed numbers in one line invites reading
 * one as the other.
 */
function describeVsToday(price) {
    const today = state.detail?.quote?.price;
    if (!Number.isFinite(today) || !Number.isFinite(price)) return '';
    const gap = today - price;
    if (Math.abs(gap) < 0.005) return 'today';
    return `${fmt.usd(Math.abs(gap))} ${gap > 0 ? 'below' : 'above'} today`;
}

/**
 * Puts the header back to whatever the current range was showing before a
 * scrub. On 1D that is the live quote; on any longer range it is the summary
 * for the window, not the day's move.
 */
function restoreRangeSummary() {
    if (state.range === '1D' || !state.rangeSummary) {
        updateQuoteDisplay(state.detail?.quote);
        return;
    }
    const summary = state.rangeSummary;
    updatePriceLine(summary.price, summary.change, summary.changePercent, summary.label);
}

function updateQuoteDisplay(quote) {
    if (!quote) return;
    updatePriceLine(quote.price, quote.change, quote.changePercent, 'Today');
    renderStats(quote);
}

function updatePriceLine(price, change, changePercent, label) {
    setText(qs('#d-price'), fmt.price(price));
    const changeEl = qs('#d-change');
    if (changeEl) {
        const dir = fmt.direction(change);
        setDirectionClass(changeEl, dir);
        const arrow = fmt.arrow(change);
        changeEl.textContent = change === null || change === undefined
            ? fmt.EMPTY
            : `${arrow} ${fmt.signedUsd(change)} (${fmt.signedPercent(changePercent)})`;
    }
    setText(qs('#d-change-label'), label ?? '');
    updateEstimate();
}

function renderStats(quote) {
    const container = qs('#d-stats');
    if (!container) return;

    const stats = [
        ['Open', fmt.price(quote.open)],
        ['High', fmt.price(quote.high)],
        ['Low', fmt.price(quote.low)],
        ['Prev close', fmt.price(quote.previousClose)],
        ['Volume', fmt.abbreviate(quote.volume)],
        ['VWAP', fmt.price(quote.vwap)],
    ];
    container.innerHTML = stats
        .map(
            ([label, value]) => `
            <div class="stat">
                <span class="stat__label">${label}</span>
                <span class="stat__value">${value}</span>
            </div>`,
        )
        .join('');

    // Where the last price sits inside today's range, as a position marker.
    const bar = qs('#d-range-bar');
    if (bar && Number.isFinite(quote.low) && Number.isFinite(quote.high) && quote.high > quote.low) {
        bar.hidden = false;
        const ratio = (quote.price - quote.low) / (quote.high - quote.low);
        qs('#d-range-marker').style.left = `${Math.max(0, Math.min(1, ratio)) * 100}%`;
        setText(qs('#d-range-low'), fmt.price(quote.low));
        setText(qs('#d-range-high'), fmt.price(quote.high));
    } else if (bar) {
        bar.hidden = true;
    }
}

// -------------------------------------------------------------- watch toggle

function updateWatchButton(watchlistIds) {
    const button = qs('#d-watch');
    if (!button) return;
    const watched = watchlistIds.includes(state.activeWatchlistId);
    button.setAttribute('aria-pressed', String(watched));
    setText(qs('#d-watch-label'), watched ? 'In watchlist' : 'Add to watchlist');
}

async function toggleWatch(symbol) {
    const listId = state.activeWatchlistId;
    if (!listId) {
        toast('No watchlist to add to.', 'error');
        return;
    }
    const watched = qs('#d-watch')?.getAttribute('aria-pressed') === 'true';
    try {
        if (watched) await api.removeFromWatchlist(listId, symbol);
        else await api.addToWatchlist(listId, symbol);

        toast(watched ? `${symbol} removed from watchlist.` : `${symbol} added to watchlist.`, 'success');
        updateWatchButton(watched ? [] : [listId]);
        await loadWatchlists();
    } catch (error) {
        toast(error.message, 'error');
    }
}

// -------------------------------------------------------------------- trading

function renderPositionNote(position) {
    const note = qs('#d-position');
    if (!note) return;
    note.textContent = position
        ? `You hold ${fmt.shares(position.quantity)} shares at ${fmt.usd(position.avgCost)} average cost.`
        : 'You do not hold this stock.';
}

function updateEstimate() {
    const input = qs('#d-quantity');
    const estimate = qs('#d-estimate');
    if (!input || !estimate) return;
    const quantity = parseFloat(input.value);
    const price = state.detail?.quote?.price;
    estimate.textContent =
        Number.isFinite(quantity) && quantity > 0 && Number.isFinite(price) ? fmt.usd(quantity * price) : fmt.EMPTY;
}

async function placeOrder(symbol, side) {
    const input = qs('#d-quantity');
    const quantity = parseFloat(input?.value);
    if (!Number.isFinite(quantity) || quantity <= 0) {
        toast('Enter how many shares to trade.', 'error');
        input?.focus();
        return;
    }

    const buttons = [qs('#d-buy'), qs('#d-sell')];
    buttons.forEach((button) => button && (button.disabled = true));
    try {
        const result = await api.order(symbol, side, quantity);
        state.portfolio = result.portfolio;
        toast(
            `${side === 'BUY' ? 'Bought' : 'Sold'} ${fmt.shares(result.trade.quantity)} ${symbol} at ${fmt.usd(result.trade.price)}.`,
            'success',
        );
        const held = result.portfolio.holdings.find((holding) => holding.symbol === symbol);
        renderPositionNote(held ? { quantity: held.quantity, avgCost: held.avgCost } : null);
        renderBuyingPower();
    } catch (error) {
        toast(error.message, 'error');
    } finally {
        buttons.forEach((button) => button && (button.disabled = false));
    }
}

async function refreshPortfolioContext() {
    try {
        state.portfolio = await api.portfolio();
        renderBuyingPower();
    } catch {
        /* The trade card degrades to hiding buying power; not worth a toast. */
    }
}

function renderBuyingPower() {
    setText(qs('#d-buying-power'), state.portfolio ? `${fmt.usdCompact(state.portfolio.cash)} available` : '');
}

// --------------------------------------------------------------------- alerts

async function createAlert(symbol) {
    const direction = qs('#d-alert-direction')?.value;
    const threshold = parseFloat(qs('#d-alert-threshold')?.value);
    if (!Number.isFinite(threshold) || threshold <= 0) {
        toast('Enter a price for the alert.', 'error');
        return;
    }
    try {
        await api.createAlert(symbol, direction, threshold);
        toast(`Alert set: ${symbol} ${direction.toLowerCase()} ${fmt.usd(threshold)}.`, 'success');
        qs('#d-alert-threshold').value = '';
        renderAlertList(symbol);
    } catch (error) {
        toast(error.message, 'error');
    }
}

async function renderAlertList(symbol) {
    const list = qs('#d-alert-list');
    if (!list) return;
    try {
        const alerts = (await api.alerts()).filter((alert) => alert.symbol === symbol);
        list.innerHTML = alerts.length === 0
            ? ''
            : alerts
                  .map(
                      (alert) => `
                    <li class="estimate">
                        <span>${alert.triggeredAt ? '✓ ' : ''}${escapeHtml(alert.direction.toLowerCase())}
                              ${fmt.usd(alert.threshold)}</span>
                        <button type="button" class="button button--small button--ghost"
                                data-alert="${alert.id}">Remove</button>
                    </li>`,
                  )
                  .join('');

        list.querySelectorAll('[data-alert]').forEach((button) => {
            button.addEventListener('click', async () => {
                await api.deleteAlert(Number(button.dataset.alert));
                renderAlertList(symbol);
            });
        });
    } catch {
        /* Alerts are supplementary; a failure here should not break the page. */
    }
}

// ============================================================ portfolio view

async function renderPortfolioView() {
    setHtml(main, '<div class="empty"><p class="empty__title">Loading portfolio</p></div>');

    let summary;
    try {
        summary = await api.portfolio();
    } catch (error) {
        setHtml(main, errorPanel(error, ''));
        return;
    }
    state.portfolio = summary;

    const [history, trades] = await Promise.all([
        api.portfolioHistory('1Y').catch(() => ({ points: [] })),
        api.trades(25).catch(() => []),
    ]);

    setHtml(main, portfolioMarkup(summary, trades));

    const canvas = qs('#p-canvas');
    const emptyEl = qs('#p-chart-empty');
    if (!(history.points?.length > 1)) {
        // Distinguish "nothing has happened yet" from "something happened, but
        // the curve needs a closed trading day before it can plot anything".
        emptyEl.textContent = trades.length === 0
            ? 'Your value chart appears here once you have made a trade.'
            : 'Your value chart fills in after the first full trading day since your earliest trade.';
    }
    if (history.points?.length > 1) {
        emptyEl.hidden = true;
        state.chart = new Chart(canvas, {
            tooltip: qs('#p-tooltip'),
            priceTag: qs('#p-price-tag'),
            formatValue: (value) => fmt.usdCompact(value),
            onScrub: (info) => {
                if (info) {
                    setText(qs('#p-total'), fmt.usd(info.price));
                } else {
                    setText(qs('#p-total'), fmt.usd(summary.totalValue));
                }
            },
        });
        state.chart.setData({
            // The history endpoint returns {time, value}; the chart reads `close`.
            points: history.points.map((point) => ({ time: point.time, close: Number(point.value) })),
            baseline: Number(summary.startingCash),
            range: '1Y',
        });
    }

    qsa('[data-holding]').forEach((row) => {
        row.addEventListener('click', () => navigate(`/${row.dataset.holding}`));
    });
    qs('#p-reset')?.addEventListener('click', resetPortfolio);
}

function portfolioMarkup(summary, trades) {
    const dayDir = fmt.direction(summary.dayChange);
    const totalDir = fmt.direction(summary.totalPnl);

    return `
    <section aria-labelledby="portfolio-heading">
        ${summary.stale ? '<p class="banner">Some positions could not be priced, so totals are approximate.</p>' : ''}

        <p class="hero__eyebrow">Paper portfolio</p>
        <h1 class="hero__symbol" id="portfolio-heading">Portfolio</h1>
        <p class="hero__price" id="p-total">${fmt.usd(summary.totalValue)}</p>
        <p class="hero__change">
            <span class="${dayDir}">${fmt.arrow(summary.dayChange)} ${fmt.signedUsd(summary.dayChange)}
                (${fmt.signedPercent(summary.dayChangePercent)})</span>
            <span class="hero__change-label">Today</span>
        </p>

        <div class="chart">
            <canvas class="chart__canvas" id="p-canvas" tabindex="0" role="img"
                    aria-label="Portfolio value over the past year"></canvas>
            <div class="chart__tooltip" id="p-tooltip" aria-hidden="true"></div>
            <div class="chart__price-tag" id="p-price-tag" aria-hidden="true"></div>
            <div class="chart__empty" id="p-chart-empty">
                Your value chart appears here once you have made a trade.
            </div>
        </div>

        <div class="summary">
            <div class="summary__item">
                <p class="summary__label">Buying power</p>
                <p class="summary__value">${fmt.usdCompact(summary.cash)}</p>
            </div>
            <div class="summary__item">
                <p class="summary__label">Holdings value</p>
                <p class="summary__value">${fmt.usdCompact(summary.marketValue)}</p>
            </div>
            <div class="summary__item">
                <p class="summary__label">All-time return</p>
                <p class="summary__value ${totalDir}">${fmt.signedPercent(summary.totalPnlPercent)}</p>
            </div>
            <div class="summary__item">
                <p class="summary__label">Realised P&amp;L</p>
                <p class="summary__value ${fmt.direction(summary.realizedPnl)}">${fmt.signedUsd(summary.realizedPnl)}</p>
            </div>
        </div>

        <section class="section">
            <h2 class="section__title">Holdings</h2>
            ${summary.holdings.length === 0
                ? '<p class="note">No positions yet. Open a stock and place a simulated buy.</p>'
                : `<div class="table__wrap"><table class="table">
                    <thead><tr>
                        <th>Symbol</th><th class="num">Shares</th><th class="num">Avg cost</th>
                        <th class="num">Price</th><th class="num">Value</th><th class="num">Today</th>
                        <th class="num">Total P&amp;L</th><th class="num">Weight</th>
                    </tr></thead>
                    <tbody>${summary.holdings.map(holdingRow).join('')}</tbody>
                  </table></div>`}
        </section>

        <section class="section">
            <h2 class="section__title">Recent activity</h2>
            ${trades.length === 0
                ? '<p class="note">No trades recorded.</p>'
                : `<div class="table__wrap"><table class="table">
                    <thead><tr>
                        <th>When</th><th>Side</th><th>Symbol</th>
                        <th class="num">Shares</th><th class="num">Price</th><th class="num">Amount</th>
                    </tr></thead>
                    <tbody>${trades.map(tradeRow).join('')}</tbody>
                  </table></div>`}
        </section>

        <section class="section">
            <button type="button" class="button button--small button--ghost" id="p-reset">
                Reset portfolio to ${fmt.usdCompact(summary.startingCash)}
            </button>
        </section>
    </section>`;
}

function holdingRow(holding) {
    return `
    <tr data-holding="${escapeHtml(holding.symbol)}" tabindex="0">
        <td><strong>${escapeHtml(holding.symbol)}</strong></td>
        <td class="num">${fmt.shares(holding.quantity)}</td>
        <td class="num">${fmt.usd(holding.avgCost)}</td>
        <td class="num">${fmt.price(holding.lastPrice)}</td>
        <td class="num">${fmt.usd(holding.marketValue)}</td>
        <td class="num ${fmt.direction(holding.dayChange)}">${fmt.signedUsd(holding.dayChange)}</td>
        <td class="num ${fmt.direction(holding.unrealizedPnl)}">
            ${fmt.signedUsd(holding.unrealizedPnl)} (${fmt.signedPercent(holding.unrealizedPnlPercent)})
        </td>
        <td class="num">${fmt.percent(holding.weight)}</td>
    </tr>`;
}

function tradeRow(trade) {
    return `
    <tr>
        <td>${fmt.dateTime(trade.executedAt)}</td>
        <td><span class="side-chip" data-side="${escapeHtml(trade.side)}">${escapeHtml(trade.side)}</span></td>
        <td><strong>${escapeHtml(trade.symbol)}</strong></td>
        <td class="num">${fmt.shares(trade.quantity)}</td>
        <td class="num">${fmt.usd(trade.price)}</td>
        <td class="num ${fmt.direction(trade.amount)}">${fmt.signedUsd(trade.amount)}</td>
    </tr>`;
}

async function resetPortfolio() {
    if (!window.confirm('Delete every simulated trade and restore the opening cash balance?')) return;
    try {
        state.portfolio = await api.resetPortfolio();
        toast('Portfolio reset.', 'success');
        renderPortfolioView();
    } catch (error) {
        toast(error.message, 'error');
    }
}

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
                    state.detail.quote = quote;
                    if (state.range === '1D') updateQuoteDisplay(quote);
                    else renderStats(quote);
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
