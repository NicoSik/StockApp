package stockapp.importer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A minimal .xlsx reader: enough to pull a grid of strings out of a sheet.
 *
 * <p>An xlsx file is a zip of XML parts, and reading one is a few dozen lines
 * of JDK code. Apache POI would do it too, but it is a large dependency with a
 * substantial transitive tail, and this app needs to read exactly one report
 * from one bank.
 *
 * <p>Only what the importer needs is supported: shared strings, inline strings
 * and numbers. Formulas resolve to their cached value, which is what a bank
 * export contains anyway. Styles, dates and formatting are ignored.
 */
final class XlsxReader {

    /** Guards against a zip bomb in a user-supplied file. */
    private static final long MAX_ENTRY_BYTES = 32L * 1024 * 1024;

    private XlsxReader() {
    }

    /** Sheet name to rows, each row a list of cell values in column order. */
    static Map<String, List<List<String>>> read(byte[] content) {
        Map<String, byte[]> parts = unzip(content);

        List<String> sharedStrings = readSharedStrings(parts.get("xl/sharedStrings.xml"));
        Map<String, String> relIdToTarget = readRelationships(parts.get("xl/_rels/workbook.xml.rels"));
        Map<String, String> sheetNameToRelId = readSheetIndex(parts.get("xl/workbook.xml"));

        Map<String, List<List<String>>> sheets = new LinkedHashMap<>();
        int fallbackIndex = 0;
        for (Map.Entry<String, String> entry : sheetNameToRelId.entrySet()) {
            String target = relIdToTarget.get(entry.getValue());
            // Some writers omit the relationship; fall back to sheet order.
            String path = target == null
                    ? "xl/worksheets/sheet" + (++fallbackIndex) + ".xml"
                    : "xl/" + target.replaceFirst("^/?xl/", "");
            byte[] sheetXml = parts.get(path);
            if (sheetXml != null) {
                sheets.put(entry.getKey(), readSheet(sheetXml, sharedStrings));
            }
        }
        return sheets;
    }

    private static Map<String, byte[]> unzip(byte[] content) {
        Map<String, byte[]> parts = new HashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                parts.put(entry.getName(), readAtMost(zip));
            }
        } catch (IOException e) {
            throw new ImportException("The file is not a readable Excel workbook.", e);
        }
        if (parts.isEmpty()) {
            throw new ImportException("The file is not a readable Excel workbook.");
        }
        return parts;
    }

    private static byte[] readAtMost(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > MAX_ENTRY_BYTES) {
                throw new ImportException("A part of the workbook is unreasonably large; refusing to read it.");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static List<String> readSharedStrings(byte[] xml) {
        List<String> strings = new ArrayList<>();
        if (xml == null) {
            return strings;
        }
        NodeList items = parse(xml).getElementsByTagName("si");
        for (int i = 0; i < items.getLength(); i++) {
            strings.add(concatText((Element) items.item(i)));
        }
        return strings;
    }

    /** A shared string may be split across runs; concatenate every t node. */
    private static String concatText(Element parent) {
        StringBuilder text = new StringBuilder();
        NodeList nodes = parent.getElementsByTagName("t");
        for (int i = 0; i < nodes.getLength(); i++) {
            text.append(nodes.item(i).getTextContent());
        }
        return text.toString();
    }

    private static Map<String, String> readRelationships(byte[] xml) {
        Map<String, String> targets = new HashMap<>();
        if (xml == null) {
            return targets;
        }
        NodeList nodes = parse(xml).getElementsByTagName("Relationship");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            targets.put(element.getAttribute("Id"), element.getAttribute("Target"));
        }
        return targets;
    }

    private static Map<String, String> readSheetIndex(byte[] xml) {
        Map<String, String> sheets = new LinkedHashMap<>();
        if (xml == null) {
            return sheets;
        }
        NodeList nodes = parse(xml).getElementsByTagName("sheet");
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            sheets.put(element.getAttribute("name"), element.getAttribute("r:id"));
        }
        return sheets;
    }

    private static List<List<String>> readSheet(byte[] xml, List<String> sharedStrings) {
        List<List<String>> rows = new ArrayList<>();
        NodeList rowNodes = parse(xml).getElementsByTagName("row");

        for (int r = 0; r < rowNodes.getLength(); r++) {
            Element row = (Element) rowNodes.item(r);
            List<String> cells = new ArrayList<>();
            NodeList cellNodes = row.getElementsByTagName("c");

            for (int c = 0; c < cellNodes.getLength(); c++) {
                Element cell = (Element) cellNodes.item(c);
                // Cells are sparse: an empty column is simply absent, so place
                // each value at the column its reference names.
                int column = columnOf(cell.getAttribute("r"));
                while (cells.size() < column) {
                    cells.add(null);
                }
                cells.add(valueOf(cell, sharedStrings));
            }
            rows.add(cells);
        }
        return rows;
    }

    private static String valueOf(Element cell, List<String> sharedStrings) {
        String type = cell.getAttribute("t");
        if ("inlineStr".equals(type)) {
            return concatText(cell);
        }
        NodeList values = cell.getElementsByTagName("v");
        if (values.getLength() == 0) {
            return null;
        }
        String raw = values.item(0).getTextContent();
        if ("s".equals(type)) {
            try {
                int index = Integer.parseInt(raw.trim());
                return index >= 0 && index < sharedStrings.size() ? sharedStrings.get(index) : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return raw;
    }

    /** "B3" -> 1. Handles multi-letter columns (AA, AB...). */
    static int columnOf(String reference) {
        int column = 0;
        for (int i = 0; i < reference.length(); i++) {
            char c = reference.charAt(i);
            if (c < 'A' || c > 'Z') {
                break;
            }
            column = column * 26 + (c - 'A' + 1);
        }
        return Math.max(0, column - 1);
    }

    private static Document parse(byte[] xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // These files come from outside the app. Disable external entity
            // resolution so a crafted workbook cannot read local files or make
            // the parser issue network requests.
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            factory.setXIncludeAware(false);
            factory.setNamespaceAware(false);

            DocumentBuilder builder = factory.newDocumentBuilder();
            Document document = builder.parse(new ByteArrayInputStream(xml));
            document.getDocumentElement().normalize();
            return document;
        } catch (Exception e) {
            throw new ImportException("The workbook contains XML that could not be read.", e);
        }
    }

    /** Trimmed cell value, or null when absent or blank. */
    static String at(List<String> row, int index) {
        if (row == null || index < 0 || index >= row.size()) {
            return null;
        }
        String value = row.get(index);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** The named sheet, matched without regard to case or padding; null if absent. */
    static List<List<String>> sheet(Map<String, List<List<String>>> sheets, String name) {
        for (Map.Entry<String, List<List<String>>> entry : sheets.entrySet()) {
            if (entry.getKey().trim().toLowerCase(Locale.ROOT).equals(name)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** A header row as lowercased column name to index, so column order is irrelevant. */
    static Map<String, Integer> headerIndex(List<String> header) {
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            String cell = header.get(i);
            if (cell != null && !cell.isBlank()) {
                columns.put(cell.trim().toLowerCase(Locale.ROOT), i);
            }
        }
        return columns;
    }

    /** Excel stores numbers with a dot; be tolerant of a comma regardless. */
    static BigDecimal number(String raw) {
        if (raw == null) {
            return null;
        }
        String cleaned = raw.trim().replace(" ", "").replace("\u00a0", "").replace(",", ".");
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
