package bench;

import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Correctness smoke test; deliberately produces no performance scores. */
public final class VerifyAdapters {
    public static void main(String[] args) throws Exception {
        for (String orm : new String[]{"Volan", "Hibernate", "Exposed", "jOOQ", "JDBC"}) {
            try (ReadAdapter reads = new ReadAdapter(orm)) {
                reads.verify(1);
                reads.verify(100);
                var workers = Executors.newFixedThreadPool(4);
                try {
                    var checks = new ArrayList<Callable<Void>>();
                    for (int i = 0; i < 4; i++) {
                        checks.add(() -> { reads.verify(1); reads.verify(100); return null; });
                    }
                    for (var result : workers.invokeAll(checks)) result.get();
                } finally {
                    workers.shutdownNow();
                    if (!workers.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("Readers did not stop");
                }
            }
            try (WriteAdapter writes = new WriteAdapter(orm)) {
                writes.verify(1);
                writes.verify(100);
            }
            System.out.println(orm + ": reads, concurrent reads, updates and insert/delete verified");
        }
    }
}
