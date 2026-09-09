# Java API

Volan generates Kotlin and Java entry points from the same schema and query implementation.
Enable the additional Java entry points in your generator:

```prisma
generator client {
  provider     = "volan-kotlin"
  package      = "com.example.blog"
  output       = "build/generated/volan"
  javaFriendly = true
}
```

The generated sources are Kotlin and must be compiled before the Java code that uses them.
Build-plugin integration arrives in M9; [codegen-verify](../codegen-verify/build.gradle.kts) demonstrates
generation as a Gradle task today. Compile generated sources with the Kotlin option
`-Xemit-jvm-type-annotations` to retain JSpecify annotations on nullable types in the Java bytecode.

## Queries and writes

Repositories and fields use Java getters. Configuration callbacks are `Consumer<Scope>` and return
nothing; scalar write values and paging use setters. Kotlin keeps its receiver-lambda syntax.

```java
try (VolanClient db = VolanClient.builder()
        .url(System.getenv("DATABASE_URL"))
        .build()) {
    User created = db.getUser().create(data -> {
        data.setEmail("alice@acme.com");
        data.setName("Alice");
    });

    List<User> users = db.getUser().findMany(q -> {
        q.where(w -> w.getEmail().endsWith("@acme.com"));
        q.orderBy(o -> o.getId().desc());
        q.include(i -> i.posts(p -> p.setTake(5)));
        q.setTake(20);
    });

    db.getUser().update(change -> {
        change.where(w -> w.getId().eq(created.getId()));
        change.data(data -> data.setName(null));
    });
}
```

Methods with default arguments have Java overloads: `findMany()`, `findFirst()`, `count()`, `exists()`,
`include.posts()`, `cursor(id)` and `rawExecute(sql)` work without placeholder callbacks or arguments.
`findFirst` and `findUnique` return nullable entities; their `OrThrow` variants throw
`VolanNotFoundException` when no row matches.

| Operation | Java configuration |
|---|---|
| Filter | `q.where(w -> w.getEmail().contains("acme"))` |
| Boolean group | `w.or(o -> { o.getId().eq(1); o.getId().eq(2); })` |
| Relation filter | `w.posts(p -> p.some(post -> post.getViews().gt(0)))` |
| Projection | `q.select(s -> { s.email(); s.name(); })` with `projectMany` / `projectFirst` |
| Distinct | `q.distinct(fields -> fields.role())` |
| Nested create | `data.getPosts().create(post -> post.setTitle("Hello"))` |
| Bulk create | `many.row(data -> data.setEmail("bob@acme.com"))` inside `createMany` |
| Summary | `aggregate(a -> { a.count(); a.sum(s -> s.id()); })` |
| Grouping | `groupBy(g -> { g.by(b -> b.role()); g.count(); })` |

Entities expose getters, value equality and a static `builder()`. A relation must be requested with
`include` before its getter can be read; omitted projection fields also throw when read. The same
rule applies to reflection-based serializers: expose an explicitly selected DTO, or configure the
serializer to ignore relations that were not loaded.

## Asynchronous operations

Every repository operation has a `*Async` equivalent returning `CompletableFuture`, as do the
client's `transaction`, `rawQuery` and `rawExecute` operations. This dispatches **blocking JDBC** work;
it does not change the driver into a nonblocking driver.

```java
ExecutorService workers = Executors.newFixedThreadPool(4);
try (VolanClient db = VolanClient.builder()
        .url(System.getenv("DATABASE_URL"))
        .asyncExecutor(workers)
        .build()) {
    CompletableFuture<List<User>> result = db.getUser().findManyAsync(q -> {
        q.where(w -> w.getEmail().endsWith("@acme.com"));
        q.setTake(20);
    });
    List<User> users = result.join();
} finally {
    workers.shutdown();
}
```

The default executor is `ForkJoinPool.commonPool()`. For a service, supply a dedicated executor sized
for its database pool and workload. Volan never shuts down a supplied executor. Finish outstanding
operations before closing the client. Configuration callbacks also run on the executor, so do not
modify their captured inputs while the operation is pending.

Query failures, callback failures and executor rejection complete the future exceptionally.
`join()` wraps a failure in `CompletionException`; `get()` uses `ExecutionException`.
Cancelling a future before execution skips the queued operation. Cancellation after JDBC starts
does **not** cancel the statement or roll back its effects; statement cancellation is part of M10.

## Transactions

```java
User user = db.transactionAsync(Isolation.READ_COMMITTED, tx -> {
    User created = tx.getUser().create(d -> d.setEmail("tx@acme.com"));
    tx.getProfile().create(d -> {
        d.setUserId(created.getId());
        d.setBio("Created in the same transaction");
    });
    return created;
}).join();
```

The complete callback runs on one worker thread, and its synchronous queries share one connection.
Success commits; failure rolls back. Nested synchronous transactions use savepoints. Isolation and
retry policies are available through the same overloads as synchronous transactions.

Calling an async operation from inside a connected client's transaction returns a failed future:
a thread-bound transaction cannot follow work onto another thread. Use synchronous calls inside
`transactionAsync`, and return the final value from the callback, not another future.
Clients constructed over a bare `QueryExecutor` have no connection or transaction ownership;
use the connected client when transaction guards are required.

## Nullability and verification

Generated classes and the public core, runtime and dialect contracts are `@NullMarked`. Nullable
types carry JSpecify `@Nullable`, including collection elements and nullable future results.
An empty `findFirstAsync` completes normally with a null payload. Nullability annotations describe
the contract for static analyzers; they do not add a second runtime validation layer.

`./gradlew :java-compat-tests:check` compiles and runs Java-only tests, exercises PostgreSQL when
Docker is available, checks runtime ABI dumps, and inspects generated class signatures (including
generic arguments and bounds). Kotlin receiver methods are synthetic in the Java-facing build;
Java sees the generated `Consumer` overloads. The signature checker excludes compiler-generated
synthetic members and Kotlin's enum `getEntries()` accessor; Java enums expose standard `values()`.
With `javaFriendly = false`, the additional Java callbacks are omitted and the Kotlin DSL remains.
