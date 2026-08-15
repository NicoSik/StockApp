package stockapp.etoro;

/** An eToro request that failed. The message is written to be shown to a user. */
public class EtoroException extends RuntimeException {

    public EtoroException(String message) {
        super(message);
    }

    public EtoroException(String message, Throwable cause) {
        super(message, cause);
    }
}
