package stockapp.importer;

/** Reads one broker's export format. */
public interface BrokerParser {

    String broker();

    /**
     * Whether this parser recognises the file, judged from its content rather
     * than its name. Nordnet calls a tab-separated UTF-16 file {@code .csv} and
     * DNB calls a holdings report {@code rapport.xlsx}, so filenames prove
     * nothing.
     */
    boolean supports(String filename, byte[] content);

    /** @throws ImportException when the file is recognised but unreadable */
    ParsedExport parse(String filename, byte[] content);
}
