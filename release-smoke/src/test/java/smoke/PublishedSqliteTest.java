package smoke;

import smoke.generated.VolanClient;
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader;
import io.github.thirtyeighttwentysix.volan.ir.Provider;
import io.github.thirtyeighttwentysix.volan.dialect.DialectProvider;
import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer;
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseReader;
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSync;
import io.github.thirtyeighttwentysix.volan.runtime.VolanUniqueConstraintException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ServiceLoader;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "volan.sqlite", matches = "true")
class PublishedSqliteTest {
    @TempDir
    Path directory;

    @Test
    void sqliteProviderIsDiscoveredFromThePublishedJar() throws Exception {
        var schema = Files.readString(Path.of("schema.volan")).replace("postgresql", "sqlite");
        var model = SchemaLoader.load("sqlite.volan", schema).schemaOrThrow();
        var url = "jdbc:sqlite:" + directory.resolve("published.sqlite");
        var dialect = (DdlRenderer) ServiceLoader.load(DialectProvider.class).stream()
                .map(ServiceLoader.Provider::get).filter(provider -> provider.supports(url))
                .findFirst().orElseThrow().dialect();
        var sync = new DatabaseSync(DatabaseReader.forProvider(Provider.SQLITE), dialect);
        try (var connection = DriverManager.getConnection(url)) {
            assertFalse(sync.push(connection, model).isEmpty());
            assertTrue(sync.plan(connection, model).isEmpty());
            assertTrue(sync.pull(connection).contains("provider = \"sqlite\""));
        }
        try (var client = VolanClient.builder().url(url).maxPoolSize(4).build()) {
            var user = client.getUser().create(d -> d.setEmail("sqlite@example.org"));
            assertNull(user.getName());
            assertEquals(user, client.getUser().findFirstOrThrow());
            assertThrows(VolanUniqueConstraintException.class,
                    () -> client.getUser().create(d -> d.setEmail(user.getEmail())));
            assertThrows(IllegalStateException.class, () -> client.transaction(tx -> {
                tx.getUser().create(d -> d.setEmail("rollback@example.org"));
                throw new IllegalStateException("rollback");
            }));
            assertEquals(1, client.getUser().count());
        }
    }
}
