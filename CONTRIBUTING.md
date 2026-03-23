# Contributing to Pantera

Thank you for your interest in contributing to **Pantera Artifact Registry** by Auto1 Group.
This document covers everything you need to get started, from environment setup through
submitting a pull request.

---

## Prerequisites

| Tool   | Minimum Version | Notes                                              |
|--------|----------------:|------------------------------------------------------|
| JDK    |            21+  | OpenJDK or Eclipse Temurin recommended               |
| Maven  |           3.4+  | Enforced by `maven-enforcer-plugin`                  |
| Docker |        latest   | Required for integration tests (TestContainers)      |
| Git    |        latest   | For version control                                  |

---

## Project Setup

### 1. Clone the repository

```bash
git clone https://github.com/auto1-oss/pantera.git
cd pantera
```

### 2. Build the project

```bash
mvn clean verify
```

### 3. IDE setup

**IntelliJ IDEA (recommended)**

- Import as a Maven project (`File > Open > pom.xml`).
- Set Project SDK to JDK 21.
- Enable annotation processing if prompted.
- The multi-module structure will be auto-detected from the root `pom.xml`.

**VS Code**

- Install the "Extension Pack for Java" and "Maven for Java" extensions.
- Open the project root folder.

---

## Development Workflow

1. **Fork** the repository on GitHub.
2. **Create a feature branch** from `master`.
3. **Make changes** -- write code, add tests.
4. **Run tests** locally (see [Testing Requirements](#testing-requirements)).
5. **Commit** using [Conventional Commits](#commit-messages).
6. **Push** your branch and open a **Pull Request**.

---

## Building

### Full build (compile + test + static analysis + license check)

```bash
mvn clean verify
```

### Fast build (skip tests and PMD)

```bash
mvn install -DskipTests -Dpmd.skip=true
```

### Multi-threaded build

```bash
mvn clean install -U -DskipTests -T 1C
```

### Single module build

Build a single module (and its dependencies) from the project root:

```bash
mvn install -pl maven-adapter -am -DskipTests
```

Replace `maven-adapter` with the target module name.

---

## Running Locally

### Docker Compose

The full local stack (Pantera, PostgreSQL, Keycloak, Valkey, Prometheus, Grafana, Nginx) can
be started with Docker Compose:

```bash
cd pantera-main/docker-compose
docker compose up -d
```

Default ports:

| Service      | Port  |
|--------------|------:|
| Pantera API  | 8086  |
| Pantera UI   | 8090  |
| Keycloak     | 8080  |
| PostgreSQL   | 5432  |
| Valkey       | 6379  |
| Prometheus   | 9090  |
| Grafana      | 3000  |
| Nginx (HTTP) | 8081  |
| Nginx (HTTPS)| 8443  |

### Direct execution

For running Pantera directly from IntelliJ IDEA or the command line:

1. Review the example configuration in `pantera-main/examples/pantera.yml` and adjust
   paths for your local environment.
2. Add the `--config-file` parameter pointing to your configuration file.

```bash
java -cp pantera.jar:lib/* \
  com.auto1.pantera.VertxMain \
  --config-file=/path/to/pantera.yml \
  --port=8080 \
  --api-port=8086
```

Check the logs for running repositories, REST API URL, and test user credentials.

---

## Testing Requirements

### Unit tests

```bash
mvn test
```

- Run by **maven-surefire-plugin**.
- Must not depend on any external component (no Docker, no network, no database).
- Test classes must be named `*Test.java`.

### Integration tests

```bash
mvn verify -Pitcase
```

- Run by **maven-failsafe-plugin** under the `itcase` profile.
- Require Docker (tests use TestContainers).
- Test classes must be named `*IT.java` or `*ITCase.java`.

### Database tests

- Automatically provision a PostgreSQL instance via TestContainers.
- No manual database setup is required.

### Valkey tests

- Gated by the `VALKEY_HOST` environment variable.
- Tests are annotated with `@EnabledIfEnvironmentVariable(named = "VALKEY_HOST", matches = ".+")`.
- To run locally, set `VALKEY_HOST` to point to a running Valkey instance:

```bash
VALKEY_HOST=localhost mvn test -pl pantera-core
```

### Test naming conventions

| Pattern          | Plugin     | Purpose             |
|------------------|------------|---------------------|
| `*Test.java`     | Surefire   | Unit tests          |
| `*IT.java`       | Failsafe   | Integration tests   |
| `*ITCase.java`   | Failsafe   | Integration tests   |

---

## Code Style

### PMD static analysis

Code style is enforced by `maven-pmd-plugin` using the project ruleset at
`build-tools/src/main/resources/pmd-ruleset.xml`. The build fails on any PMD violation.

Key PMD rules include:

- Cyclomatic complexity limit per method: 15.
- Cognitive complexity limit: 17.
- Public static methods are prohibited (except `main`).
- Only one constructor should perform field initialization; others must delegate.

### License header

Every Java source file must include the GPL-3.0 license header defined in `LICENSE.header`.
The `license-maven-plugin` checks this during the `verify` phase. To add missing headers:

```bash
mvn license:format
```

### Hamcrest matchers

Prefer matcher **objects** over static factory methods:

```java
// Preferred
MatcherAssert.assertThat(target, new IsEquals<>(expected));

// Avoid
MatcherAssert.assertThat(target, Matchers.equalTo(expected));
```

### Assertion reasons

- **Single assertion** in a test method: no reason string needed.

```java
MatcherAssert.assertThat(target, matcher);
```

- **Multiple assertions** in a test method: add a reason string to each.

```java
MatcherAssert.assertThat("Reason one", target1, matcher1);
MatcherAssert.assertThat("Reason two", target2, matcher2);
```

---

## Commit Messages

We follow the [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/)
specification:

```
<type>[optional scope]: <description>

[optional body]

[optional footer(s)]
```

Common types: `feat`, `fix`, `test`, `refactor`, `docs`, `chore`, `perf`, `ci`.

Use present-tense, imperative mood (e.g., "add", not "added" or "adds").

Examples:

```
feat(docker): add blob GET endpoint
fix(maven): resolve NPE on artifact download
test(npm): add integration tests for publish
```

---

## Pull Request Guidelines

### Title

Format: `<type>[optional scope]: <description>`

- Keep it short and descriptive.
- Do not include links or ticket references in the title.

Good examples:
- `fix: maven artifact upload`
- `feat: GET blobs API for Docker`
- `test: add integration test for Maven deploy`

Bad examples:
- `Fixed NPE` (too vague)
- `Added more tests` (not specific)
- `Implementing Docker registry` (describes process, not result)

### Description

The description explains **how** the change was made, not just **what** changed.
Provide a short summary of all changes to give context before the reviewer reads code.

### Footer

Separate footers from the body with a blank line:

```
Check if the file exists before accessing it and return 404 code if it does not exist.

Fix: #123
```

Common footers:

| Footer   | Purpose                                |
|----------|----------------------------------------|
| `Close:` | Closes the referenced issue on merge   |
| `Fix:`   | Fixes the referenced issue             |
| `Ref:`   | References a related issue or tracker  |

### Ticket reference

Every pull request **must** reference a ticket (via `Close:`, `Fix:`, or `Ref:` footer),
except for truly minor fixes (typos, formatting).

---

## Review Process

It is the **author's responsibility** to bring changes to the `master` branch.

### Workflow

```
        PR created |   Review   | Request changes | Fixed changes | Approves changes | Merge
assignee: <none>  -> <reviewer> ->    (author)    ->  (reviewer)  ->   <maintainer>  -> <none>
```

1. Author creates the PR and requests review.
2. Reviewer assigns the PR to themselves and begins review.
3. If changes are requested, the PR is assigned back to the author.
4. Author addresses feedback (amend + force-push for minor/obvious changes, or new commit
   for substantive changes the author wants to document).
5. Reviewer approves. If the reviewer is not the repository maintainer, the PR is assigned
   to the maintainer.
6. Maintainer merges the PR.

---

## Merging

PRs are merged only after all required CI checks pass and a maintainer approves.

- **Squash merge**: used when the PR has many commits (more than 3) or commit messages are
  not well-formatted. GitHub auto-populates the squash message from the PR title and
  description.
- **Merge commit**: used when the PR has a small number of well-formatted commits that each
  follow the Conventional Commits convention.

---

## Branch Strategy

- All feature branches are created from `master`.
- Branch names should be descriptive (e.g., `feat/docker-blob-api`, `fix/maven-npe`).
- Use Conventional Commits for all commits on the branch.
- Keep branches focused: one logical change per branch.

---

## Security Disclosure

If you discover a security vulnerability, **do not** open a public issue. Instead, report
it through [GitHub Security Advisories](https://github.com/auto1-oss/pantera/security/advisories/new).

We will acknowledge the report within 3 business days and work with you on a fix.

---

## License

Pantera is licensed under [GPL-3.0](LICENSE.txt).

All Java source files must include the license header from `LICENSE.header`. The
`license-maven-plugin` enforces this during the build. Files missing the header will cause
the build to fail.

```
Copyright (c) 2025-2026 Auto1 Group
Maintainers: Auto1 DevOps Team
Lead Maintainer: Ayd Asraf

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU General Public License v3.0.

Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
```
