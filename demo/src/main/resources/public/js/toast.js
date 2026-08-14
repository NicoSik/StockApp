/**
 * Transient notifications.
 *
 * The container is an aria-live region, so a screen reader announces a filled
 * order or a rejected one without the user having to go looking for it.
 */

const container = document.getElementById('toasts');

/**
 * @param {string} message
 * @param {'info'|'success'|'error'} tone
 */
export function toast(message, tone = 'info') {
    if (!container) return;

    const element = document.createElement('div');
    element.className = 'toast';
    element.dataset.tone = tone;
    element.textContent = message;
    // Errors interrupt; confirmations wait for a pause in speech.
    element.setAttribute('role', tone === 'error' ? 'alert' : 'status');
    container.append(element);

    const life = tone === 'error' ? 7000 : 4000;
    setTimeout(() => {
        element.style.transition = 'opacity 200ms, transform 200ms';
        element.style.opacity = '0';
        element.style.transform = 'translateX(12px)';
        setTimeout(() => element.remove(), 220);
    }, life);
}
