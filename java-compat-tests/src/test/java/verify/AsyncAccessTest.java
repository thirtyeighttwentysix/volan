package verify;

import com.example.blog.VolanClient;
import io.github.thirtyeighttwentysix.volan.runtime.AsyncAccess;
import io.github.thirtyeighttwentysix.volan.runtime.QueryExecutor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AsyncAccessTest {
    @Test
    void generatedCallbacksAndQueriesRunOnTheSuppliedExecutor() throws Exception {
        var worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "volan-java-test"));
        try {
            var seen = new AtomicReference<String>();
            QueryExecutor queries = (QueryExecutor) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{QueryExecutor.class}, (proxy, method, arguments) -> {
                        seen.set(Thread.currentThread().getName());
                        return List.of();
                    });
            try (var client = new VolanClient(queries, worker)) {
                var callback = new AtomicReference<String>();
                assertEquals(List.of(), client.getUser().findManyAsync(q -> {
                    callback.set(Thread.currentThread().getName());
                    q.where(w -> w.getEmail().endsWith("@example.org"));
                }).get(5, TimeUnit.SECONDS));
                assertEquals("volan-java-test", seen.get());
                assertEquals(seen.get(), callback.get());
                assertEquals(List.of(), client.getUser().findManyAsync().get(5, TimeUnit.SECONDS));
            }
            assertFalse(worker.isShutdown(), "Closing the client must not close an application executor");
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void cancellationBeforeExecutionSkipsTheOperation() {
        var queue = new ArrayDeque<Runnable>();
        var called = new AtomicBoolean();
        var future = new AsyncAccess(queue::add).submit(() -> { called.set(true); return 1; });
        assertTrue(future.cancel(false));
        queue.remove().run();
        assertFalse(called.get());
    }

    @Test
    void rejectionAndUserFailureCompleteFutures() {
        var rejected = new AsyncAccess(task -> { throw new RejectedExecutionException("closed"); });
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(CompletionException.class, () -> rejected.submit(() -> 1).join()).getCause());
        var failure = new IllegalStateException("callback failed");
        assertSame(failure, assertThrows(CompletionException.class,
                () -> new AsyncAccess(Runnable::run).submit(() -> { throw failure; }).join()).getCause());
        assertNull(new AsyncAccess(Runnable::run).submit(() -> null).join());
        var error = new AssertionError("user code failed");
        assertSame(error, assertThrows(CompletionException.class,
                () -> new AsyncAccess(Runnable::run).submit(() -> { throw error; }).join()).getCause());
    }
}
