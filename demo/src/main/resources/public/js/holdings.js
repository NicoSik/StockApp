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

export async function renderHoldingsView(main) {
    setHtml(main, '<div class="empty"><p class="empty__title">Loading holdings</p></div>');

    let data;
    try {
        data = await api.holdings();
    } catch (error) {
        setHtml(main, `<div class="empty"><p class="empty__title">Could not load holdings</p>
            <p class="note">${escapeHtml(error.message)}</p></div>`);
        return;
    }

    setHtml(main, markup(data));
    bind(main);
}

function markup(data) {
    const empty = !data.holdings || data.holdings.length === 0;
    const gainDir = fmt.direction(data.gainNok);

    return `
    <section aria-labelledby="holdings-heading">
        <p class="hero__eyebrow">Across your brokers</p>
        <h1 class="hero__symbol" id="holdings-heading">Holdings</h1>
        <p class="hero__price">${kr(data.totalNok)}</p>
        <p class="hero__change">
            ${data.gainNok !== null && data.gainNok !== undefined
                ? `<span class="${gainDir}">${fmt.arrow(data.gainNok)} ${kr(data.gainNok)}</span>
                   <span class="hero__change-label">where cost basis is known</span>`
                : '<span class="hero__change-label">Combined value in NOK</span>'}
        </p>

        ${empty ? '' : freshnessBanner(data)}

        <div class="hero__actions">
            <button type="button" class="button button--small" id="h-import">Import a broker export</button>
            <button type="button" class="button button--small button--ghost" id="h-refresh">Refresh prices</button>
        </div>

        ${empty ? emptyState() : `
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
                 style="width:min(880px, calc(100vw - var(--space-6)))">
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
    if (live >= 99.5) {
        return `<p class="note" style="margin-top:var(--space-3)">
            Everything priced live.</p>`;
    }
    return `<p class="banner" style="margin-top:var(--space-4)">
        <span>${live.toFixed(0)}% priced live · ${kr(data.asOfNok)} carried from your last import${
            data.oldestAsOf ? ` (${escapeHtml(data.oldestAsOf)})` : ''
        }. Norwegian funds have no free price feed, so they hold their imported value.</span>
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

// ================================================================ interaction

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
    if (event.key === 'Escape') closeImport();
}

function openImport() {
    const backdrop = qs('#h-import-backdrop');
    if (!backdrop) return;
    backdrop.hidden = false;
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
        setHtml(qs('#h-import-body'), previewMarkup(preview));
        bindPreview();
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

function previewMarkup(p) {
    const problems = p.needsReview + p.unresolved;
    return `
    <div style="padding:var(--space-5); max-height:78vh; overflow:auto">
        <h2 class="card__title">
            <span>${escapeHtml(p.accountName)} · ${p.rows.length} holdings · ${kr(p.totalNok)}</span>
        </h2>

        ${problems === 0
            ? `<p class="note">All ${p.confirmed} holdings matched and were verified against the
               price in your file. Nothing needs your attention.</p>`
            : `<p class="banner">${p.confirmed} matched automatically. ${problems} need a look —
               either the price disagreed with your file, or nothing was found. Anything left
               unmatched is still imported, just carried at the value your broker reported.</p>`}

        <div class="table__wrap" style="margin-top:var(--space-4)">
            <table class="table">
                <thead><tr>
                    <th>From your file</th><th class="num">Qty</th><th class="num">Your price</th>
                    <th>Matched to</th><th class="num">Live price</th><th>Status</th>
                </tr></thead>
                <tbody>${p.rows.map(previewRow).join('')}</tbody>
            </table>
        </div>

        <div class="button-row" style="margin-top:var(--space-5)">
            <button type="button" class="button button--ghost" id="h-cancel">Cancel</button>
            <button type="button" class="button button--buy" id="h-commit">
                Import ${p.rows.length} holdings
            </button>
        </div>
    </div>`;
}

function previewRow(row) {
    const tone = { CONFIRMED: 'BUY', NEEDS_REVIEW: 'SELL', UNRESOLVED: 'SELL' }[row.status] ?? 'SELL';
    const label = { CONFIRMED: 'matched', NEEDS_REVIEW: 'check', UNRESOLVED: 'no match' }[row.status] ?? row.status;
    return `
    <tr>
        <td><strong>${escapeHtml(row.name)}</strong>
            ${row.knownAlias ? '<br><span class="note">already mapped</span>' : ''}</td>
        <td class="num">${fmt.shares(row.quantity)}</td>
        <td class="num">${row.lastPrice !== null && row.lastPrice !== undefined
            ? Number(row.lastPrice).toFixed(2) : fmt.EMPTY} ${escapeHtml(row.currency || '')}</td>
        <td>${row.symbol ? `<strong>${escapeHtml(row.symbol)}</strong>` : fmt.EMPTY}
            ${row.note ? `<br><span class="note">${escapeHtml(row.note)}</span>` : ''}</td>
        <td class="num">${row.livePrice !== null && row.livePrice !== undefined
            ? Number(row.livePrice).toFixed(2) : fmt.EMPTY}</td>
        <td><span class="side-chip" data-side="${tone}">${label}</span></td>
    </tr>`;
}

function bindPreview() {
    qs('#h-cancel')?.addEventListener('click', closeImport);
    qs('#h-commit')?.addEventListener('click', async () => {
        const button = qs('#h-commit');
        if (button) button.disabled = true;
        try {
            const result = await api.commitImport(preview.id, {}, []);
            toast(`Imported ${result.result.imported} holdings from ${result.result.accountName}.`, 'success');
            closeImport();
            await renderHoldingsView(qs('#main'));
        } catch (error) {
            toast(error.message, 'error');
            if (button) button.disabled = false;
        }
    });
}
