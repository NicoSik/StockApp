/**
 * The multi-broker aggregator view.
 *
 * Separate from the simulated portfolio on purpose: these are real holdings
 * imported from DNB and Nordnet, valued in NOK.
 *
 * The screen has one job the others do not: being honest about how each number
 * was arrived at. Roughly two thirds of a Norwegian portfolio can be priced
 * live; the rest are mutual funds with no free price source, carried at the
 * value the broker last reported. That split is shown rather than blurred.
 */

import { api } from './api.js';
import { Chart } from './chart.js';
import { escapeHtml, qs, qsa, setHtml } from './dom.js';
import { toast } from './toast.js';
import * as fmt from './format.js';

const nok0 = new Intl.NumberFormat('nb-NO', {
    style: 'currency', currency: 'NOK', minimumFractionDigits: 0, maximumFractionDigits: 0,
});
const nok2 = new Intl.NumberFormat('nb-NO', {
    style: 'currency', currency: 'NOK', minimumFractionDigits: 2, maximumFractionDigits: 2,
});

const kr = (value, precise = false) => {
    const n = Number(value);
    if (!Number.isFinite(n)) return fmt.EMPTY;
    return precise ? nok2.format(n) : nok0.format(n);
};

/** State for an in-progress import, held only while the dialog is open. */
let preview = null;
/** Row index to the symbol the user picked by hand. */
let overrides = {};
/** Row indices the user chose to leave out. */
let skipped = new Set();
/** Which row currently has its search panel open, if any. */
let fixingRow = null;
let searchTimer = null;

let chart = null;

/** Called by the router before leaving, so the canvas listeners do not leak. */
export function teardownHoldingsChart() {
    chart?.destroy();
    chart = null;
}

export async function renderHoldingsView(main) {
    teardownHoldingsChart();
    setHtml(main, '<div class="empty"><p class="empty__title">Loading holdings</p></div>');

    let data;
    let history = { points: [] };
    try {
        [data, history] = await Promise.all([
            api.holdings(),
            api.holdingsHistory().catch(() => ({ points: [] })),
        ]);
    } catch (error) {
        setHtml(main, `<div class="empty"><p class="empty__title">Could not load holdings</p>
            <p class="note">${escapeHtml(error.message)}</p></div>`);
        return;
    }

    setHtml(main, markup(data, history));
    mountChart(data, history);
    bind(main);
}

// ==================================================================== markup

function markup(data, history) {
    const empty = !data.holdings || data.holdings.length === 0;
    const gainDir = fmt.direction(data.gainNok);

    return `
    <section aria-labelledby="holdings-heading">
        <p class="hero__eyebrow">Across your brokers</p>
        <h1 class="hero__symbol" id="holdings-heading">Holdings</h1>
        <p class="hero__price" id="h-total">${kr(data.totalNok)}</p>
        <p class="hero__change">
            ${data.gainNok !== null && data.gainNok !== undefined
                ? `<span class="${gainDir}">${fmt.arrow(data.gainNok)} ${kr(data.gainNok)}</span>
                   <span class="hero__change-label" id="h-total-label">where cost basis is known</span>`
                : '<span class="hero__change-label" id="h-total-label">Combined value in NOK</span>'}
        </p>

        ${empty ? '' : freshnessBanner(data)}

        <div class="hero__actions">
            <button type="button" class="button button--small" id="h-import">Import a broker export</button>
            <button type="button" class="button button--small button--ghost" id="h-refresh">Refresh prices</button>
        </div>

        ${empty ? emptyState() : `
            <div class="chart" style="height:220px">
                <canvas class="chart__canvas" id="h-canvas" tabindex="0" role="img"
                        aria-label="Combined portfolio value over time. Use arrow keys to read values."></canvas>
                <div class="chart__tooltip" id="h-tooltip" aria-hidden="true"></div>
                <div class="chart__price-tag" id="h-price-tag" aria-hidden="true"></div>
                <div class="chart__empty" id="h-chart-empty"${history.points?.length > 1 ? ' hidden' : ''}>
                    ${history.points?.length > 1 ? '' : 'Your value chart builds up as you import over time — one point per import.'}
                </div>
            </div>

            <div class="summary">
                ${data.accounts.filter(a => a.holdingCount > 0).map(accountCard).join('')}
            </div>

            <section class="section">
                <h2 class="section__title">Holdings</h2>
                <div class="table__wrap"><table class="table">
                    <thead><tr>
                        <th>Instrument</th><th>Account</th><th class="num">Quantity</th>
                        <th class="num">Price</th><th class="num">Value</th>
                        <th class="num">Gain</th><th class="num">Weight</th><th>Priced</th>
                    </tr></thead>
                    <tbody>${data.holdings.map(holdingRow).join('')}</tbody>
                </table></div>
            </section>
        `}

        <div class="palette-backdrop" id="h-import-backdrop" hidden>
            <div class="palette" role="dialog" aria-modal="true" aria-label="Import a broker export"
                 style="width:min(920px, calc(100vw - var(--space-6)))">
                <div id="h-import-body"></div>
            </div>
        </div>
    </section>`;
}

