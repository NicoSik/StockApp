package stockapp.web;

/** A path that refers to something that does not exist. Mapped to 404. */
public class NotFound extends RuntimeException {
    public NotFound(String message) {
        super(message);
    }
}
