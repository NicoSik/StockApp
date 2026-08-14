/**
 * Watchlist sparklines.
 *
 * Deliberately not the {@link Chart} class: these are 68x30 px, there are
 * dozens on screen, and they redraw on every quote tick. No animation, no
 * interaction, no observers - just a path.
 */

const PADDING = 3;

/**
 * @param {HTMLCanvasElement} canvas
 * @param {number[]} points  closes, oldest first
 * @param {?number} baseline previous close; decides the colour
 */
export function drawSparkline(canvas, points, baseline) {
    const context = canvas.getContext('2d');
    if (!context) return;

    const dpr = window.devicePixelRatio || 1;
    const width = canvas.clientWidth || 68;
    const height = canvas.clientHeight || 30;
    canvas.width = Math.round(width * dpr);
    canvas.height = Math.round(height * dpr);
    context.setTransform(dpr, 0, 0, dpr, 0, 0);
    context.clearRect(0, 0, width, height);

    if (!Array.isArray(points) || points.length < 2) return;

    const reference = Number.isFinite(baseline) ? baseline : points[0];
    const last = points[points.length - 1];
    const styles = getComputedStyle(document.documentElement);
    const token = last > reference ? '--up' : last < reference ? '--down' : '--neutral';
    const color = styles.getPropertyValue(token).trim() || '#8b8b97';

    let min = Math.min(...points);
    let max = Math.max(...points);
    // The baseline is included so a row that gapped down still shows the drop
    // rather than a flat line drawn across its own narrow range.
    if (Number.isFinite(baseline)) {
        min = Math.min(min, baseline);
        max = Math.max(max, baseline);
    }
    if (min === max) {
        min -= 1;
        max += 1;
    }

    const usable = height - PADDING * 2;
    const x = (index) => (index / (points.length - 1)) * width;
    const y = (value) => PADDING + (1 - (value - min) / (max - min)) * usable;

    context.beginPath();
    points.forEach((value, index) => {
        if (index === 0) context.moveTo(x(index), y(value));
        else context.lineTo(x(index), y(value));
    });
    context.strokeStyle = color;
    context.lineWidth = 1.5;
    context.lineJoin = 'round';
    context.lineCap = 'round';
    context.stroke();
}
