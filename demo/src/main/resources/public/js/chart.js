/**
 * The price chart.
 *
 * A hand-rolled canvas renderer rather than a charting library, for three
 * reasons: the scrub interaction that drives the header numbers is the whole
 * point and libraries fight you on it, the page loads no third-party code, and
 * a single line series needs perhaps 200 lines of drawing code.
 *
 * Design notes that matter:
 *
 * - The x axis is spaced by *index*, not by timestamp. Real timestamps would
 *   render the 17-hour overnight gap as a long flat stretch and squash the
 *   session that actually matters. Every charting app does this.
 *
 * - The y domain always includes the baseline, so the dashed previous-close
 *   line is never clipped off-screen on a big gap day.
 *
 * - Colour comes from last-versus-baseline, and is published back to the page
 *   as `--trend`, so the range pills and the chart cannot disagree.
 */

import { tooltipLabel } from './format.js';

const PADDING_TOP = 18;
const PADDING_BOTTOM = 18;
const HIT_RADIUS = 3.5;
const LINE_WIDTH = 2;

const prefersReducedMotion = () =>
    window.matchMedia('(prefers-reduced-motion: reduce)').matches;

const cssVar = (name) => getComputedStyle(document.documentElement).getPropertyValue(name).trim();

export class Chart {
    /**
     * @param {HTMLCanvasElement} canvas
     * @param {{ onScrub?: (info: object|null) => void, tooltip?: HTMLElement }} options
     */
    constructor(canvas, { onScrub, tooltip, priceTag, formatValue } = {}) {
        this.canvas = canvas;
        this.context = canvas.getContext('2d');
        this.onScrub = onScrub ?? (() => {});
        this.tooltipEl = tooltip ?? null;
        this.priceTagEl = priceTag ?? null;
        // How the value at the cursor is written: dollars on a stock chart,
        // kroner on the portfolio one.
        this.formatValue = formatValue ?? ((value) => String(value));

        this.points = [];
        this.baseline = null;
        this.range = '1D';
        this.activeIndex = null;
        this.progress = 1;
        this.animationFrame = null;
        this.animationFallback = null;

        this.handlePointerMove = this.handlePointerMove.bind(this);
        this.handlePointerLeave = this.handlePointerLeave.bind(this);
        this.handleKeyDown = this.handleKeyDown.bind(this);
        this.render = this.render.bind(this);

        canvas.addEventListener('pointermove', this.handlePointerMove);
        canvas.addEventListener('pointerdown', this.handlePointerMove);
        canvas.addEventListener('pointerleave', this.handlePointerLeave);
        canvas.addEventListener('pointercancel', this.handlePointerLeave);
        canvas.addEventListener('keydown', this.handleKeyDown);
        canvas.addEventListener('blur', this.handlePointerLeave);

        this.resizeObserver = new ResizeObserver(() => this.resize());
        this.resizeObserver.observe(canvas.parentElement ?? canvas);

        // Repaint on theme change: the palette lives in CSS variables that the
        // canvas has already baked into pixels.
        this.themeObserver = new MutationObserver(() => this.render());
        this.themeObserver.observe(document.documentElement, {
            attributes: true, attributeFilter: ['data-theme', 'data-palette'],
        });

        this.resize();
    }

    /**
     * Replaces the series.
     *
     * @param {{points: Array<{time:number, close:number}>, baseline: ?number, range: string,
     *          animate?: boolean}} data
     */
    setData({ points, baseline, range, animate = true }) {
        this.points = Array.isArray(points) ? points.filter((p) => Number.isFinite(p.close)) : [];
        this.baseline = Number.isFinite(baseline) ? baseline : null;
        this.range = range ?? this.range;
        this.activeIndex = null;
        // Clear the labels too, not just the index. Switching range with the
        // cursor still on the chart would otherwise leave the old value pinned
        // beside a header that has already reset to the live price.
        this.updateTooltip();
        this.publishTrend();

        if (animate && !prefersReducedMotion() && this.points.length > 1) {
            this.animateIn();
        } else {
            this.progress = 1;
            this.render();
        }
        this.onScrub(null);
    }

    /** The value every "today" number is measured against. */
    referencePrice() {
        if (this.baseline !== null) return this.baseline;
        return this.points.length ? this.points[0].close : null;
    }

    lastPrice() {
        return this.points.length ? this.points[this.points.length - 1].close : null;
    }

