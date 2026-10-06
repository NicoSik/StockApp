# Ticker

A local stock app with two halves that never mix:

- **Watchlists, charts and a paper portfolio** — US equities from Alpaca,
  simulated trades only.
- **Holdings** — real accounts from DNB, Nordnet and eToro, valued in NOK.
  Prices from Yahoo (Oslo Børs, Stockholm, Norwegian funds), exchange rates
  from Norges Bank, eToro positions from its own API.

Java 17 + Javalin 7, PostgreSQL, a vanilla-JS front end with no build step.
Single user, localhost only, no authentication.

Read `docs/ARCHITECTURE.md` before changing anything structural.

## Constraints

- **No front-end framework and no bundler.** ES modules served straight from
  `demo/src/main/resources/public`. One module per screen (`detail.js`,
  `portfolio.js`, `holdings.js`); `app.js` is only the shell.
- **No third-party scripts or fonts.** The page must work offline.
- **Money is `BigDecimal`.** Anything stored, summed or shown as an amount is
  `BigDecimal`, rounded through `model/Money`. Market-data prices arrive as
  `double` and are converted at that boundary — never accumulate doubles.
- **Every schema change is a new numbered migration**, appended to `MIGRATIONS`
  in `Db.java`. Never edit one that has shipped.
- **Credentials only in `.env`.** Never in a tracked file.
- **Log through SLF4J** (`LoggerFactory.getLogger(X.class)`), never
  `System.out`.
- **One package and client per external API** — `alpaca/`, `etoro/`, `yahoo/`,
  `norgesbank/` — each derived from the shared `OkHttpClient` built in
  `App.java`.
- **HTTP errors:** throw `BadRequest`, `NotFound` or a domain exception. Status
  codes are mapped in one place, `web/ErrorHandlers`.
- **The server binds to 127.0.0.1.** It serves real holdings without
  authentication; it must never listen on other interfaces.

## Interface direction

Robinhood-like: near-black canvas, one dominant price figure, colour reserved
for direction only, full-bleed line chart with no gridlines, drag-to-scrub
driving the header numbers. Beat it where it is weak — keyboard access,
colour-blind safety, and information density in the stat rail. The rules are in
`docs/DESIGN.md`.

## Environment

- macOS, zsh. JDK 21 from Homebrew (`openjdk@21`). Windows is still supported
  through `run.bat`.
- Maven via the wrapper: `demo/mvnw`. Nothing to install.
- PostgreSQL on port **5433** (the `Config` default, not the usual 5432).
- App on port **9090**. Start it with `./run.sh`.

## Definition of done

1. `cd demo && ./mvnw clean test` passes.
2. The app starts and the affected screen has been driven in a browser.
3. No console errors; no horizontal page scroll at 375 px wide.
4. Correct in light and dark, and while the market is closed.
5. `docs/` is updated if behaviour changed.

## How to work

Take the time it needs. If there is a problem underneath the one asked about,
say so and fix it rather than building on top of it. Ask before anything
destructive or irreversible. Say plainly what was not finished.
