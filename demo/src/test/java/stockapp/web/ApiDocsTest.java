package stockapp.web;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * docs/API.md and the registered routes describe the same API.
 *
 * <p>The routes are read from the {@code routes.get("/api/...")} calls in the
 * source rather than by starting Javalin, which would need a database. Both
 * directions are checked: a new route nobody documented, and a documented
 * route that no longer exists.
 */
class ApiDocsTest {

    /** The tests run from demo/. */
    private static final Path WEB_SOURCES = Path.of("src", "main", "java", "stockapp", "web");
    private static final Path API_DOC = Path.of("..", "docs", "API.md");

    private static final Pattern REGISTERED =
            Pattern.compile("routes\\.(get|post|put|patch|delete)\\(\"(/api/[^\"]*)\"");
    private static final Pattern DOCUMENTED =
            Pattern.compile("`(GET|POST|PUT|PATCH|DELETE) (/api/[^`?\\s]*)");

    /** The catch-all that turns an unknown /api path into a JSON 404. */
    private static final String CATCH_ALL = "GET /api/*";

    @Test
    void everyRouteIsDocumented() throws IOException {
        Set<String> documented = documentedRoutes();
        List<String> missing = new ArrayList<>();
        for (String route : registeredRoutes()) {
            if (!documented.contains(route)) {
                missing.add(route);
            }
        }
        assertEquals(List.of(), missing, "Routes registered in web/ but missing from docs/API.md");
    }

    @Test
    void everyDocumentedRouteExists() throws IOException {
        Set<String> registered = registeredRoutes();
        List<String> stale = new ArrayList<>();
        for (String route : documentedRoutes()) {
            if (!registered.contains(route)) {
                stale.add(route);
            }
        }
        assertEquals(List.of(), stale, "Routes in docs/API.md that web/ no longer registers");
    }

    /** Guards the two tests above: if the patterns stop matching, both would pass on nothing. */
    @Test
    void bothSidesAreFound() throws IOException {
        assertTrue(registeredRoutes().size() >= 30, "found only " + registeredRoutes());
        assertTrue(documentedRoutes().size() >= 30, "found only " + documentedRoutes());
    }

    /** "METHOD /path" for every route the web package registers, except the catch-all. */
    private static Set<String> registeredRoutes() throws IOException {
        Set<String> routes = new TreeSet<>();
        try (Stream<Path> files = Files.list(WEB_SOURCES)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = REGISTERED.matcher(Files.readString(file));
                while (m.find()) {
                    routes.add(m.group(1).toUpperCase(Locale.ROOT) + " " + m.group(2));
                }
            }
        }
        routes.remove(CATCH_ALL);
        return routes;
    }

    /** "METHOD /path" for every endpoint named in docs/API.md, without its query string. */
    private static Set<String> documentedRoutes() throws IOException {
        Set<String> routes = new TreeSet<>();
        Matcher m = DOCUMENTED.matcher(Files.readString(API_DOC));
        while (m.find()) {
            routes.add(m.group(1) + " " + m.group(2));
        }
        return routes;
    }
}
