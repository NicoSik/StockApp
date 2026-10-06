/**
 * The stock detail view: price header, chart, session stats, the simulated
 * trade card and price alerts.
 */

import { api } from './api.js';
import { Chart } from './chart.js';
import { toast } from './toast.js';
import { errorPanel, escapeHtml, qs, qsa, setDirectionClass, setHtml, setText } from './dom.js';
import { RANGES, safeStore, state } from './state.js';
import * as fmt from './format.js';

/** What changes on the current range are measured against, e.g. "vs previous close". */
let baselineLabel = '';
/** The header for a non-1D range while not scrubbing; see updateRangeChangeLabel. */
let rangeSummary = null;
/** Called after the watch toggle changes a list, so the shell can refresh the rail. */
let watchlistChanged = null;

/**
 * Applies a polled quote to the open detail view.
 *
 * On 1D the header follows the quote; on longer ranges the header describes
 * the window, so only the session stats update.
 */
export function applyLiveQuote(quote) {
    state.detail.quote = quote;
    if (state.range === '1D') updateQuoteDisplay(quote);
    else renderStats(quote);
}

export async function renderDetailView(main, symbol, { onWatchlistChanged } = {}) {
    watchlistChanged = onWatchlistChanged ?? null;
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
                    `${baselineLabel} · ${describeVsToday(info.price)}`);
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

export function selectRange(symbol, range) {
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
        baselineLabel = range === '1D'
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
    rangeSummary = {
        price: last,
        change,
        changePercent: baseline ? (change / baseline) * 100 : null,
        label: `Past ${range}`,
    };
    updatePriceLine(last, change, rangeSummary.changePercent, `Past ${range}`);
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
    if (state.range === '1D' || !rangeSummary) {
        updateQuoteDisplay(state.detail?.quote);
        return;
    }
    const summary = rangeSummary;
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
        await watchlistChanged?.();
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
