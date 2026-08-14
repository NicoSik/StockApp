package stockapp.importer;

/** A file that could not be imported. The message is shown to the user. */
public class ImportException extends RuntimeException {

    public ImportException(String message) {
        super(message);
    }

    public ImportException(String message, Throwable cause) {
        super(message, cause);
    }
}
