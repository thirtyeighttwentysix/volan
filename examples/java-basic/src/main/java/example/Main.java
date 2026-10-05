package example;

import example.generated.User;
import example.generated.VolanClient;
import io.github.thirtyeighttwentysix.volan.migrate.MigrationDirectory;
import io.github.thirtyeighttwentysix.volan.migrate.MigrationJournal;
import io.github.thirtyeighttwentysix.volan.migrate.Migrator;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;

public final class Main {
    public static VolanClient openDatabase(String url) throws SQLException {
        try (var connection = DriverManager.getConnection(url)) {
            new Migrator(new MigrationDirectory(Path.of("migrations")), new MigrationJournal(), Clock.systemUTC())
                .apply(connection);
        }
        return VolanClient.builder().url(url).build();
    }

    public static User insertAndRead(VolanClient db, String name) {
        return db.transaction(tx -> {
            var created = tx.getUser().create(data -> data.setName(name));
            return tx.getUser().findFirstOrThrow(query -> query.where(where -> where.getId().eq(created.getId())));
        });
    }

    public static void main(String[] args) throws SQLException {
        try (var db = openDatabase("jdbc:h2:mem:java-example;DB_CLOSE_DELAY=-1")) {
            System.out.println("Saved and read: " + insertAndRead(db, "Ada").getName());
        }
    }
}
