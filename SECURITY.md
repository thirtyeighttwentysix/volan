# Security policy

## Supported versions

Volan is pre-1.0 and under active development. Until 1.0 is released, only the `main` branch receives
security fixes. After 1.0, the latest minor release of the current major version will be supported.

## Reporting a vulnerability

**Please do not report security issues through public GitHub issues, discussions or pull requests.**

Use GitHub's private vulnerability reporting for this repository:
<https://github.com/thirtyeighttwentysix/volan/security/advisories/new>

Please include:

- the affected module and version (or commit),
- a description of the issue and its impact,
- a minimal reproduction — a schema fragment, a query and the resulting SQL are ideal,
- any known mitigation.

You can expect an acknowledgement within 5 working days and an assessment with a planned remediation
timeline within 15 working days. We will keep you informed while a fix is prepared, and we will credit
you in the advisory unless you prefer otherwise.

## Scope

Issues that are in scope include, among others:

- any way to influence generated SQL through data (SQL injection),
- credential or connection-string disclosure in logs, error messages or generated artefacts,
- a migration path that can destroy or expose data without warning,
- a code-generation path that emits code executing untrusted input.

Out of scope: vulnerabilities in third-party databases or JDBC drivers (report those upstream), and
findings that require an attacker who already controls the application's own schema file or build.

## Security practices in the project

- All user values are bound as statement parameters; SQL text is never built by concatenating values.
- Database URLs are read through `env()` in the schema; secrets are never stored in the repository.
- Dependencies are updated automatically and reviewed before merge.
