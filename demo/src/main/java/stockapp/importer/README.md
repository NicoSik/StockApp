# importer

Reads broker export files into a normalised `ParsedExport`. One `BrokerParser`
per file format, plus a minimal hardened `.xlsx` reader.

## What's inside

- **`BrokerParser`** — the interface each format implements: `broker()`, `supports(filename, content)`, `parse(filename, content)`.
- **`NordnetParser`** — Nordnet's *Aksjelister* (UTF-16, tab-separated). `decode(bytes)` honours the byte-order mark; `number(raw)` reads Norwegian number formatting.
- **`DnbParser`** — DNB's Norwegian holdings report; checks the rows against its `Total` sheet.
- **`DnbBeholdningParser`** — DNB's English export, one sheet per asset class. `date(raw)` reads Excel serial dates.
- **`XlsxReader`** — `read(bytes)` turns a workbook into sheet → rows of strings, hardened against XXE and zip bombs. `sheet`, `headerIndex`, `at`, `columnOf` and `number` help read the grid.
- **`ParsedExport`**, **`ParsedHolding`** — the normalised result. `computedTotalNok()` sums the rows for reconciliation.
- **`ImportException`** — a file that was recognised but can't be read or reconciled (422).

## Not here

- Resolving rows to symbols (`market/`) or writing anything (`service/ImportService`).
  A parser only reads; it never touches the network or the database.

## Rules

- **Detect by content, not filename.** Nordnet calls a UTF-16 tab-separated
  file `.csv`, and DNB has two different `.xlsx` reports. `supports()` looks
  inside the file.
- **Reconcile before trusting.** Where a file carries its own totals, the
  parsed rows must add up to them, or the import is refused.
- **Untrusted input.** The `.xlsx` reader disables DOCTYPE, external entities
  and XInclude, and caps decompressed size. Keep it that way.
- A new broker is a new `BrokerParser`, registered in `ImportService.PARSERS`,
  with a test using a synthetic fixture. Real exports are gitignored.
