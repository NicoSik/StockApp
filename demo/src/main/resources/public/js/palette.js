/**
 * The command-palette search (Cmd/Ctrl-K).
 *
 * Search-as-you-type against 14,000 symbols, so three things matter:
 * debouncing so keystrokes do not each become a request, aborting the previous
 * request so a slow early response cannot overwrite a fast later one, and full
 * keyboard control so the mouse is never required.
 */

import { api } from './api.js';
import { escapeHtml } from './dom.js';

const DEBOUNCE_MS = 140;

export class SearchPalette {
    /** @param {(symbol: string) => void} onPick */
    constructor(onPick) {
        this.onPick = onPick;
        this.backdrop = document.getElementById('palette-backdrop');
        this.input = document.getElementById('palette-input');
        this.results = document.getElementById('palette-results');

        this.items = [];
        this.activeIndex = 0;
        this.debounceTimer = null;
        this.inFlight = null;
        this.previouslyFocused = null;

        this.input.addEventListener('input', () => this.scheduleSearch());
        this.input.addEventListener('keydown', (event) => this.handleKeyDown(event));
        this.backdrop.addEventListener('pointerdown', (event) => {
            if (event.target === this.backdrop) this.close();
        });
        this.results.addEventListener('click', (event) => {
            const button = event.target.closest('[data-symbol]');
            if (button) this.pick(button.dataset.symbol);
        });

        document.addEventListener('keydown', (event) => {
            if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
                event.preventDefault();
                this.toggle();
            }
            // "/" is the other muscle memory for search, but not while typing.
            if (event.key === '/' && !this.isOpen() && !isTypingTarget(event.target)) {
                event.preventDefault();
                this.open();
            }
        });
    }

    isOpen() {
        return !this.backdrop.hidden;
    }

    toggle() {
        this.isOpen() ? this.close() : this.open();
    }

    open() {
        this.previouslyFocused = document.activeElement;
        this.backdrop.hidden = false;
        this.input.value = '';
        this.renderMessage('Type a ticker or company name.');
        this.input.focus();
    }

    close() {
        this.backdrop.hidden = true;
        this.inFlight?.abort();
        clearTimeout(this.debounceTimer);
        this.items = [];
        // Returning focus is what keeps keyboard navigation from resetting to
        // the top of the document every time the palette closes.
        this.previouslyFocused?.focus?.();
    }

    scheduleSearch() {
        clearTimeout(this.debounceTimer);
        const query = this.input.value.trim();
        if (!query) {
            this.inFlight?.abort();
            this.items = [];
            this.renderMessage('Type a ticker or company name.');
            return;
        }
        this.debounceTimer = setTimeout(() => this.runSearch(query), DEBOUNCE_MS);
    }

    async runSearch(query) {
        this.inFlight?.abort();
        const controller = new AbortController();
        this.inFlight = controller;

        try {
            const results = await api.search(query, controller.signal);
            // A response for a query the user has already typed past is noise.
            if (controller.signal.aborted || this.input.value.trim() !== query) return;
            this.items = results;
            this.activeIndex = 0;
            this.renderResults();
        } catch (error) {
            if (error.name === 'AbortError') return;
            this.renderMessage(error.message);
        }
    }

    renderMessage(message) {
        this.results.innerHTML = `<p class="palette__item palette__company">${escapeHtml(message)}</p>`;
    }

    renderResults() {
        if (this.items.length === 0) {
            this.renderMessage('No matches.');
            return;
        }
        this.results.innerHTML = this.items
            .map(
                (item, index) => `
                <button type="button" class="palette__item" role="option"
                        data-symbol="${escapeHtml(item.symbol)}"
                        data-active="${index === this.activeIndex}"
                        aria-selected="${index === this.activeIndex}">
                    <span class="palette__symbol">${escapeHtml(item.symbol)}</span>
                    <span class="palette__company">${escapeHtml(item.company)}</span>
                    <span class="palette__market">${escapeHtml(item.market)}</span>
                </button>`,
            )
            .join('');
    }

    handleKeyDown(event) {
        switch (event.key) {
            case 'Escape':
                event.preventDefault();
                this.close();
                break;
            case 'ArrowDown':
                event.preventDefault();
                this.move(1);
                break;
            case 'ArrowUp':
                event.preventDefault();
                this.move(-1);
                break;
            case 'Enter':
                event.preventDefault();
                if (this.items[this.activeIndex]) this.pick(this.items[this.activeIndex].symbol);
                break;
            default:
        }
    }

    move(delta) {
        if (this.items.length === 0) return;
        // Wrap around: from the last result, down goes back to the first.
        this.activeIndex = (this.activeIndex + delta + this.items.length) % this.items.length;
        this.renderResults();
        this.results.querySelector('[data-active="true"]')?.scrollIntoView({ block: 'nearest' });
    }

    pick(symbol) {
        this.close();
        this.onPick(symbol);
    }
}

function isTypingTarget(element) {
    if (!element) return false;
    const tag = element.tagName;
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || element.isContentEditable;
}
