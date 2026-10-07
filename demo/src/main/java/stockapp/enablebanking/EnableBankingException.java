package stockapp.enablebanking;

/** Enable Banking refused a request or could not be reached. Mapped to 502. */
public class EnableBankingException extends RuntimeException {

    private final int status;

    public EnableBankingException(String message) {
        this(message, 0);
    }

    /** @param status the HTTP status that caused this, or 0 when it was not one */
    public EnableBankingException(String message, int status) {
        super(message);
        this.status = status;
    }

    public EnableBankingException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
    }

    /** 0 when the request never got as far as a response. */
    public int status() {
        return status;
    }
}
