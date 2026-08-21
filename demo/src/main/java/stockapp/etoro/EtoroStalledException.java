package stockapp.etoro;

/**
 * eToro accepted the connection and then sent nothing back.
 *
 * <p>Its own signal for "you are asking too often" - there is no 429 - and it is
 * worth its own type because the correct response is the opposite of the usual
 * one: <b>stop sending requests</b>. Observed to be keyed to the account behind
 * {@code x-user-key} rather than to the API key, and to get worse the more it is
 * poked, so retrying or continuing a batch loop deepens it instead of working
 * through it.
 */
public final class EtoroStalledException extends EtoroException {

    public EtoroStalledException(String message, Throwable cause) {
        super(message, cause);
    }
}
