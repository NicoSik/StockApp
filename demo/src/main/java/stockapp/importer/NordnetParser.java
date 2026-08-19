package stockapp.importer;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads Nordnet's "aksjelister" export.
 *
 * <p>The file is a trap for anything that trusts its extension. It is named
 * {@code .csv} but it is:
 *
 * <ul>
 *   <li><b>UTF-16LE</b> with a byte-order mark, not UTF-8</li>
 *   <li><b>tab</b>-delimited, not comma-delimited</li>
 *   <li>full of <b>decimal commas</b> ({@code 12,3456}), which is precisely why
 *       the delimiter cannot be a comma</li>
 * </ul>
 *
 * <p>Read it as an ordinary CSV and every field lands in the wrong column while
 * appearing to succeed.
 *
 * <p>Columns: Navn, Valuta, Antall, GAV, I dag %, Siste kurs, Belåningsverdi,
 * Verdi, Verdi NOK, Avkast. %, Avkast. NOK. Nordnet converts to NOK for us, so
 * no exchange rate is needed at import time.
 *
 * <p>There is no ISIN, so instruments are resolved from Navn - see
 * {@link stockapp.market.InstrumentResolver}.
 */
public final class NordnetParser implements BrokerParser {

    public static final String BROKER = "NORDNET";

    private static final String COL_NAME = "navn";
    private static final String COL_CURRENCY = "valuta";
    private static final String COL_QUANTITY = "antall";
    private static final String COL_AVG_COST = "gav";
    private static final String COL_LAST_PRICE = "siste kurs";
    private static final String COL_VALUE = "verdi";
    private static final String COL_VALUE_NOK = "verdi nok";

    @Override
    public String broker() {
        return BROKER;
    }

    @Override
    public boolean supports(String filename, byte[] content) {
        String text = decode(content);
        if (text.isEmpty()) {
            return false;
        }
        String header = text.split("\r?\n", 2)[0].toLowerCase();
        return header.contains(COL_NAME) && header.contains(COL_VALUE_NOK) && header.contains("\t");
    }

    @Override
    public ParsedExport parse(String filename, byte[] content) {
        String text = decode(content);
        String[] lines = text.split("\r?\n");
        if (lines.length < 2) {
            throw new ImportException("The file has no data rows.");
        }

        Map<String, Integer> columns = headerIndex(lines[0]);
        requireColumn(columns, COL_NAME);
        requireColumn(columns, COL_QUANTITY);
        requireColumn(columns, COL_VALUE_NOK);

        List<ParsedHolding> holdings = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isBlank()) {
                continue;
            }
            String[] cells = lines[i].split("\t", -1);
            String name = cell(cells, columns, COL_NAME);
            if (name == null || name.isBlank()) {
                continue;
            }
            BigDecimal quantity = number(cell(cells, columns, COL_QUANTITY));
            BigDecimal valueNok = number(cell(cells, columns, COL_VALUE_NOK));
            if (quantity == null || valueNok == null) {
                continue;
            }
            holdings.add(new ParsedHolding(
                    name.trim(),
                    null,
                    upper(cell(cells, columns, COL_CURRENCY)),
                    quantity,
                    number(cell(cells, columns, COL_AVG_COST)),
                    number(cell(cells, columns, COL_LAST_PRICE)),
                    number(cell(cells, columns, COL_VALUE)),
                    valueNok));
        }

        if (holdings.isEmpty()) {
            throw new ImportException("No holdings could be read from the file.");
        }
        // Nordnet's export states no total of its own, so there is nothing to
        // reconcile against - the row count is the only sanity check available.
        return new ParsedExport(BROKER, LocalDate.now(), filename, holdings, null);
    }

    /**
     * Decodes the file, honouring whichever byte-order mark it carries.
     *
     * <p>Nordnet writes UTF-16LE today. Falling back to UTF-8 rather than
     * assuming means a future change of encoding degrades to a readable file
     * instead of a screenful of interleaved null bytes.
     */
    static String decode(byte[] content) {
        if (content == null || content.length == 0) {
            return "";
        }
        Charset charset = StandardCharsets.UTF_8;
        int offset = 0;
        if (content.length >= 2 && (content[0] & 0xFF) == 0xFF && (content[1] & 0xFF) == 0xFE) {
            charset = StandardCharsets.UTF_16LE;
            offset = 2;
        } else if (content.length >= 2 && (content[0] & 0xFF) == 0xFE && (content[1] & 0xFF) == 0xFF) {
            charset = StandardCharsets.UTF_16BE;
            offset = 2;
        } else if (content.length >= 3
                && (content[0] & 0xFF) == 0xEF && (content[1] & 0xFF) == 0xBB && (content[2] & 0xFF) == 0xBF) {
            offset = 3;
        }
        return new String(content, offset, content.length - offset, charset).replace("﻿", "");
    }

    private static Map<String, Integer> headerIndex(String headerLine) {
        Map<String, Integer> columns = new HashMap<>();
        String[] cells = headerLine.split("\t", -1);
        for (int i = 0; i < cells.length; i++) {
            columns.put(cells[i].trim().toLowerCase().replace("﻿", ""), i);
        }
        return columns;
    }

    private static void requireColumn(Map<String, Integer> columns, String name) {
        if (!columns.containsKey(name)) {
            throw new ImportException("This does not look like a Nordnet export: no \"" + name + "\" column.");
        }
    }

    private static String cell(String[] cells, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index == null || index >= cells.length ? null : cells[index];
    }

    private static String upper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase();
    }

    /**
     * Parses a Norwegian-formatted number: comma for decimals, and a space or
     * non-breaking space for thousands.
     */
    static BigDecimal number(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim()
                .replace(" ", "")
                .replace(" ", "")
                .replace(" ", "")
                .replace(",", ".");
        if (cleaned.isEmpty() || cleaned.equals("-")) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