/**
 * Says plainly how much of the total is live and how much is carried from the
 * last import, and when that was.
 */
function freshnessBanner(data) {
    const live = Number(data.livePercent) || 0;
    if (live >= 99.95) {
        return '<p class="note" style="margin-top:var(--space-3)">Everything priced live.</p>';
    }
    return `<p class="banner" style="margin-top:var(--space-4)">
        <span>${live.toFixed(0)}% priced live · ${kr(data.asOfNok)} carried from your last import${
            data.oldestAsOf ? ` (${escapeHtml(data.oldestAsOf)})` : ''
        }. Anything without a verified match keeps the value your broker reported.</span>
    </p>`;
}

function accountCard(account) {
    return `
    <div class="summary__item">
        <p class="summary__label">${escapeHtml(account.name)}</p>
        <p class="summary__value">${kr(account.valueNok)}</p>
        <p class="note">${account.holdingCount} holdings${
            account.asOf ? ` · as of ${escapeHtml(account.asOf)}` : ''}</p>
    </div>`;
}

function holdingRow(holding) {
    const gainDir = fmt.direction(holding.gainNok);
    return `
    <tr>
        <td>
            <strong>${escapeHtml(holding.symbol || holding.name)}</strong>
            ${holding.symbol ? `<br><span class="note">${escapeHtml(holding.name)}</span>` : ''}
        </td>
        <td class="note">${escapeHtml(holding.accountName)}</td>
        <td class="num">${fmt.shares(holding.quantity)}</td>
        <td class="num">${holding.price !== null && holding.price !== undefined
            ? `${Number(holding.price).toFixed(2)} ${escapeHtml(holding.currency || '')}` : fmt.EMPTY}</td>
        <td class="num">${kr(holding.valueNok, true)}</td>
        <td class="num ${gainDir}">${holding.gainNok !== null && holding.gainNok !== undefined
            ? `${kr(holding.gainNok)} (${fmt.signedPercent(holding.gainPercent)})` : fmt.EMPTY}</td>
        <td class="num">${fmt.percent(holding.weight)}</td>
        <td>${holding.live
            ? '<span class="side-chip" data-side="BUY">live</span>'
            : '<span class="side-chip" data-side="SELL">as of import</span>'}</td>
    </tr>`;
}

function emptyState() {
    return `
    <div class="empty">
        <p class="empty__title">No holdings imported yet</p>
        <p class="note">Export your holdings from Nordnet (Aksjelister) or DNB (Beholdning)
           and drop the file in. Nothing leaves your machine.</p>
    </div>`;
}

// ===================================================================== chart

/**
 * Plots combined value per snapshot date.
 *
 * <p>Two points are enough for a line, and two points is what you have after a
 * second import - so the chart earns its place immediately rather than after
 * weeks of history.
 */
function mountChart(data, history) {
    const canvas = qs('#h-canvas');
    const points = (history.points ?? [])
        // Anchored to midday UTC, not midnight. A snapshot carries a calendar
        // date, but the chart's labels are formatted in market time - and
        // "2026-08-11" parsed as UTC midnight is the evening of the 10th in New
        // York, so every point would display a day early. Midday leaves no
        // timezone able to shift the date either way.
        .map((p) => ({ time: Date.parse(`${p.date}T12:00:00Z`), close: Number(p.value) }))
        .filter((p) => Number.isFinite(p.time) && Number.isFinite(p.close));

    if (!canvas || points.length < 2) return;

    chart = new Chart(canvas, {
        tooltip: qs('#h-tooltip'),
        priceTag: qs('#h-price-tag'),
        formatValue: (value) => kr(value),
        onScrub: (info) => {
            const total = qs('#h-total');
            const label = qs('#h-total-label');
            if (info) {
                if (total) total.textContent = kr(info.price);
                if (label) label.textContent = 'on this date';
            } else {
                if (total) total.textContent = kr(data.totalNok);
                if (label) label.textContent = data.gainNok !== null && data.gainNok !== undefined
                    ? 'where cost basis is known' : 'Combined value in NOK';
            }
        },
    });
    chart.setData({ points, baseline: points[0].close, range: '1Y' });
}

