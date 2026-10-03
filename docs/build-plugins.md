# CLI and build plugins

These features are on `main` for **alpha.3**; Maven Central alpha.2 does not contain them.
JDK 17+ is required. Generation validates the schema without opening a database connection or
reading the datasource environment variable. A schema still needs a `volan-kotlin` generator:

```prisma
generator client {
  provider = "volan-kotlin"
  package = "com.example.generated"
  javaFriendly = true
}
```

## CLI

Build from source with `./gradlew :volan-cli:installDist`, then put the distribution's `bin` directory
on PATH. The launchers declare the native access used by the terminal and JDBC drivers.

```bash
volan init --provider sqlite --package com.example.generated --java-friendly
volan validate
volan format
volan format --check
volan generate
```

All commands accept `--schema path/to/schema.volan`. `init` creates a starter datasource, generator
and User model and refuses to replace an existing file. Set `DATABASE_URL` before database commands;
initialization and client generation do not require it.

`validate` checks syntax and semantics and returns a nonzero exit code with source diagnostics on
errors. `format --check` fails on differences without modifying the schema; `format --stdout` prints
formatted text. Formatting only requires valid syntax, so a semantically invalid schema can still
be formatted. `--check` and `--stdout` cannot be combined.

`generate` writes to the generator's `output` (default `build/generated/volan`), relative to the
current project directory. `--output directory` overrides that location. Generated files are build
outputs. The `.volan-generated-files` manifest lets regeneration remove sources of deleted models
or renamed packages without recursively clearing a directory. Do not edit generated files or their
manifest. Collisions with unrelated files and symbolic links inside the output directory are refused.
The output root resolves ordinary filesystem aliases, including macOS system-directory links.

Database operations remain explicit: `volan db pull` and `volan db push` are described in
[Migrations](migrations.md). Compiling a project never modifies its database.

## Gradle

The plugin marker and implementation are published to Maven Central together; the Plugin Portal is
not required. Configure `mavenCentral()` in `pluginManagement.repositories` in `settings.gradle.kts`.

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal() // Kotlin compiler plugin
    }
}
```

```kotlin
// build.gradle.kts — alpha.3 example, once published
plugins {
    kotlin("jvm") version "2.4.20"
    id("io.github.thirtyeighttwentysix.volan") version "0.1.0-alpha.3"
}
repositories { mavenCentral() }
dependencies {
    implementation("io.github.thirtyeighttwentysix:volan-dialect-sqlite:0.1.0-alpha.3")
    runtimeOnly("org.xerial:sqlite-jdbc:3.53.4.0")
}
```

Run `./gradlew build` as usual. The plugin adds the matching Volan runtime and wires `volanGenerate`
into compilation of the main Kotlin source set, including Java consumers of those compiled classes.
There is no generator source set, JavaExec task or manual `dependsOn` declaration to write.
Select the database dialect and JDBC driver as ordinary dependencies.

Optional locations are module-relative:

```kotlin
volan {
    schemaFile.set(layout.projectDirectory.file("db/schema.volan"))
    outputDirectory.set(layout.buildDirectory.dir("generated/my-client"))
}
```

The output directory defaults to `build/generated/volan`. It is owned by the task, is included in
Gradle's build cache, and must not contain application sources. Build-plugin output locations
override the schema's `output` setting. Generation is incremental and supports configuration cache.
The initial plugin targets Kotlin/JVM main sources; Android and Kotlin Multiplatform are outside M9.
CI tests Gradle 9.8.0 with Kotlin 2.4.20.

## Maven

Add the Volan plugin's `generate` goal to your normal build. Its default lifecycle phase is
`generate-sources`, and it registers the generated source root automatically:

```xml
<plugin>
  <groupId>io.github.thirtyeighttwentysix</groupId>
  <artifactId>volan-maven-plugin</artifactId>
  <version>${volan.version}</version>
  <executions>
    <execution><goals><goal>generate</goal></goals></execution>
  </executions>
</plugin>
```

The generated client is Kotlin, even when called from Java. Keep the ordinary Kotlin compiler
plugin in the build with `<extensions>true</extensions>` so it compiles registered source roots;
no custom generator program or generated `sourceDirs` setting is required. This follows the
[Kotlin Maven configuration](https://kotlinlang.org/docs/maven-configure-project.html) and
[compiler source-root defaults](https://kotlinlang.org/docs/maven-kotlin-compiler.html).
Add Volan runtime, the chosen dialect and its JDBC driver as dependencies, using the same version
as the generation plugin. The BOM can align library dependencies; it does not set Maven plugin
versions. Maven 3.9+ and JDK 17+ are required.

Enable `<kotlin.compiler.incremental>true</kotlin.compiler.incremental>` in Maven project properties
so the Kotlin compiler also removes compiled classes of deleted models between builds. With
incremental compilation disabled, use `mvn clean test` after removing a model; source generation
alone cannot clean a compiler's class output. The complete example enables incremental compilation
and verifies both source and bytecode removal.

The schema defaults to `${project.basedir}/schema.volan`; generated sources go to
`${project.build.directory}/generated-sources/volan`, independently of the schema's `output`.
Override them with `<schemaFile>` / `<outputDirectory>` or `-Dvolan.schemaFile=...` /
`-Dvolan.outputDirectory=...`. Each reactor module owns its schema and output directory; sharing an
output between concurrent modules is unsupported.

## Verified examples

[Gradle consumer](../plugin-smoke/gradle/) and [Maven consumer](../plugin-smoke/maven/) compile and use
generated clients against H2 through ordinary `test` builds. Their build files contain no handwritten
generator or source-directory wiring. CI stages the actual plugin publications, resolves them in
these independent projects, repeats the Gradle build with configuration cache, changes the schema,
and checks removal of a deleted model. The release workflow runs the same checks before publication
and again against Maven Central afterwards.
The Gradle consumer also verifies generated-source restoration from build cache after `clean`.

To verify the development candidate locally:

```bash
./gradlew publishAllPublicationsToReleaseTestRepository -Pversion=0.1.0-alpha.3 -PvolanUnsignedLocalPublication --no-configuration-cache
python scripts/verify-release.py --version 0.1.0-alpha.3
python scripts/verify-plugins.py --version 0.1.0-alpha.3 --repository build/release-repository
```

The last command requires Maven on PATH and copies examples into `build/plugin-smoke` before
modifying them. It preserves the checked-in examples.
