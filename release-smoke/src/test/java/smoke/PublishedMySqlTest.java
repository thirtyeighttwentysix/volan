package smoke;

import smoke.generated.VolanClient;
import io.github.thirtyeighttwentysix.volan.dialect.DialectProvider;
import io.github.thirtyeighttwentysix.volan.dialect.DdlRenderer;
import io.github.thirtyeighttwentysix.volan.ir.Provider;
import io.github.thirtyeighttwentysix.volan.ir.SchemaLoader;
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseReader;
import io.github.thirtyeighttwentysix.volan.migrate.DatabaseSync;
import io.github.thirtyeighttwentysix.volan.runtime.VolanUniqueConstraintException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.mariadb.MariaDBContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ServiceLoader;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "volan.mysql", matches = "true")
class PublishedMySqlTest {
    @Test
    void publishedModuleSupportsBothDriversAndJavaCrud() throws Exception {
        for (JdbcDatabaseContainer<?> server : new JdbcDatabaseContainer<?>[] {
                new MySQLContainer("mysql:8.4"), new MariaDBContainer("mariadb:11.4") }) {
            try (server) {
                server.start();
                var dialect = (DdlRenderer) ServiceLoader.load(DialectProvider.class).stream()
                        .map(ServiceLoader.Provider::get).filter(p -> p.supports(server.getJdbcUrl()))
                        .findFirst().orElseThrow().dialect();
                var schema = SchemaLoader.load("mysql.volan", Files.readString(Path.of("schema.volan"))
                        .replace("postgresql", dialect.getId())).schemaOrThrow();
                try (var connection = DriverManager.getConnection(server.getJdbcUrl(), server.getUsername(), server.getPassword())) {
                    var sync = new DatabaseSync(DatabaseReader.forProvider(Provider.fromId(dialect.getId())), dialect);
                    sync.push(connection, schema);
                    assertTrue(sync.plan(connection, schema).isEmpty());
                }
                try (var client = VolanClient.builder().url(server.getJdbcUrl())
                        .username(server.getUsername()).password(server.getPassword()).build()) {
                    var user = client.getUser().create(d -> d.setEmail("mysql@example.org"));
                    var changed = client.getUser().update(d -> {
                        d.where(w -> w.getId().eq(user.getId()));
                        d.data(v -> v.setName("updated"));
                    });
                    assertEquals("updated", changed.getName());
                    assertThrows(VolanUniqueConstraintException.class,
                            () -> client.getUser().create(d -> d.setEmail(user.getEmail())));
                    assertEquals(changed, client.getUser().delete(d -> d.where(w -> w.getId().eq(user.getId()))));
                    assertEquals(0, client.getUser().count());
                }
            }
        }
    }
}
