package bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

/** Each invocation builds a query, borrows a pooled connection, maps rows and commits. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"})
@Threads(1)
public class OrmBenchmark {
    @Param({"Volan", "Hibernate", "Exposed", "jOOQ", "JDBC"})
    public String orm;

    @Param({"1", "100"})
    public int rows;

    private ReadAdapter adapter;
    private int cursor;

    @Setup(Level.Trial)
    public void setup() {
        adapter = new ReadAdapter(orm);
        adapter.verify(rows);
    }

    @Benchmark
    public Object read() {
        cursor = (cursor + 101) % 9800;
        return adapter.read(cursor + 1, rows);
    }

    @TearDown(Level.Trial)
    public void close() {
        adapter.close();
    }
}
