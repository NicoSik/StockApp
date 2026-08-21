package stockapp.etoro;

/** An eToro request that failed. The message is written to be shown to a user. */
public class EtoroException extends RuntimeException {

    private final int status;

    public EtoroException(String message) {
        this(message, 0);
    }

    /** @param status the HTTP status that caused this, or 0 when it was not one */
    public EtoroException(String message, int status) {
        super(message);
        this.status = status;
    }

    public EtoroException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    /** 0 when the request never got as far as a response. */
    public int status() {
        return status;
    }
}
