# HTTP API

All responses are JSON. Errors are always `{"error": "message"}` with a status
code — one shape for the client to handle. Error messages are written to be
shown to a person.

| Status | Meaning |
|---|---|
| 400 | Malformed request (`Json.BadRequest`) |
| 404 | No such symbol, watchlist or endpoint |
| 422 | A valid order the portfolio refused — insufficient buying power or shares |
| 502 | Alpaca is not responding |
| 500 | Unexpected; details are logged server-side, not returned |

---

## Meta

### `GET /api/health`
```json
{ "status": "ok", "time": 1786608477028 }
```

### `GET /api/meta`
```json
{
  "stockCount": 14696,
  "feed": "sip",
  "clock": { "isOpen": false, "session": "PRE",
             "nextOpen": "2026-08-13T09:30:00-04:00",
             "nextClose": "2026-08-13T16:00:00-04:00",
             "serverTime": 1786608477028 },
  "startingCash": 100000
}
```

`session` is `OPEN`, `PRE`, `AFTER`, `CLOSED` or `UNKNOWN`. Alpaca reports only
whether the regular session is open; pre- and after-hours are derived from the
market-local clock, guarded so a holiday is not labelled pre-market.

### `GET /api/market/clock`
The `clock` object above, on its own.

---

## Market data

### `GET /api/search?q={term}&limit={n}`
Ranked: exact ticker → ticker prefix → company prefix → substring. Listed
exchanges outrank OTC at equal relevance; shorter tickers win ties. `limit`
defaults to 12, capped at 50.

```json
[{ "id": 5058, "symbol": "AAPL", "company": "Apple Inc. Common Stock", "market": "NASDAQ" }]
```

### `GET /api/quotes?symbols=AAPL,MSFT`
Map keyed by symbol. Symbols with no data are absent rather than null.

```json
{ "AAPL": { "symbol": "AAPL", "price": 302.2, "previousClose": 304.885,
            "change": -2.685, "changePercent": -0.8806,
            "open": 305.075, "high": 305.61, "low": 300.585,
            "vwap": 301.974, "volume": 1799379, "asOf": 1786564799557 } }
```

`change` and `changePercent` are `null` when there is no previous close to
measure against — a distinct state from zero, and the UI renders a dash.

### `GET /api/rows?symbols=AAPL,MSFT`
Quote **and** sparkline per symbol in one call. Backs the watchlist rail and
the holdings table. Capped at 100 symbols.

```json
[{ "symbol": "AAPL", "company": "Apple Inc. Common Stock", "market": "NASDAQ",
   "quote": { "...": "as above" },
   "spark": { "symbol": "AAPL", "points": [304.75, 304.66, "…"], "baseline": 304.885 } }]
```

`points` is at most 48 closes from the latest session, downsampled by even
strides with the final bar always kept.

### `GET /api/stocks/{symbol}`
```json
{ "stock": { "id": 5058, "symbol": "AAPL", "company": "…", "market": "NASDAQ" },
  "quote": { "…": "…" },
  "watchlistIds": [1],
  "position": { "quantity": 10, "avgCost": 302.20, "realizedPnl": 0.00 } }
```
`position` is `null` when not held.

### `GET /api/stocks/{symbol}/candles?range={1D|1W|1M|3M|1Y|5Y}`
```json
{ "symbol": "AAPL", "range": "1D", "timeframe": "5Min",
  "points": [{ "time": 1786536300000, "open": 304.75, "high": 304.75,
               "low": 304.75, "close": 304.75, "volume": 100 }],
  "baseline": 304.885, "source": "alpaca" }
```

| `range` | timeframe | typical points |
|---|---|---|
| `1D` | 5Min | ~80–190 |
| `1W` | 30Min | ~120 |
| `1M` | 1Hour | ~190 |
| `3M` | 1Day | ~65 |
| `1Y` | 1Day | ~250 |
| `5Y` | 1Week | ~260 |

`baseline` is the previous session's close for `1D`, otherwise the first
point's close. `source` is `alpaca`, `database` (offline fallback) or
`unavailable`. An unrecognised `range` falls back to `1D` rather than erroring.

