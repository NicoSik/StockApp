/** Small DOM helpers shared across views. */

/**
 * Escapes a value for interpolation into an HTML template string.
 *
 * Company names come from a third-party API and routinely contain `&`, and
 * search text comes from the user. Everything untrusted goes through here
 * before it reaches innerHTML.
 */
export function escapeHtml(value) {
    return String(value ?? '')
        .replaceAll('&', '&amp;')
        .replaceAll('<', '&lt;')
        .replaceAll('>', '&gt;')
        .replaceAll('"', '&quot;')
        .replaceAll("'", '&#39;');
}

export const qs = (selector, root = document) => root.querySelector(selector);
export const qsa = (selector, root = document) => [...root.querySelectorAll(selector)];

/** Replaces an element's children with parsed HTML. */
export function setHtml(element, html) {
    if (element) element.innerHTML = html;
}

/**
 * Sets text only when it changed.
 *
 * The rail repaints on every poll; skipping no-op writes keeps the browser from
 * invalidating layout for rows whose price did not move.
 */
export function setText(element, text) {
    if (element && element.textContent !== text) element.textContent = text;
}

/** Swaps the direction class on an element to exactly one of up/down/flat. */
export function setDirectionClass(element, direction) {
    if (!element) return;
    element.classList.remove('up', 'down', 'flat');
    element.classList.add(direction);
}

/**
 * The panel a view shows when its data could not be loaded.
 *
 * A 404 gets its own wording, because "not in the database" is something the
 * user can act on and "something went wrong" is not.
 */
export function errorPanel(error, symbol) {
    const notFound = error?.status === 404;
    return `
    <div class="empty">
        <p class="empty__title">${notFound ? `${escapeHtml(symbol)} is not in the database` : 'Something went wrong'}</p>
        <p class="note">${escapeHtml(error.message)}</p>
        ${notFound ? '<p class="note">Try searching for the company name instead.</p>' : ''}
    </div>`;
}
