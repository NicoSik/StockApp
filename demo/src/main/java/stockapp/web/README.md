# web

HTTP routes and the error contract. `Api` serves the paper-trading half under
`/api/*`; `AggregatorApi` serves holdings under `/api/holdings/*`. Every
endpoint is documented in `docs/API.md`.

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
