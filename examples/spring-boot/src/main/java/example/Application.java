package example;

import example.generated.VolanClient;
import io.github.thirtyeighttwentysix.volan.migrate.MigrationDirectory;
import io.github.thirtyeighttwentysix.volan.migrate.MigrationJournal;
import io.github.thirtyeighttwentysix.volan.migrate.Migrator;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication(proxyBeanMethods = false)
public class Application {
    @Bean(destroyMethod = "close")
    VolanClient database(@Value("${volan.url:jdbc:h2:mem:spring-example;DB_CLOSE_DELAY=-1}") String url) throws SQLException {
        try (var connection = DriverManager.getConnection(url)) {
            new Migrator(new MigrationDirectory(Path.of("migrations")), new MigrationJournal(), Clock.systemUTC())
                .apply(connection);
        }
        return VolanClient.builder().url(url).build();
    }

    @Bean
    UserService users(VolanClient database) {
        return new UserService(database);
    }

    @Bean
    ApplicationRunner demonstration(UserService users) {
        return args -> System.out.println("Saved and read: " + users.create("Ada").getName());
    }

    public static void main(String[] args) {
        try (var context = SpringApplication.run(Application.class, args)) {
            // This console example exits after its ApplicationRunner; closing the context closes Volan.
        }
    }
}
