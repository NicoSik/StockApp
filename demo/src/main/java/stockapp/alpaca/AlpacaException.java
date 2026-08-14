package stockapp.alpaca;

/** Raised when an Alpaca endpoint answers with a non-2xx status. */
public class AlpacaException extends RuntimeException {

    private final int status;
    private final String body;

    public AlpacaException(int status, String body, String message) {
        super(message);
        this.status = status;
        this.body = body;
    }

    public AlpacaException(String message, Throwable cause) {
        super(message, cause);
        this.status = 0;
        this.body = "";
    }

    public int status() {
        return status;
    }

    public String body() {
        return body;
    }

    /** True when the account is not entitled to the requested data feed. */
    public boolean isSubscriptionProblem() {
        return status == 403 || (status == 400 && body.contains("subscription"));
    }
}