    /** 'up' | 'down' | 'flat', from the last point against the reference. */
    trend() {
        const reference = this.referencePrice();
        const last = this.lastPrice();
        if (reference === null || last === null || last === reference) return 'flat';
        return last > reference ? 'up' : 'down';
    }

    /**
     * Publishes the trend colour to the document so CSS can use it. Keeping one
     * writer for `--trend` is what stops the pills and the line drifting apart.
     */
    publishTrend() {
        const trend = this.trend();
        const root = document.documentElement;
        const colorVar = trend === 'flat' ? '--neutral' : `--${trend}`;
        root.style.setProperty('--trend', `var(${colorVar})`);
        root.style.setProperty('--trend-soft', `var(${colorVar}-soft)`);
    }

    // ------------------------------------------------------------ geometry

    resize() {
        const parent = this.canvas.parentElement ?? this.canvas;
        const rect = parent.getBoundingClientRect();
        if (rect.width === 0 || rect.height === 0) return;

        const dpr = window.devicePixelRatio || 1;
        this.width = rect.width;
        this.height = rect.height;
        this.canvas.width = Math.round(rect.width * dpr);
        this.canvas.height = Math.round(rect.height * dpr);
        // Draw in CSS pixels; the transform handles the device ratio.
        this.context.setTransform(dpr, 0, 0, dpr, 0, 0);
        this.render();
    }

    /** y domain, padded, always containing the baseline. */
    domain() {
        let min = Infinity;
        let max = -Infinity;
        for (const point of this.points) {
            if (point.close < min) min = point.close;
            if (point.close > max) max = point.close;
        }
        if (this.baseline !== null) {
            min = Math.min(min, this.baseline);
            max = Math.max(max, this.baseline);
        }
        if (!Number.isFinite(min) || !Number.isFinite(max)) return { min: 0, max: 1 };
        if (min === max) {
            // A perfectly flat series still needs a non-zero range to divide by.
            const nudge = Math.abs(min) * 0.01 || 1;
            return { min: min - nudge, max: max + nudge };
        }
        const padding = (max - min) * 0.08;
        return { min: min - padding, max: max + padding };
    }

    xAt(index) {
        if (this.points.length <= 1) return this.width / 2;
        return (index / (this.points.length - 1)) * this.width;
    }

    yAt(value, domain) {
        const usable = this.height - PADDING_TOP - PADDING_BOTTOM;
        const ratio = (value - domain.min) / (domain.max - domain.min);
        return PADDING_TOP + (1 - ratio) * usable;
    }

    indexAt(clientX) {
        const rect = this.canvas.getBoundingClientRect();
        const x = clientX - rect.left;
        if (this.points.length <= 1) return 0;
        const index = Math.round((x / rect.width) * (this.points.length - 1));
        return Math.max(0, Math.min(this.points.length - 1, index));
    }

    // ------------------------------------------------------------ animation

    animateIn() {
        cancelAnimationFrame(this.animationFrame);
        clearTimeout(this.animationFallback);

        const duration = 520;
        const startedAt = performance.now();
        this.progress = 0;

        const step = (now) => {
            const elapsed = now - startedAt;
            const t = Math.min(1, elapsed / duration);
            // easeOutCubic: quick to reveal, gentle to settle.
            this.progress = 1 - Math.pow(1 - t, 3);
            this.render();
            if (t < 1) this.animationFrame = requestAnimationFrame(step);
        };
        this.animationFrame = requestAnimationFrame(step);

        // requestAnimationFrame does not fire while the document is not being
        // composited - a background tab, a hidden panel, a headless capture. On
        // its own that leaves the canvas permanently blank, because the only
        // code that ever draws lives inside the callback. setTimeout is still
        // delivered (throttled) in those states, so it backstops the animation
        // and guarantees the chart ends up painted either way.
        this.animationFallback = setTimeout(() => {
            if (this.progress < 1) {
                this.progress = 1;
                // resize() rather than render(): ResizeObserver is suspended
                // alongside rAF in those same states, so the cached width may
                // predate a resize that happened while the page was hidden.
                this.resize();
            }
        }, duration + 400);
    }

    // -------------------------------------------------------------- drawing

    render() {
        const ctx = this.context;
        if (!ctx || !this.width || !this.height) return;

        ctx.clearRect(0, 0, this.width, this.height);
        if (this.points.length === 0) return;

        const domain = this.domain();
        const trend = this.trend();
        const color = cssVar(trend === 'flat' ? '--neutral' : `--${trend}`) || '#8b8b97';
        const visibleCount = Math.max(2, Math.round(this.points.length * this.progress));
        const visible = this.points.slice(0, visibleCount);

        this.drawBaseline(ctx, domain);
        this.drawArea(ctx, visible, domain, color);
        this.drawLine(ctx, visible, domain, color);

        if (this.progress >= 1) {
            this.drawEndDot(ctx, domain, color);
            if (this.activeIndex !== null) this.drawCrosshair(ctx, domain, color);
        }
    }