// =============================================================== interaction

function bind(main) {
    qs('#h-import', main)?.addEventListener('click', openImport);
    qs('#h-refresh', main)?.addEventListener('click', async () => {
        toast('Refreshing prices…', 'info');
        await renderHoldingsView(main);
    });

    const backdrop = qs('#h-import-backdrop', main);
    backdrop?.addEventListener('pointerdown', (event) => {
        if (event.target === backdrop) closeImport();
    });
    document.addEventListener('keydown', escapeToClose);
}

function escapeToClose(event) {
    if (event.key !== 'Escape') return;
    // Close the row search first, so Escape does not discard the whole import
    // just because a search box happened to be open.
    if (fixingRow !== null) {
        fixingRow = null;
        renderPreviewTable();
        return;
    }
    closeImport();
}

function openImport() {
    const backdrop = qs('#h-import-backdrop');
    if (!backdrop) return;
    backdrop.hidden = false;
    preview = null;
    overrides = {};
    skipped = new Set();
    fixingRow = null;
    setHtml(qs('#h-import-body'), uploadMarkup());

    const input = qs('#h-file');
    input?.addEventListener('change', () => {
        if (input.files?.[0]) uploadFile(input.files[0]);
    });

    const drop = qs('#h-drop');
    ['dragenter', 'dragover'].forEach((type) =>
        drop?.addEventListener(type, (e) => { e.preventDefault(); drop.dataset.active = 'true'; }));
    ['dragleave', 'drop'].forEach((type) =>
        drop?.addEventListener(type, (e) => { e.preventDefault(); drop.dataset.active = 'false'; }));
    drop?.addEventListener('drop', (e) => {
        const file = e.dataTransfer?.files?.[0];
        if (file) uploadFile(file);
    });
}

function closeImport() {
    const backdrop = qs('#h-import-backdrop');
    if (backdrop) backdrop.hidden = true;
    preview = null;
    overrides = {};
    skipped = new Set();
    fixingRow = null;
    clearTimeout(searchTimer);
    document.removeEventListener('keydown', escapeToClose);
}

function uploadMarkup() {
    return `
    <div style="padding:var(--space-5)">
        <h2 class="card__title"><span>Import a broker export</span></h2>
        <div id="h-drop" class="empty" data-active="false"
             style="border:2px dashed var(--border-strong); border-radius:var(--radius-lg); padding:var(--space-6)">
            <p class="empty__title">Drop your export here</p>
            <p class="note">Nordnet <code>Aksjelister</code> (.csv) or DNB <code>Beholdning</code> (.xlsx)</p>
            <p style="margin-top:var(--space-4)">
                <label class="button button--small" style="cursor:pointer">
                    Choose a file<input type="file" id="h-file" accept=".csv,.xlsx,.xls" hidden>
                </label>
            </p>
        </div>
        <p class="note" style="margin-top:var(--space-4)">
            The file is read on your machine and never uploaded anywhere else.
        </p>
    </div>`;
}

async function uploadFile(file) {
    setHtml(qs('#h-import-body'),
        '<div class="empty"><p class="empty__title">Reading…</p><p class="note">Matching holdings against live prices.</p></div>');
    try {
        preview = await api.previewImport(file);
        overrides = {};
        skipped = new Set();
        fixingRow = null;
        setHtml(qs('#h-import-body'), previewShell());
        renderPreviewTable();
    } catch (error) {
        setHtml(qs('#h-import-body'), `
            <div style="padding:var(--space-5)">
                <h2 class="card__title"><span>That file could not be imported</span></h2>
                <p class="banner">${escapeHtml(error.message)}</p>
                <button type="button" class="button button--small" id="h-back">Try another file</button>
            </div>`);
        qs('#h-back')?.addEventListener('click', openImport);
    }
}

