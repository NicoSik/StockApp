package stockapp.web;

/**
 * A malformed or missing request field. Mapped to 400; the message reaches the
 * user, so it should say what to fix.
 */
public class BadRequest extends RuntimeException {
    public BadRequest(String message) {
        super(message);
    }
}
