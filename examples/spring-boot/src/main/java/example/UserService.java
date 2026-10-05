package example;

import example.generated.User;
import example.generated.VolanClient;

public final class UserService {
    private final VolanClient database;

    public UserService(VolanClient database) {
        this.database = database;
    }

    public User create(String name) {
        return database.transaction(tx -> {
            var user = tx.getUser().create(data -> data.setName(name));
            return tx.getUser().findFirstOrThrow(query -> query.where(where -> where.getId().eq(user.getId())));
        });
    }
}
