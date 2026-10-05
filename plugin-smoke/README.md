# Build-plugin consumers

Independent examples for the 1.0.0 Gradle and Maven plugins. They use published Maven
coordinates, not project substitutions. Each ordinary `test` build generates and compiles the
client before exercising H2 CRUD. No database URL is needed during generation.

From the repository root:

```bash
./gradlew -p plugin-smoke/gradle test
mvn -f plugin-smoke/maven/pom.xml test
```

For staged artifacts use `scripts/verify-plugins.py`, described in
[CLI and build plugins](../docs/build-plugins.md). The script also checks configuration-cache reuse,
schema changes and model removal in disposable copies of these projects.

The Gradle consumer also resolves the optional coroutine and Micrometer publications, runs generated
CRUD through `suspendQuery`, and checks statement metrics. No direct project dependencies are used.
