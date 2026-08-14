# Working prompt for this project

You asked me to "make this prompt better". This file has three parts: what your
original prompt did well, a rewritten version you can reuse, and a short guide
to writing the next one.

---

## 1. Your original

> I want you to help me continue building my stock watcher app. I want the ui to
> be be robinhoodlike and make it better, make this prompt betetr and make md if
> you need to i want you to do this througly and use your time, i am not in a
> hurry, make it good and that i will be proud of yopu

**What worked, and worked well:**

- **"robinhoodlike"** is a genuinely efficient instruction. One word carries a
  whole design language: near-black canvas, one enormous price, green/red as
  the only meaningful colours, a full-bleed line chart with no axes, and the
  drag-to-scrub gesture. That is more useful than a page of adjectives.
- **"I am not in a hurry"** is the single most valuable sentence in it. It
  authorised fixing the build, the schema and the data pipeline instead of
  painting over them. Most requests do not say this, and the result is
  usually a nicer-looking version of a broken thing.
- **"and make it better"** invited going past imitation, which is where the
  keyboard scrubbing, the colour-blind palette and the compare-ready chart
  came from.

**What cost time:**

- **No mention of the data problem.** The database had 14,696 stocks and zero
  price rows, and the live `stock_price` table had no price columns at all. A
  "make the UI Robinhood-like" request implies there is data to show. Finding
  that out took the first stretch of the session.
- **"stock watcher" vs. the Robinhood reference.** Robinhood is a brokerage;
  its interface is built around a portfolio. "Watcher" suggests no portfolio.
  I had to ask which you meant.
- **No environment facts.** Maven was not installed, `JAVA_HOME` pointed at
  JDK 16, and the port was 9090 rather than the documented 8080. Each was a
  small stall.

None of these are failings — you cannot describe a problem you have not hit
yet. They are just the things worth writing down next time.

---

## 2. The rewritten prompt

Reusable as-is. Replace the bracketed parts.

> **Project.** A local stock watcher: Java 17 + Javalin backend, PostgreSQL,
> Alpaca market data, vanilla-JS front end with no build step. Single user, runs
> on my machine, no authentication. Read `docs/ARCHITECTURE.md` first.
>
> **Goal.** [What you want to be true when this is done, in one sentence.]
>
> **Interface direction.** Robinhood-like: near-black canvas, one dominant price
> figure, colour reserved for direction only, full-bleed line chart with no
> gridlines, drag-to-scrub driving the header numbers. Beat it where it is
> weak — keyboard access, colour-blind safety, and information density in the
> stat rail.
>
> **Constraints.**
> - No front-end framework and no bundler; ES modules served straight from
>   `resources/public`.
> - No third-party scripts or fonts. The page must work offline.
> - Money is `BigDecimal` end to end. Never `double`.
> - Every schema change is a new numbered migration; never edit one that shipped.
> - Credentials only in `.env`. Never in a tracked file.
>
> **Environment.** Windows, PowerShell. JDK 17+ (`run.ps1` locates it). Maven
> via `demo/mvnw.cmd`. PostgreSQL on port **5433**. App on port **9090**.
> Alpaca keys have SIP market data.
>
> **Definition of done.**
> 1. `cd demo && .\mvnw.cmd clean test` passes.
> 2. The app starts and you have driven the affected screen in a browser.
> 3. No console errors; no horizontal page scroll at 375 px wide.
> 4. Behaviour is correct in light and dark, and while the market is closed.
> 5. `docs/` is updated if behaviour changed.
>
> **How to work.** Take the time it needs; I am not in a hurry. If you find a
> problem underneath the one I asked about, tell me and fix it rather than
> building on top of it. Ask before anything destructive or irreversible. Tell
> me plainly what you did not finish.

---

## 3. Writing the next one

Four things carry almost all the weight.

**Say what "done" looks like.** "Make it good" cannot be checked; "the 1D chart
must be correct before the opening bell" can. A checkable finish line is what
lets an assistant catch its own mistakes instead of handing you the first
plausible version. The 1D chart in this app has a bug class that only appears
between 04:00 and 09:30 ET — a definition of done that mentions market hours is
what finds it.

**Name the environment.** Ports, JDK versions, whether a tool is installed.
These are unguessable and each wrong assumption is a wasted round trip.

**Say what must not change.** Constraints are more useful than instructions.
"No bundler" and "money is BigDecimal" rule out whole categories of wrong
answer in eight words.

**Keep "I am not in a hurry" when you mean it.** It is the difference between a
patch and a repair. But mean it — it authorises restructuring, and on this
session it meant deleting seven source files and rebuilding the data layer.

### Worth stating explicitly

| Say this | Because |
|---|---|
| "Fix the root cause, don't work around it" | Otherwise the safe move is a workaround |
| "Ask before deleting or restructuring" | Sets the bar for checking in |
| "Tell me what you skipped" | Makes partial work visible instead of silent |
| "Show me it working, don't just tell me" | Turns "should work" into "verified" |

### One thing to avoid

Do not ask for a feature and a rewrite in the same breath without saying which
matters more. "Add alerts and make the whole thing Robinhood-like" gets you a
half-finished rewrite with alerts bolted on. Either sequence them, or say
plainly which one you would keep if only one got done.
