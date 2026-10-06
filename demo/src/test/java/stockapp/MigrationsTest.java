package stockapp;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Db.MIGRATIONS lists exactly the migration files on disk, in order.
 *
 * <p>The list is explicit because a shaded jar cannot list a classpath
 * directory, so a file that is added but not listed is silently never applied.
 */
class MigrationsTest {

    /** The tests run from demo/. */
    private static final Path MIGRATION_DIR = Path.of("src", "main", "resources", "db", "migration");
    private static final Pattern NAME = Pattern.compile("V(\\d{3})__[a-z0-9_]+\\.sql");

    @Test
    void listMatchesTheFilesOnDisk() throws IOException {
        assertEquals(filesOnDisk(), List.of(Db.MIGRATIONS),
                "Db.MIGRATIONS must list every .sql file in db/migration, in order");
    }

    @Test
    void filesAreNamedAndNumberedInSequence() throws IOException {
        List<String> files = filesOnDisk();
        assertFalse(files.isEmpty(), "no migrations found in " + MIGRATION_DIR.toAbsolutePath());
        for (int i = 0; i < files.size(); i++) {
            Matcher m = NAME.matcher(files.get(i));
            assertTrue(m.matches(), files.get(i) + " does not match V0NN__lower_snake_case.sql");
            assertEquals(i + 1, Integer.parseInt(m.group(1)),
                    files.get(i) + " breaks the sequence: expected V" + String.format("%03d", i + 1));
        }
    }

    private static List<String> filesOnDisk() throws IOException {
        List<String> names = new ArrayList<>();
        try (Stream<Path> files = Files.list(MIGRATION_DIR)) {
            files.map(p -> p.getFileName().toString())
                    .filter(name -> name.endsWith(".sql"))
                    .sorted()
                    .forEach(names::add);
        }
        return names;
    }
}