/** The parts of the preview dialog that do not change as rows are edited. */
function previewShell() {
    return `
    <div style="padding:var(--space-5); max-height:80vh; overflow:auto">
        <h2 class="card__title">
            <span>${escapeHtml(preview.accountName)} · ${preview.rows.length} holdings · ${kr(preview.totalNok)}</span>
        </h2>
        <div id="h-preview-status"></div>
        <div class="table__wrap" style="margin-top:var(--space-4)">
            <table class="table">
                <thead><tr>
                    <th>From your file</th><th class="num">Qty</th><th class="num">Your price</th>
                    <th>Matched to</th><th class="num">Live price</th><th>Status</th><th></th>
                </tr></thead>
                <tbody id="h-preview-rows"></tbody>
            </table>
        </div>
        <div class="button-row" style="margin-top:var(--space-5)">
            <button type="button" class="button button--ghost" id="h-cancel">Cancel</button>
            <button type="button" class="button button--buy" id="h-commit"></button>
        </div>
    </div>`;
}

/**
 * Redraws the row table and the summary line.
 *
 * <p>Rebuilt wholesale on every change rather than patched in place: a preview
 * is at most a few dozen rows, and a single render path cannot drift out of
 * sync with the overrides it is displaying.
 */
function renderPreviewTable() {
    const rows = qs('#h-preview-rows');
    if (!rows || !preview) return;

    rows.innerHTML = preview.rows.map(previewRow).join('');

    const importing = preview.rows.length - skipped.size;
    const outstanding = preview.rows.filter(needsAttention).length;
    const fixed = Object.keys(overrides).length;

    setHtml(qs('#h-preview-status'), outstanding === 0
        ? `<p class="note">Everything matched${fixed ? ` (${fixed} fixed by you)` : ''} and was verified
           against the price in your file. Nothing needs your attention.</p>`
        : `<p class="banner">${outstanding} ${outstanding === 1 ? 'row needs' : 'rows need'} a look — the price
           disagreed with your file, or nothing was found. Fix them, skip them, or import anyway: anything
           unmatched is still imported, just carried at the value your broker reported rather than priced live.</p>`);

    const commit = qs('#h-commit');
    if (commit) {
        commit.textContent = skipped.size
            ? `Import ${importing} holdings (${skipped.size} skipped)`
            : `Import ${importing} holdings`;
        commit.disabled = importing === 0;
    }

    bindPreviewRows();
}

/** A row still wanting attention: flagged, not overridden, not skipped. */
function needsAttention(row) {
    return row.status !== 'CONFIRMED'
        && !(row.index in overrides)
        && !skipped.has(row.index);
}

function previewRow(row) {
    const chosen = overrides[row.index];
    const isSkipped = skipped.has(row.index);
    const resolvedSymbol = chosen ?? row.symbol;

    let tone = 'BUY';
    let label = 'matched';
    if (isSkipped) {
        tone = 'SELL';
        label = 'skipped';
    } else if (chosen) {
        tone = 'BUY';
        label = 'you chose';
    } else if (row.status === 'NEEDS_REVIEW') {
        tone = 'SELL';
        label = 'check';
    } else if (row.status === 'UNRESOLVED') {
        tone = 'SELL';
        label = 'no match';
    }

    const actions = row.status === 'CONFIRMED' && !chosen
        ? ''
        : `<button type="button" class="button button--small button--ghost" data-fix="${row.index}">
               ${fixingRow === row.index ? 'Close' : 'Fix'}</button>
           <button type="button" class="button button--small button--ghost" data-skip="${row.index}">
               ${isSkipped ? 'Include' : 'Skip'}</button>`;

    const searchPanel = fixingRow === row.index ? `
        <tr data-search-for="${row.index}">
            <td colspan="7" style="background:var(--surface)">
                <label class="field" style="margin:0">
                    <span class="field__label">Search for the right instrument
                        ${row.currency ? `(${escapeHtml(row.currency)} listings)` : ''}</span>
                    <input class="input" id="h-search-input" type="text" autocomplete="off"
                           placeholder="Ticker or company name, e.g. LIDR"
                           value="${escapeHtml(row.name || '')}">
                </label>
                <div id="h-search-results" class="note">Type to search.</div>
            </td>
        </tr>` : '';

    return `
    <tr style="${isSkipped ? 'opacity:.45' : ''}">
        <td><strong>${escapeHtml(row.name)}</strong>
            ${row.knownAlias ? '<br><span class="note">already mapped</span>' : ''}</td>
        <td class="num">${fmt.shares(row.quantity)}</td>
        <td class="num">${row.lastPrice !== null && row.lastPrice !== undefined
            ? Number(row.lastPrice).toFixed(2) : fmt.EMPTY} ${escapeHtml(row.currency || '')}</td>
        <td>${resolvedSymbol ? `<strong>${escapeHtml(resolvedSymbol)}</strong>` : fmt.EMPTY}
            ${!chosen && row.note ? `<br><span class="note">${escapeHtml(row.note)}</span>` : ''}</td>
        <td class="num">${row.livePrice !== null && row.livePrice !== undefined
            ? Number(row.livePrice).toFixed(2) : fmt.EMPTY}</td>
        <td><span class="side-chip" data-side="${tone}">${label}</span></td>
        <td style="white-space:nowrap">${actions}</td>
    </tr>${searchPanel}`;
}

