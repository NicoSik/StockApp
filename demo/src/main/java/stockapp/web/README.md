# web

HTTP routes and the error contract. `Api` serves the paper-trading half under
`/api/*`; `AggregatorApi` serves holdings under `/api/holdings/*`. Every
endpoint is documented in `docs/API.md`.

## What's inside

- **`Api`** — `register(routes)`: health, search, quotes, rail rows, charts, watchlists, the paper portfolio and alerts, ending with the JSON 404 catch-all.
- **`AggregatorApi`** — `register(routes)`: holdings, history, file imports, fund entry, eToro sync, bank linking and sync, and symbol lookup.
- **`ErrorHandlers`** — `register(routes)`: every exception to a status code and `{"error": …}`.
- **`Json`** — `parseObject(body)`, `requireString`, `optString`, `requirePositiveDecimal`, `requireOneOf`; `write(value)` for responses.
- **`GsonMapper`** — makes Javalin's `ctx.json()` use Gson.
- **`BadRequest`**, **`NotFound`** — throw these for 400 and 404.

## Not here

- Logic. A handler validates input, calls a service, and returns its result.

## Rules

- **One error shape.** Throw `BadRequest`, `NotFound` or a domain exception;
  `ErrorHandlers` is the only place that maps them to status codes, always as
  `{"error": "..."}`. Messages are written for a person to read.
- **Unexpected errors are logged, not returned.** A 500 carries a generic
  message; the stack trace goes to the log.
- **Read request bodies through `Json`**, so a bad field produces a precise
  400 instead of a generic binding failure.
- The `/api/*` catch-all is registered last, so an unknown path gets a JSON 404
  instead of the single-page app's `index.html`.
- A new or changed endpoint is updated in `docs/API.md` in the same change.
  `ApiDocsTest` fails the build when a registered route is missing from the
  doc, or the doc names a route that no longer exists.
