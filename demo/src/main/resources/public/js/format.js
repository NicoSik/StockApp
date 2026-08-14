/**
 * Number, money and date formatting.
 *
 * Every formatter is built once at module load. `Intl.NumberFormat` is
 * expensive to construct and these run on every price tick, for every visible
 * row.
 */

const MARKET_TIME_ZONE = 'America/New_York';

const money0 = new Intl.NumberFormat('en-US', {
    style: 'currency', currency: 'USD', minimumFractionDigits: 0, maximumFractionDigits: 0,
});
const money2 = new Intl.NumberFormat('en-US', {
    style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2,
});
const decimal2 = new Intl.NumberFormat('en-US', {
    minimumFractionDigits: 2, maximumFractionDigits: 2,
});
const compact = new Intl.NumberFormat('en-US', {
    notation: 'compact', maximumFractionDigits: 2,
});

/** Clock time in market hours, e.g. "10:35 AM" - always New York, never local. */
const marketTime = new Intl.DateTimeFormat('en-US', {
    hour: 'numeric', minute: '2-digit', timeZone: MARKET_TIME_ZONE,
});
const marketDay = new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', timeZone: MARKET_TIME_ZONE,
});
const marketDayYear = new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', year: 'numeric', timeZone: MARKET_TIME_ZONE,
});
const marketDateTime = new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: MARKET_TIME_ZONE,
});

const isNum = (value) => typeof value === 'number' && Number.isFinite(value);

/** A dash, not "0" or "NaN" - absent data should look absent. */
export const EMPTY = '—';

export function usd(value) {
    if (!isNum(Number(value))) return EMPTY;
    return money2.format(Number(value));
}

export function usdCompact(value) {
    const n = Number(value);
    if (!isNum(n)) return EMPTY;
    return Math.abs(n) >= 100000 ? money0.format(n) : money2.format(n);
}

/** Money with an explicit sign, for changes: "+$2.31", "-$1.20". */
export function signedUsd(value) {
    const n = Number(value);
    if (!isNum(n)) return EMPTY;
    return (n >= 0 ? '+' : '-') + money2.format(Math.abs(n));
}

export function signedPercent(value) {
    const n = Number(value);
    if (!isNum(n)) return EMPTY;
    return `${n >= 0 ? '+' : '-'}${decimal2.format(Math.abs(n))}%`;
}

export function percent(value) {
    const n = Number(value);
    return isNum(n) ? `${decimal2.format(n)}%` : EMPTY;
}

/** Share counts: whole numbers stay whole, fractions keep what they need. */
export function shares(value) {
    const n = Number(value);
    if (!isNum(n)) return EMPTY;
    return Number.isInteger(n) ? String(n) : String(parseFloat(n.toFixed(6)));
}

/** Volume and other large counts: 49.1M rather than 49,067,433. */
export function abbreviate(value) {
    const n = Number(value);
    if (!isNum(n)) return EMPTY;
    return compact.format(n);
}

export function price(value) {
    const n = Number(value);
    if (!isNum(n)) return EMPTY;
    // Sub-dollar tickers need more precision than blue chips.
    return n < 1 ? `$${n.toFixed(4)}` : money2.format(n);
}

/**
 * The x-axis label appropriate to a range: a clock time within one session,
 * a date across weeks, a date with year across years.
 */
export function axisLabel(epochMillis, range) {
    const date = new Date(epochMillis);
    if (range === '1D') return marketTime.format(date);
    if (range === '1W') return marketDateTime.format(date);
    if (range === '5Y') return marketDayYear.format(date);
    return marketDay.format(date);
}

/** Full timestamp for the chart tooltip. */
export function tooltipLabel(epochMillis, range) {
    const date = new Date(epochMillis);
    if (range === '1D' || range === '1W') return marketDateTime.format(date);
    return marketDayYear.format(date);
}

export function dateTime(isoString) {
    if (!isoString) return EMPTY;
    return marketDateTime.format(new Date(isoString));
}

/** "up" | "down" | "flat" - the class name that carries direction. */
export function direction(value) {
    const n = Number(value);
    if (!isNum(n) || n === 0) return 'flat';
    return n > 0 ? 'up' : 'down';
}

/** "▲" | "▼" | "" - shape backs up colour for anyone who cannot see it. */
export function arrow(value) {
    const dir = direction(value);
    if (dir === 'up') return '▲';
    if (dir === 'down') return '▼';
    return '';
}
