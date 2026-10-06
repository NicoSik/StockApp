# norgesbank

Exchange rates against NOK from Norges Bank's SDMX API. Free, no key, and the
authoritative source for a Norwegian portfolio.

## What's inside

**`NorgesBankClient`**
- `latest(currencies)` — the latest published rate for each currency, as NOK per one unit.
- `parse(csv)` — reads the CSV response and applies `UNIT_MULT`.

## Not here

- Caching, storing rates or converting amounts — that is `service/FxService`,
  which also falls back to the last stored rates when this API is down.

## Rules

- **`UNIT_MULT` must be applied.** SEK and DKK are quoted per *hundred*. Taking
  `OBS_VALUE` at face value values a Swedish holding at 100× its worth. Rates
  leave this package as "1 unit = n NOK", always.
- Columns are found by header name, not position; their order is not
  guaranteed.
- Rates are published once per business day, so weekends return Friday's.
