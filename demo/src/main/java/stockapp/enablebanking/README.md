# enablebanking

Client for Enable Banking, the licensed Open Banking aggregator that bank
account balances come through. Access to each bank is consented with BankID at
the bank; this package only ever holds the application's own key. Configured by
`ENABLE_BANKING_APP_ID` and `ENABLE_BANKING_KEY_FILE` in `.env`; the feature
hides itself when they are absent.

## What's inside

**`EnableBankingClient`**
- `configured()` — whether an application ID and a readable key file are set.
- `banks(country)` — the banks that can be linked, with each one's longest consent.
- `startAuthorization(bank, country, redirectUrl, state, validUntil)` — the bank's BankID login page.
- `createSession(code)` — turns the code the bank sent back into a session over the linked accounts.
- `balances(accountUid)` — every balance the bank reports for one account.
- `pickBalance(balances)` — the one to count: booked before available.
- `Bank`, `LinkedAccount`, `Session`, `Balance` — what those calls return.

**`EnableBankingException`** — a refused or failed request, with the HTTP status (0 when nothing came back).

## Not here

- Storing links and writing snapshots — that is `service/BankSyncService` and
  `repo/BankLinkRepo`.
- Any bank login. BankID happens at the bank, in the user's browser.

## Rules

- Every request carries an RS256 JWT signed with the key file, `kid` set to
  the application ID, living an hour and reused until five minutes before it
  expires.
- The key must be PKCS#8 (`BEGIN PRIVATE KEY`). A PKCS#1 file is refused with
  the `openssl` command that converts it.
- Booked balances beat available ones: "available" can include a credit line.
- Failures surface as `EnableBankingException`, mapped to 502 with a message
  written for a person.
