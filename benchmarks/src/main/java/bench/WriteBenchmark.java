package bench;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 2, jvmArgsAppend = {"-Xms512m", "-Xmx512m", "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"})
@Threads(1)
public class WriteBenchmark {
    @Param({"Volan", "Hibernate", "Exposed", "jOOQ", "JDBC"})
    public String orm;

    @Param({"1", "100"})
    public int rows;

    private WriteAdapter adapter;
    private int cursor;

    @Setup(Level.Trial)
    public void setup() {
        adapter = new WriteAdapter(orm);
        adapter.verify(rows);
    }

    @Benchmark
    public long update() {
        cursor = (cursor + 101) % 9800;
        return adapter.update(cursor + 1, rows, cursor);
    }

    @Benchmark
    public long insertDelete() {
        return adapter.insertDelete(rows, false);
    }

    @TearDown(Level.Trial)
    public void close() {
        adapter.close();
    }
}
