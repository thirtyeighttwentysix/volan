package verify;

import com.example.h2.KeywordRecord;
import com.example.h2.KeywordState;
import com.example.h2.VolanClient;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class KeywordFieldTest {
    @Test
    void generatedJavaApiKeepsKeywordNames() {
        try (var client = VolanClient.builder().url("jdbc:h2:mem:" + UUID.randomUUID()).maxPoolSize(1).build()) {
            client.rawExecute("""
                    CREATE TABLE "KeywordRecord" (
                        "record_id" INT PRIMARY KEY,
                        "count" INT NOT NULL,
                        "as" VARBINARY,
                        "state" VARCHAR NOT NULL,
                        "parentId" INT
                    )
                    """);
            var records = client.getKeywordRecord();
            var written = records.create(data -> {
                data.setWhen(1);
                data.setIn(7);
                data.setState(KeywordState.object);
            });
            var expected = KeywordRecord.builder().when(1).in(7).state(KeywordState.object).build();
            assertEquals(expected, written);
            assertEquals(expected, records.findFirstOrThrow(query -> query.where(where -> where.getWhen().eq(1))));
            var projected = records.projectFirst(query -> query.select(select -> select.in()));
            assertEquals(7, projected.getIn());
        }
    }
}