function bindPreviewRows() {
    qsa('[data-fix]').forEach((button) => button.addEventListener('click', () => {
        const index = Number(button.dataset.fix);
        fixingRow = fixingRow === index ? null : index;
        renderPreviewTable();
        if (fixingRow !== null) {
            const input = qs('#h-search-input');
            input?.focus();
            input?.select();
            runSearch();
        }
    }));

    qsa('[data-skip]').forEach((button) => button.addEventListener('click', () => {
        const index = Number(button.dataset.skip);
        if (skipped.has(index)) {
            skipped.delete(index);
        } else {
            skipped.add(index);
            delete overrides[index];
            if (fixingRow === index) fixingRow = null;
        }
        renderPreviewTable();
    }));

    qs('#h-search-input')?.addEventListener('input', () => {
        clearTimeout(searchTimer);
        searchTimer = setTimeout(runSearch, 250);
    });

    qs('#h-cancel')?.addEventListener('click', closeImport);
    qs('#h-commit')?.addEventListener('click', commit);
}

async function runSearch() {
    const input = qs('#h-search-input');
    const results = qs('#h-search-results');
    if (!input || !results || fixingRow === null) return;

    const row = preview.rows.find((r) => r.index === fixingRow);
    const query = input.value.trim();
    if (!query) {
        results.textContent = 'Type to search.';
        return;
    }
    results.textContent = 'Searching…';

    try {
        const matches = await api.lookupInstrument(query, row?.currency);
        if (fixingRow === null) return;
        if (!matches.length) {
            results.innerHTML = `<span class="note">Nothing found${
                row?.currency ? ` on a ${escapeHtml(row.currency)} market` : ''}.</span>`;
            return;
        }
        results.innerHTML = matches.map((match) => `
            <button type="button" class="palette__item" data-pick="${escapeHtml(match.symbol)}"
                    style="border-radius:var(--radius-sm)">
                <span class="palette__symbol">${escapeHtml(match.symbol)}</span>
                <span class="palette__company">${escapeHtml(match.name || '')}</span>
                <span class="palette__market">${match.price !== undefined
                    ? `${Number(match.price).toFixed(2)} ${escapeHtml(match.currency || '')}` : ''}</span>
            </button>`).join('');

        qsa('[data-pick]', results).forEach((button) => button.addEventListener('click', () => {
            overrides[fixingRow] = button.dataset.pick;
            skipped.delete(fixingRow);
            fixingRow = null;
            renderPreviewTable();
        }));
    } catch (error) {
        results.innerHTML = `<span class="note">${escapeHtml(error.message)}</span>`;
    }
}

async function commit() {
    const button = qs('#h-commit');
    if (button) button.disabled = true;
    try {
        const result = await api.commitImport(preview.id, overrides, [...skipped]);
        const { imported, skipped: skippedCount, accountName } = result.result;
        toast(`Imported ${imported} holdings from ${accountName}${
            skippedCount ? `, ${skippedCount} skipped` : ''}.`, 'success');
        closeImport();
        await renderHoldingsView(qs('#main'));
    } catch (error) {
        toast(error.message, 'error');
        if (button) button.disabled = false;
    }
}
