/**
 * The paper portfolio view: value curve, summary, positions and trade log.
 *
 * Simulated money only. Real holdings live in holdings.js and never mix with
 * these figures.
 */

import { api } from './api.js';
import { Chart } from './chart.js';
import { toast } from './toast.js';
import { errorPanel, escapeHtml, qs, qsa, setHtml, setText } from './dom.js';
import { state } from './state.js';
import * as fmt from './format.js';

export async function renderPortfolioView(main, { navigate }) {
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
    qs('#p-reset')?.addEventListener('click', () => resetPortfolio(main, { navigate }));
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

async function resetPortfolio(main, options) {
    if (!window.confirm('Delete every simulated trade and restore the opening cash balance?')) return;
    try {
        state.portfolio = await api.resetPortfolio();
        toast('Portfolio reset.', 'success');
        renderPortfolioView(main, options);
    } catch (error) {
        toast(error.message, 'error');
    }
}
