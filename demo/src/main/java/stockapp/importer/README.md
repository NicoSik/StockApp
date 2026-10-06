# importer

Reads broker export files into a normalised `ParsedExport`. One `BrokerParser`
per file format, plus a minimal hardened `.xlsx` reader.

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
