package example;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ApplicationTest {
    @Test
    void springOwnsTheClientAndInjectsTheService() {
        var app = new SpringApplication(Application.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        var context = app.run("--volan.url=jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try {
            var users = context.getBean(UserService.class);
            assertEquals("Grace", users.create("Grace").getName());
            assertEquals(2, context.getBean(example.generated.VolanClient.class).getUser().findMany().size());
        } finally {
            context.close();
        }
        assertFalse(context.isActive());
    }
}
