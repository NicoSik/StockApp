package stockapp.importer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds real .xlsx bytes for the parser tests.
 *
 * <p>The fixtures are genuine workbooks rather than stubbed grids, so the zip
 * and XML handling in {@link XlsxReader} is exercised by every test that uses
 * one. Both DNB layouts are built from here.
 */
final class Workbooks {

    private Workbooks() {
    }

    /** Builds a minimal but genuine xlsx from sheet name -> grid of cells. */
    static byte[] workbook(Map<String, String[][]> sheets) {
        List<String> shared = new java.util.ArrayList<>();
        StringBuilder workbookXml = new StringBuilder(
                "<workbook xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        StringBuilder relsXml = new StringBuilder("<Relationships>");
        Map<String, String> sheetXml = new LinkedHashMap<>();

        int index = 0;
        for (Map.Entry<String, String[][]> entry : sheets.entrySet()) {
            index++;
            String rid = "rId" + index;
            workbookXml.append("<sheet name=\"").append(entry.getKey())
                    .append("\" sheetId=\"").append(index).append("\" r:id=\"").append(rid).append("\"/>");
            relsXml.append("<Relationship Id=\"").append(rid)
                    .append("\" Target=\"worksheets/sheet").append(index).append(".xml\"/>");

            StringBuilder rows = new StringBuilder("<worksheet><sheetData>");
            for (int r = 0; r < entry.getValue().length; r++) {
                rows.append("<row r=\"").append(r + 1).append("\">");
                String[] row = entry.getValue()[r];
                for (int c = 0; c < row.length; c++) {
                    String value = row[c];
                    if (value == null) {
                        continue;
                    }
                    String ref = (char) ('A' + c) + String.valueOf(r + 1);
                    if (isNumeric(value)) {
                        rows.append("<c r=\"").append(ref).append("\"><v>").append(value).append("</v></c>");
                    } else {
                        int si = shared.indexOf(value);
                        if (si < 0) {
                            shared.add(value);
                            si = shared.size() - 1;
                        }
                        rows.append("<c r=\"").append(ref).append("\" t=\"s\"><v>").append(si).append("</v></c>");
                    }
                }
                rows.append("</row>");
            }
            rows.append("</sheetData></worksheet>");
            sheetXml.put("xl/worksheets/sheet" + index + ".xml", rows.toString());
        }
        workbookXml.append("</sheets></workbook>");
        relsXml.append("</Relationships>");

        StringBuilder sharedXml = new StringBuilder("<sst>");
        for (String s : shared) {
            sharedXml.append("<si><t>").append(s.replace("&", "&amp;")).append("</t></si>");
        }
        sharedXml.append("</sst>");

        try (ByteArrayOutputStream out = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(out)) {
            write(zip, "xl/workbook.xml", workbookXml.toString());
            write(zip, "xl/_rels/workbook.xml.rels", relsXml.toString());
            write(zip, "xl/sharedStrings.xml", sharedXml.toString());
            for (Map.Entry<String, String> sheet : sheetXml.entrySet()) {
                write(zip, sheet.getKey(), sheet.getValue());
            }
            zip.finish();
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static boolean isNumeric(String value) {
        return value.matches("-?\\d+(\\.\\d+)?");
    }

    private static void write(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + content).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
