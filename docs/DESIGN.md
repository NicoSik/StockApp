# Interface design

The brief was "Robinhood-like, and make it better". This is what that meant in
practice: what was borrowed, what was changed, and the rules that keep it
coherent.

## What Robinhood gets right, and is borrowed

- **One number dominates.** The price is `clamp(2.25rem, 5vw, 3.25rem)`.
  Everything else is 15 px or smaller. You should be able to read the price
  from across the room and nothing else.
- **Colour means exactly one thing.** Green and red are reserved for direction.
  Nothing else on the page is green or red — buttons, links and chrome are all
  neutral, so a coloured pixel always answers "up or down?".
- **The chart has no furniture.** No gridlines, no axis labels, no legend. A
  line, a soft gradient beneath it, and a dashed previous-close reference.
  The x axis is time and the y axis is price; labelling that is noise.
- **Drag to read.** The chart's only affordance is scrubbing, and scrubbing
  updates the header rather than showing a tooltip bubble. The number you were
  already looking at becomes the number for the moment under your finger.
- **Tabular figures everywhere.** `font-variant-numeric: tabular-nums` on
  `body`. Without it, digits change width as prices tick and the whole rail
  shivers.

## What is deliberately better

**Keyboard access to the chart.** Robinhood's scrub is pointer-only, which
makes the underlying data unreachable without a mouse. Here the canvas is
focusable, and `←` `→` `Home` `End` step through the series while `Esc`
releases back to the live quote. This is also the fastest way to read an exact
value.

**A colour-blind-safe palette.** Encoding profit and loss in red versus green
fails for roughly one man in twelve. The toggle in the top bar swaps the pair
for blue/orange, which survives every common form of colour vision deficiency.
Direction is never carried by colour alone regardless: every change shows an
arrow (▲ ▼) and an explicit sign.

**A real light theme.** Both directional colours are darkened for light mode —
the dark-theme green (`#00c805`) fails contrast on white, so light mode uses
`#018d2c`. A theme is not an inversion.

**Density where it helps.** A stat rail (open, high, low, previous close,
volume, VWAP) and a day-range bar showing where the last price sits between the
session's low and high. Robinhood hides this behind taps.

**Honest empty states.** "Your value chart fills in after the first full
trading day since your earliest trade" rather than a generic shrug. When data
is stale or partial, the UI says so — a banner when a holding could not be
priced, a toast when charts fall back to stored history.

## Tokens

Everything lives in `css/tokens.css`. No component hard-codes a colour.

**Surfaces** step deliberately: `--bg` → `--surface` → `--surface-raised` →
`--surface-hover`. Each step is a real elevation, not a random grey.

**Direction** is `--up` / `--down` / `--neutral`, each with a `-soft` variant at
~14% alpha for backgrounds. Components only ever reference these, which is what
makes three palettes possible without touching a component.

**`--trend`** is set at runtime by `chart.js` from the last price against the
baseline. The active range pill reads it, so the control and the line cannot
disagree. One writer, many readers.

**Type** is a 6-step scale from 11 px to the hero clamp. **Spacing** is a 4 px
scale. **Motion** is three durations behind named tokens, so
`prefers-reduced-motion` can zero them in one place.

## Rules that keep it honest

**Never colour alone.** Arrow and sign always accompany the colour.

**Never a horizontal scrollbar.** The page must not pan sideways at any width.
Grid tracks are `minmax(0, 1fr)` rather than `1fr`, because a bare `1fr` is
`minmax(auto, 1fr)` and `auto` floors the track at the widest child's
min-content — one wide table or input then pushes the whole layout past the
viewport. `auto-fit` columns use `minmax(min(300px, 100%), 1fr)` so the floor
cannot exceed the container. Wide content scrolls inside its own
`overflow-x: auto` wrapper. Verified at 375 px.

**Reserve space before it fills.** `.chart` has a fixed height so the canvas
cannot reflow the page when data lands. Skeletons occupy the same box as the
content that replaces them.

**Focus must be visible.** A single `:focus-visible` rule gives every
interactive element a 2 px accent outline. It is never removed.

**Repaint on theme change.** Canvas is baked pixels; CSS variables do not reach
it. `chart.js` observes `data-theme` / `data-palette` on the root and repaints;
sparklines are redrawn by the rail.

**Poll only when visible.** Quote, clock and alert timers stop on
`visibilitychange` and catch up immediately on return. A background tab should
not burn the rate limit or the battery.

## Layout

```
        ≥900px                          <900px
┌────────────────────────┐      ┌──────────────────┐
│ topbar                 │      │ topbar           │
├──────────┬─────────────┤      ├──────────────────┤
│ rail     │ main        │      │ main             │
│ 300px    │ ≤1080px     │      ├──────────────────┤
│ sticky   │             │      │ rail             │
└──────────┴─────────────┘      └──────────────────┘
```

Below 900 px the rail moves beneath the content — on a phone you have come to
look at one symbol, not to browse a list. The chart shortens from 340 px to
260 px, the search trigger collapses to its icon, and the range pills scroll
horizontally within their own row.

## Keyboard

| Key | Action |
|---|---|
| `Ctrl-K` / `⌘K` / `/` | Open search |
| `↑` `↓` | Move through results |
| `↵` | Open the highlighted result |
| `Esc` | Close search, or release the chart cursor |
| `1`–`6` | Chart range 1D … 5Y |
| `p` | Portfolio |
| `←` `→` `Home` `End` | Scrub the focused chart |

## Accessibility

- Focus returns to its origin when the search palette closes, so keyboard
  navigation does not reset to the top of the document.
- The chart canvas has `role="img"` and a label naming the symbol and telling
  the user arrow keys work.
- Toasts live in an `aria-live` region; errors use `role="alert"` to interrupt,
  confirmations use `role="status"` to wait for a pause.
- The active watchlist row is marked with a left bar as well as a background
  tint, so the selection survives any palette.
- `prefers-reduced-motion` zeroes the motion tokens and disables the chart's
  reveal animation.
