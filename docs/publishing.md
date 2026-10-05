# Publishing Volan

The current release version is `0.1.0-alpha.2`, under `io.github.thirtyeighttwentysix`.
The [release workflow](https://github.com/thirtyeighttwentysix/volan/actions/workflows/release.yml)
verifies downloaded artifacts, signatures and independent PostgreSQL and SQLite consumers before
creating the [GitHub prerelease](https://github.com/thirtyeighttwentysix/volan/releases/tag/v0.1.0-alpha.2).
Alpha.1 remains available as the first PostgreSQL-only release.
Current development is `0.1.0-alpha.3-SNAPSHOT`, adding H2, MySQL and MariaDB runtime, introspection
and migrations, build plugins, coroutine access, statement interception and optional Micrometer metrics.

## Published artifacts

Alpha.2 contains nine library modules and `volan-bom`, including `volan-dialect-sqlite`
and its BOM constraint. The next release adds `volan-dialect-h2` and `volan-dialect-mysql` (both MySQL and MariaDB),
for eleven libraries plus the BOM. M9 adds `volan-gradle-plugin`, `volan-maven-plugin` and the Gradle
plugin marker `io.github.thirtyeighttwentysix.volan:io.github.thirtyeighttwentysix.volan.gradle.plugin`.
M10 adds optional `volan-coroutines` and `volan-micrometer` libraries and their BOM constraints,
for thirteen libraries and seventeen Maven artifacts in total. Plugins are versioned explicitly
and are not BOM constraints.
Libraries contain JVM classes,
sources and Dokka HTML documentation. POMs contain license, SCM and developer metadata. The BOM
constrains only modules that are actually published. Placeholder modules, tests and benchmarks
are excluded. The CLI ships separately as portable GitHub release archives with SHA-256 checksums;
see [CLI installation](cli-installation.md).

## Publish CLI archives

For an independent CLI preview, set `scripts/cli-release.txt` to `cli-v<version>`, commit and push,
then run the **CLI release** workflow with that version. It tests installers on Windows, Linux and
macOS, builds and tests the CLI, checks the real packaged launcher, and creates a GitHub release
with both archives, installers and `SHA256SUMS`. It does not publish to Maven Central.
The initial CLI channel is `cli-v0.1.0-alpha.3-preview.1`.

Future library tag releases also attach CLI archives. To move the installer channel to a library
release, set `scripts/cli-release.txt` to its `v<version>` tag after the archives are published.
For subsequent previews update `docs/releases/cli-preview.md` to describe the shipped changes.

## One-time credentials

The release signing public key is [release-signing-key.asc](release-signing-key.asc), fingerprint
`9BCA38B9051840A4C32009963C84C65D9B915BE4` (RSA 4096, expires September 9, 2028).
It is also available from [Ubuntu's keyserver](https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x9BCA38B9051840A4C32009963C84C65D9B915BE4).

The Central Portal account must own the verified namespace `io.github.thirtyeighttwentysix`.
Store these repository Actions secrets:

| Secret | Value |
|---|---|
| `MAVEN_CENTRAL_USERNAME` | Username portion of a Central Portal User Token |
| `MAVEN_CENTRAL_PASSWORD` | Password portion of that token, not the account password |
| `MAVEN_SIGNING_KEY` | Full ASCII-armored private OpenPGP signing key |
| `MAVEN_SIGNING_PASSWORD` | Passphrase protecting the signing key |

Publish the corresponding **public** key to a supported public keyserver and commit it as
`docs/release-signing-key.asc`. Keep an encrypted backup of the private key and passphrase outside
the checkout. Never add either to Git or workflow logs. The workflow exposes each secret only to
steps that need it.

See the official [Central signing requirements](https://central.sonatype.org/publish/requirements/gpg/)
and [Gradle publishing plugin documentation](https://vanniktech.github.io/gradle-maven-publish-plugin/central/).

## Rehearse without publishing

Run the **Release** workflow manually on `main`. A branch run performs the full build, ABI and
migration coverage checks, stages unsigned artifacts and runs an independent PostgreSQL consumer.
From alpha.2, the consumer also checks SQLite; from alpha.3 it also checks H2, MySQL and MariaDB. For a development SNAPSHOT, rehearsal removes the
`-SNAPSHOT` suffix only in staged artifacts; it never uploads them.
It also builds independent Gradle and Maven projects through the staged plugins, including ordinary
compilation, CRUD, incremental regeneration and deleted-model source cleanup.
It does not need release secrets and does not upload to Central.

The optional `candidate_version` input rehearses a specific version, such as `1.0.0-rc.1`,
without changing `gradle.properties`. It is accepted only for branch runs; tags must match the
declared project version. A successful branch run uploads `candidate-repository-<version>`
with the complete unsigned Maven repository for 14 days. This is a downloadable CI artifact,
not a published Maven Central version or a Central Portal staging deployment.

Rehearsal also builds and tests [Kotlin, Java, Spring Boot and Ktor examples](../examples/README.md)
as independent projects. Library tag releases repeat these examples using Central downloads.

For a local rehearsal (Docker required):

```shell
./gradlew publishAllPublicationsToReleaseTestRepository -Pversion=0.1.0-alpha.3 -PvolanUnsignedLocalPublication --no-configuration-cache
python scripts/verify-release.py --version 0.1.0-alpha.3
./gradlew -p release-smoke clean test "-PvolanVersion=0.1.0-alpha.3" "-PvolanRepository=/absolute/path/to/volan/build/release-repository"
python scripts/verify-plugins.py --version 0.1.0-alpha.3 --repository build/release-repository
python scripts/verify-examples.py --version 0.1.0-alpha.3 --repository build/release-repository
```

`volanUnsignedLocalPublication` also disables registration of the Central publishing tasks.
Signed staging omits this flag and supplies the signing environment variables documented by the
plugin; verification then adds `--public-key docs/release-signing-key.asc`.

## Publish a release

1. Set `version` in `gradle.properties`, add `docs/releases/<version>.md` and a dated entry in `CHANGELOG.md`.
2. Commit and push. Wait for both CI and the manual Release rehearsal to succeed.
3. Push an annotated tag matching the version, for example `v0.1.0-alpha.2`.

A tag starts the signed release workflow. It builds and tests, validates all staged artifacts and
signatures, and runs the independent consumer before calling `publishAndReleaseToMavenCentral`.
It then waits up to 30 minutes for Central downloads, verifies them and reruns the consumer using
Maven Central. Only after those checks does it create a GitHub release; prerelease versions are
marked as prereleases.

Before accessing publishing secrets, `scripts/release-version.py` refuses mismatched tags,
SNAPSHOT versions, missing release notes and missing dated changelog entries. A release tag
cannot use the rehearsal version override.

Central release versions are immutable. If a run fails **after upload**, inspect the deployment in
Central Portal and artifact URLs before retrying: a published version cannot be overwritten.
For an already published release, run the verifier with `--central` and the independent consumer
without `volanRepository`; repair the GitHub release separately instead of uploading again.