    drawBaseline(ctx, domain) {
        if (this.baseline === null) return;
        const y = this.yAt(this.baseline, domain);
        ctx.save();
        ctx.strokeStyle = cssVar('--border-strong') || 'rgba(255,255,255,0.2)';
        ctx.lineWidth = 1;
        ctx.setLineDash([3, 5]);
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(this.width, y);
        ctx.stroke();
        ctx.restore();
    }

    drawArea(ctx, points, domain, color) {
        if (points.length < 2) return;
        const gradient = ctx.createLinearGradient(0, PADDING_TOP, 0, this.height);
        gradient.addColorStop(0, withAlpha(color, 0.18));
        gradient.addColorStop(1, withAlpha(color, 0));

        ctx.save();
        ctx.beginPath();
        ctx.moveTo(this.xAt(0), this.height);
        points.forEach((point, index) => ctx.lineTo(this.xAt(index), this.yAt(point.close, domain)));
        ctx.lineTo(this.xAt(points.length - 1), this.height);
        ctx.closePath();
        ctx.fillStyle = gradient;
        ctx.fill();
        ctx.restore();
    }

    drawLine(ctx, points, domain, color) {
        ctx.save();
        ctx.beginPath();
        points.forEach((point, index) => {
            const x = this.xAt(index);
            const y = this.yAt(point.close, domain);
            if (index === 0) ctx.moveTo(x, y);
            else ctx.lineTo(x, y);
        });
        ctx.strokeStyle = color;
        ctx.lineWidth = LINE_WIDTH;
        ctx.lineJoin = 'round';
        ctx.lineCap = 'round';
        // A faint glow reads as depth without costing legibility.
        ctx.shadowColor = withAlpha(color, 0.4);
        ctx.shadowBlur = 8;
        ctx.stroke();
        ctx.restore();
    }

