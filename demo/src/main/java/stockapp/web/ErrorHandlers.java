package stockapp.web;

import io.javalin.config.RoutesConfig;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stockapp.alpaca.AlpacaException;
import stockapp.enablebanking.EnableBankingException;
import stockapp.etoro.EtoroException;
import stockapp.importer.ImportException;
import stockapp.repo.PortfolioRepo;

import java.util.Map;

/**
 * Maps every exception the API can raise to a status code and a uniform
 * {@code {"error": "..."}} body, so the client has exactly one shape to handle.
 *
 * <p>All in one place so the full status-code contract (see {@code docs/API.md})
 * can be read top to bottom. Javalin picks the most specific registered type,
 * so registration order does not matter.
 */
public final class ErrorHandlers {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlers.class);

    private ErrorHandlers() {
    }

    public static void register(RoutesConfig routes) {
        routes.exception(BadRequest.class, (e, ctx) ->
                fail(ctx, HttpStatus.BAD_REQUEST, e.getMessage()));

        routes.exception(NotFound.class, (e, ctx) ->
                fail(ctx, HttpStatus.NOT_FOUND, e.getMessage()));

        // Valid requests the app refused - an order without the buying power,
        // or a broker file it could not reconcile. Not bugs.
        routes.exception(PortfolioRepo.TradeRejected.class, (e, ctx) ->
                fail(ctx, HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage()));
        routes.exception(ImportException.class, (e, ctx) ->
                fail(ctx, HttpStatus.UNPROCESSABLE_CONTENT, e.getMessage()));

        // Upstream failures. eToro's and Enable Banking's messages are written
        // for a person (bad keys, rate limit, an expired bank login); Alpaca's
        // are not, so a generic one goes out.
        routes.exception(EtoroException.class, (e, ctx) -> {
            log.warn("eToro failure on {} {}: {}", ctx.method(), ctx.path(), e.getMessage());
            fail(ctx, HttpStatus.BAD_GATEWAY, e.getMessage());
        });
        routes.exception(EnableBankingException.class, (e, ctx) -> {
            log.warn("Enable Banking failure on {} {}: {}", ctx.method(), ctx.path(), e.getMessage());
            fail(ctx, HttpStatus.BAD_GATEWAY, e.getMessage());
        });
        routes.exception(AlpacaException.class, (e, ctx) -> {
            log.warn("Alpaca failure on {} {}: {}", ctx.method(), ctx.path(), e.getMessage());
            fail(ctx, HttpStatus.BAD_GATEWAY, "The market data provider is not responding. Try again shortly.");
        });

        routes.exception(Exception.class, (e, ctx) -> {
            // Unexpected: the stack trace goes to the log, a plain message out.
            log.error("Unhandled error on {} {}", ctx.method(), ctx.path(), e);
            fail(ctx, HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong handling that request.");
        });
    }

    private static void fail(Context ctx, HttpStatus status, String message) {
        ctx.status(status).json(Map.of("error", message == null ? status.getMessage() : message));
    }
}
