# Repository Guidelines

## Project Structure & Module Organization

Pantera is a multi-module Maven project for a binary artifact registry. Core Java modules live at the repository root, including `pantera-core`, `pantera-main`, `pantera-storage`, `vertx-server`, `http-client`, and package adapters such as `maven-adapter`, `npm-adapter`, `docker-adapter`, `pypi-adapter`, `rpm-adapter`, and others. Java source follows the standard Maven layout: `src/main/java` for production code and `src/test/java` for tests. The Vue management console is in `pantera-ui`, with UI source under `pantera-ui/src`, public assets in `pantera-ui/public`, and Vitest tests in `__tests__` directories.

## Build, Test, and Development Commands

- `mvn clean verify`: full Java build, tests, PMD, and license checks.
- `mvn test`: run unit tests with Surefire.
- `mvn verify -Pitcase`: run integration tests with Failsafe and TestContainers.
- `mvn install -pl maven-adapter -am -DskipTests`: build one module and required dependencies.
- `mvn license:format`: add missing Java license headers.
- `make up`, `make down`, `make logs`: manage the local Docker Compose stack using `.env.dev`.
- `cd pantera-ui && npm install && npm run dev`: start the Vite UI dev server.
- `cd pantera-ui && npm test && npm run lint && npm run build`: test, lint, type-check, and build the UI.

## Coding Style & Naming Conventions

Use Java 21 and UTF-8. Java code uses 4-space indentation, descriptive PascalCase class names, and packages under `com.auto1.pantera`. Follow established suffixes such as `*Slice` for HTTP handlers and `*Storage` for storage implementations. PMD rules are defined in `build-tools/src/main/resources/pmd-ruleset.xml`; avoid public static helpers except `main`, keep complexity low, and make secondary constructors delegate. Every Java source file must include the project license header.

## Testing Guidelines

Use JUnit 5 and Hamcrest for Java tests. Unit tests must be isolated from external services and named `*Test.java`. Integration tests must be named `*IT.java` or `*ITCase.java` and use TestContainers when Docker services are needed. UI tests use Vitest and usually sit beside features in `__tests__`.

## Commit & Pull Request Guidelines

Recent history uses Conventional Commits, for example `fix(pmd): clean pypi-adapter` or `fix(ui): runtime settings save`. Keep commits scoped and imperative. Pull requests should describe the change, list test commands run, link related issues, and include screenshots for UI-visible changes.

## Security & Configuration Tips

Do not commit secrets, tokens, or local credentials. Keep local runtime settings in environment files such as `.env.dev`; review Docker Compose and `pantera-main/examples/pantera.yml` before changing ports, auth, or storage paths.
