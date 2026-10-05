# Kotlin

A generated client, a reviewed H2 migration, one transaction and automatic resource cleanup.

## Run

Requires JDK 17+ and the Gradle wrapper from this checkout. The example is a separate Gradle build:
it resolves Volan artifacts through the published plugin, without project dependencies or a generator program.
It targets the selected `1.0.0` release candidate. Stage that candidate from the repository root first:

```shell
./gradlew publishAllPublicationsToReleaseTestRepository -Pversion=1.0.0 -PvolanUnsignedLocalPublication --no-configuration-cache
```

Then change to `examples/kotlin-basic` and run:

```shell
../../gradlew test run -PvolanVersion=1.0.0 -PvolanRepository=../../build/release-repository
```

PowerShell equivalents:

```powershell
# From the repository root:
.\gradlew.bat publishAllPublicationsToReleaseTestRepository '-Pversion=1.0.0' -PvolanUnsignedLocalPublication --no-configuration-cache
Set-Location examples/kotlin-basic
..\..\gradlew.bat test run '-PvolanVersion=1.0.0' '-PvolanRepository=../../build/release-repository'
```

Once a compatible release is on Maven Central, omit `volanRepository` and pass its version through
`volanVersion`. Alpha.2 cannot run this example: the H2 dialect and build plugin were added later.
The Java example still uses the Kotlin compiler to compile the generated sources; all application code is Java.

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

CI runs this project from a copy outside the main build through `scripts/verify-examples.py`, using staged artifacts;
tag releases repeat the check using Maven Central downloads.
