package bench;

import org.openjdk.jmh.annotations.Threads;

/** Four callers share the same four-connection pool and adapter. */
@Threads(4)
public class ConcurrentReadBenchmark extends OrmBenchmark {}