---

## Watchlists

| | |
|---|---|
| `GET /api/watchlists` | `[{ "id": 1, "name": "My Watchlist", "symbols": ["AAPL", "…"] }]` |
| `POST /api/watchlists` | `{"name": "Tech"}` → 201, the new list |
| `PATCH /api/watchlists/{id}` | `{"name": "…"}` → the updated list |
| `DELETE /api/watchlists/{id}` | 204 |
| `POST /api/watchlists/{id}/items` | `{"symbol": "AAPL"}` → the updated list. Adding a duplicate is a no-op |
| `DELETE /api/watchlists/{id}/items/{symbol}` | the updated list |
| `PUT /api/watchlists/{id}/order` | `{"symbols": ["MSFT", "AAPL"]}` → the reordered list |

---

## Portfolio

### `GET /api/portfolio`
```json
{ "id": 1, "name": "Paper Portfolio",
  "cash": 96978.00, "startingCash": 100000.00,
  "marketValue": 3022.00, "totalValue": 100000.00,
  "totalPnl": 0.00, "totalPnlPercent": 0.00,
  "dayChange": -26.85, "dayChangePercent": -0.03,
  "realizedPnl": 0.00,
  "holdings": [{ "symbol": "AAPL", "company": "…", "quantity": 10,
                 "avgCost": 302.2000, "costBasis": 3022.00,
                 "lastPrice": 302.20, "marketValue": 3022.00,
                 "unrealizedPnl": 0.00, "unrealizedPnlPercent": 0.00,
                 "realizedPnl": 0.00, "dayChange": -26.85,
                 "dayChangePercent": -0.88, "weight": 100.00 }],
  "stale": false }
```

`dayChangePercent` is measured against yesterday's total, not today's. `stale`
is true when at least one holding could not be priced and was valued at cost.

### `GET /api/portfolio/history?range={…}`
```json
{ "range": "1Y", "points": [{ "time": 1786536000000, "value": 100000.00 }] }
```
Empty until a full trading day has closed since the earliest trade — the curve
is built from daily closes, and there is nothing to plot before the first one.

### `GET /api/portfolio/trades?limit={n}`
Newest first. `limit` defaults to 50, capped at 500. `amount` is negative for a
buy, positive for a sell.

### `POST /api/portfolio/orders`
```json
{ "symbol": "AAPL", "side": "BUY", "quantity": 10 }
```
`side` is case-insensitive. `quantity` accepts a number or a numeric string, and
supports fractions to 6 decimal places. Fills at the current last trade price.

201 returns both the trade and the refreshed portfolio, saving a round trip:
```json
{ "trade": { "id": 1, "symbol": "AAPL", "side": "BUY", "quantity": 10,
             "price": 302.20, "amount": -3022.00,
             "executedAt": "2026-08-13T08:09:18.108355Z", "note": null },
  "portfolio": { "…": "the full summary" } }
```

422 when refused: `{"error": "Not enough buying power: this order costs 3022.00 but only 100.00 is available."}`

### `POST /api/portfolio/reset`
Deletes every trade and position and restores the opening cash balance.
Returns the reset summary.

---

## Alerts

| | |
|---|---|
| `GET /api/alerts` | Pending first, then most recently fired |
| `POST /api/alerts` | `{"symbol": "AAPL", "direction": "ABOVE", "threshold": 320}` → 201 |
| `DELETE /api/alerts/{id}` | 204 |
| `POST /api/alerts/evaluate` | Checks all pending alerts, returns those that fired on this pass |

`direction` is `ABOVE` or `BELOW`. An alert fires once; `triggeredAt` doubles as
the fired flag, and the conditional update makes a concurrent second evaluation
a no-op. The scheduler evaluates every 60 seconds regardless of the browser.

```json
[{ "id": 1, "symbol": "AAPL", "company": "…", "direction": "ABOVE",
   "threshold": 320.0000, "note": null,
   "createdAt": "2026-08-13T08:00:00Z",
   "triggeredAt": null, "triggeredPrice": null }]
```
