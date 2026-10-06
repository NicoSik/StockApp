# etoro

Client for eToro's personal API, which is how eToro holdings arrive live rather
than through an exported file. Two static keys from `.env` (`ETORO_API_KEY`,
`ETORO_USER_KEY`); the feature hides itself when they are absent.

## Not here

- Pricing. eToro positions are **never re-priced**. An account can mix shares,
  leveraged CFDs, shorts and copy portfolios, and only eToro knows what each is
  worth, so its reported value is authoritative. The only conversion left is
  to NOK, in `service/`.
- Writing snapshots — that is `service/EtoroSyncService`.

## Rules

- **Pinned to HTTP/1.1.** eToro accepts HTTP/2 and then never answers; without
  the pin every call stalls until the read timeout.
- `x-request-id` must be a fresh, real GUID per request.
- A connection that opens and then sends nothing is `EtoroStalledException`,
  so it is reported as a stall rather than mistaken for bad credentials.
- Failures surface as `EtoroException`, mapped to 502 with eToro's message.