    drawEndDot(ctx, domain, color) {
        const index = this.points.length - 1;
        const x = this.xAt(index);
        const y = this.yAt(this.points[index].close, domain);
        ctx.save();
        ctx.fillStyle = withAlpha(color, 0.25);
        ctx.beginPath();
        ctx.arc(x, y, 6, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(x, y, HIT_RADIUS, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
    }

    /**
     * The full crosshair: a vertical line at the cursor and a horizontal one at
     * the value under it.
     *
     * <p>The horizontal line is what makes a chart readable at a glance - it
     * carries the eye from the point to the value, which is otherwise only
     * legible in the header far above. It is drawn dashed and in the border
     * colour so it reads as chrome rather than as data, and it stops short of
     * the price tag so the two do not overlap.
     */
    drawCrosshair(ctx, domain, color) {
        const index = this.activeIndex;
        const point = this.points[index];
        if (!point) return;
        const x = this.xAt(index);
        const y = this.yAt(point.close, domain);

        ctx.save();
        ctx.strokeStyle = cssVar('--border-strong') || 'rgba(255,255,255,0.2)';
        ctx.lineWidth = 1;

        // Vertical: full height, solid, marks the moment in time.
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, this.height);
        ctx.stroke();

        // Horizontal: dashed, marks the value. Reserves room on the right for
        // the price tag rather than running underneath it.
        const tagWidth = this.priceTagEl ? this.priceTagEl.offsetWidth + 10 : 0;
        ctx.setLineDash([3, 4]);
        ctx.beginPath();
        ctx.moveTo(0, y);
        ctx.lineTo(Math.max(0, this.width - tagWidth), y);
        ctx.stroke();
        ctx.setLineDash([]);

        // A ring in the page background colour lifts the dot off the line.
        ctx.fillStyle = cssVar('--bg') || '#000';
        ctx.beginPath();
        ctx.arc(x, y, HIT_RADIUS + 3, 0, Math.PI * 2);
        ctx.fill();
        ctx.fillStyle = color;
        ctx.beginPath();
        ctx.arc(x, y, HIT_RADIUS + 1, 0, Math.PI * 2);
        ctx.fill();
        ctx.restore();
    }

    // ---------------------------------------------------------- interaction

    handlePointerMove(event) {
        if (this.points.length === 0) return;
        // Only track while a finger is down on touch; a stray hover on desktop
        // should still scrub, which is what `pointerType` distinguishes.
        if (event.pointerType !== 'mouse' && event.buttons === 0 && event.type === 'pointermove') return;
        this.setActiveIndex(this.indexAt(event.clientX));
    }

    handlePointerLeave() {
        if (this.activeIndex === null) return;
        this.activeIndex = null;
        this.updateTooltip();
        this.render();
        this.onScrub(null);
    }

    /** Arrow keys scrub the chart, which makes the data reachable without a mouse. */
    handleKeyDown(event) {
        if (this.points.length === 0) return;
        const last = this.points.length - 1;
        const current = this.activeIndex ?? last;
        let next = null;

        switch (event.key) {
            case 'ArrowLeft': next = current - 1; break;
            case 'ArrowRight': next = current + 1; break;
            case 'Home': next = 0; break;
            case 'End': next = last; break;
            case 'Escape': this.handlePointerLeave(); return;
            default: return;
        }
        event.preventDefault();
        this.setActiveIndex(Math.max(0, Math.min(last, next)));
    }

    setActiveIndex(index) {
        if (index === this.activeIndex) return;
        const point = this.points[index];
        if (!point) return;

        this.activeIndex = index;
        this.updateTooltip();
        this.render();

        const reference = this.referencePrice();
        const change = reference === null ? null : point.close - reference;
        this.onScrub({
            index,
            point,
            price: point.close,
            change,
            changePercent: change === null || !reference ? null : (change / reference) * 100,
        });
    }

    updateTooltip() {
        const point = this.activeIndex === null ? null : this.points[this.activeIndex];
        // The index can outlive the data it pointed at: a range switch or a
        // refresh replaces `points` while a cursor is still parked on the
        // chart. drawCrosshair already tolerates that; this must too, or the
        // labels throw on a stale index.
        if (!point) {
            if (this.tooltipEl) this.tooltipEl.dataset.visible = 'false';
            if (this.priceTagEl) this.priceTagEl.dataset.visible = 'false';
            return;
        }

        if (this.tooltipEl) {
            this.tooltipEl.textContent = tooltipLabel(point.time, this.range);
            this.tooltipEl.dataset.visible = 'true';
            // Keep the label inside the canvas at both ends.
            const x = this.xAt(this.activeIndex);
            const halfWidth = this.tooltipEl.offsetWidth / 2;
            this.tooltipEl.style.left = `${Math.max(halfWidth, Math.min(this.width - halfWidth, x))}px`;
        }

        if (this.priceTagEl) {
            this.priceTagEl.textContent = this.formatValue(point.close);
            this.priceTagEl.dataset.visible = 'true';
            // Pinned to the right edge at the crosshair's height, the way a
            // price axis behaves, and clamped so it never leaves the plot.
            const y = this.yAt(point.close, this.domain());
            const halfHeight = this.priceTagEl.offsetHeight / 2;
            this.priceTagEl.style.top =
                `${Math.max(halfHeight, Math.min(this.height - halfHeight, y))}px`;
        }
    }

    destroy() {
        cancelAnimationFrame(this.animationFrame);
        clearTimeout(this.animationFallback);
        this.resizeObserver.disconnect();
        this.themeObserver.disconnect();
        this.canvas.removeEventListener('pointermove', this.handlePointerMove);
        this.canvas.removeEventListener('pointerdown', this.handlePointerMove);
        this.canvas.removeEventListener('pointerleave', this.handlePointerLeave);
        this.canvas.removeEventListener('pointercancel', this.handlePointerLeave);
        this.canvas.removeEventListener('keydown', this.handleKeyDown);
        this.canvas.removeEventListener('blur', this.handlePointerLeave);
    }
}

/**
 * Applies an alpha to a CSS colour.
 *
 * Theme tokens are authored as hex, but a user stylesheet or a future token
 * could be `rgb()`, so both are handled rather than assuming.
 */
function withAlpha(color, alpha) {
    const hex = color.replace('#', '');
    if (/^[0-9a-f]{6}$/i.test(hex)) {
        const r = parseInt(hex.slice(0, 2), 16);
        const g = parseInt(hex.slice(2, 4), 16);
        const b = parseInt(hex.slice(4, 6), 16);
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    }
    const match = color.match(/rgba?\(([^)]+)\)/);
    if (match) {
        const [r, g, b] = match[1].split(',').map((part) => parseFloat(part));
        return `rgba(${r}, ${g}, ${b}, ${alpha})`;
    }
    return color;
}
