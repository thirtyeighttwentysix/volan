# Spring Boot

A Spring-owned client bean and constructor-injected service. This is a console application, not an HTTP server.

## Run

Requires JDK 17+ and the Gradle wrapper from this checkout. The example is a separate Gradle build:
it resolves Volan artifacts through the published plugin, without project dependencies or a generator program.
It resolves Volan 1.0.0 from Maven Central. From the repository root:

```shell
./gradlew -p examples/spring-boot test run
```

PowerShell:

```powershell
.\gradlew.bat -p examples/spring-boot test run
```

Alpha.2 cannot run this example: the H2 dialect and build plugin were added later.
Java application code uses the Kotlin compiler for the generated client sources.

## What runs

`schema.volan` generates `example.generated.VolanClient` during compilation. The committed migration
creates the table before the client starts. The sample uses an H2 memory database with `DB_CLOSE_DELAY=-1`
so the database survives between the migration connection and the client pool; all data disappears at JVM exit.
No database service, environment variables or globally installed CLI are needed.

For deployment, keep reviewed migration files and apply them once before starting request handling.
Use a configured persistent JDBC URL and credentials; the embedded URL here is for a runnable example.
Volan transactions use `database.transaction`; these examples do not claim integration with Spring's `@Transactional`.

The application prints `Saved and read: Ada` and exits. The test exercises a real H2 database:
the basic examples verify rollback, and the Spring example starts a real application context and verifies injection and shutdown.

Framework version: [Spring Boot 4.1.1](https://docs.spring.io/spring-boot/system-requirements.html).

CI runs this project from a copy outside the main build through `scripts/verify-examples.py`, using staged artifacts;
tag releases repeat the check using Maven Central downloads.
