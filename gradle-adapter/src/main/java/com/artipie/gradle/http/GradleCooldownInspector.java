/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.http;

import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.asto.Content;
import com.artipie.http.Headers;
import com.artipie.http.headers.Header;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Gradle cooldown inspector.
 * Inspects Gradle module metadata (.module files) and POM files for dependencies.
 *
 * @since 1.0
 */
final class GradleCooldownInspector implements CooldownInspector {

    private static final DateTimeFormatter LAST_MODIFIED = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final Slice remote;
    private final RepoHead head;
    private final GradleMetadataReader reader;

    GradleCooldownInspector(final Slice remote) {
        this.remote = remote;
        this.head = new RepoHead(remote);
        this.reader = new GradleMetadataReader(remote);
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        EcsLogger.debug("com.artipie.gradle")
            .message("Checking release date")
            .eventCategory("repository")
            .eventAction("cooldown_inspector")
            .field("package.name", artifact)
            .field("package.version", version)
            .log();
        final String modulePath = GradlePathBuilder.modulePath(artifact, version);
        final String pomPath = GradlePathBuilder.pomPath(artifact, version);
        final String jarPath = GradlePathBuilder.jarPath(artifact, version);
        
        return this.tryModuleHead(modulePath)
            .thenCompose(result -> result.isPresent()
                ? CompletableFuture.completedFuture(result)
                : this.tryPomHead(pomPath))
            .thenCompose(result -> result.isPresent()
                ? CompletableFuture.completedFuture(result)
                : this.tryModuleGet(modulePath))
            .thenCompose(result -> result.isPresent()
                ? CompletableFuture.completedFuture(result)
                : this.tryJarHead(jarPath, artifact, version))
            .toCompletableFuture();
    }

    private CompletableFuture<Optional<Instant>> tryModuleHead(final String path) {
        return this.head.head(path).thenApply(headers -> {
            final Optional<Instant> result = headers.flatMap(GradleCooldownInspector::parseLastModified);
            result.ifPresent(instant -> EcsLogger.debug("com.artipie.gradle")
                .message("Found release date from module HEAD")
                .eventCategory("repository")
                .eventAction("cooldown_inspector")
                .field("package.release_date", instant)
                .log());
            return result;
        }).toCompletableFuture();
    }

    private CompletableFuture<Optional<Instant>> tryPomHead(final String path) {
        return this.head.head(path).thenApply(headers -> {
            final Optional<Instant> result = headers.flatMap(GradleCooldownInspector::parseLastModified);
            result.ifPresent(instant -> EcsLogger.debug("com.artipie.gradle")
                .message("Found release date from POM HEAD")
                .eventCategory("repository")
                .eventAction("cooldown_inspector")
                .field("package.release_date", instant)
                .log());
            return result;
        }).toCompletableFuture();
    }

    private CompletableFuture<Optional<Instant>> tryModuleGet(final String path) {
        return this.remote.response(new RequestLine(RqMethod.GET, path), Headers.EMPTY, Content.EMPTY)
            .thenApply(resp -> {
                final Optional<Instant> result = resp.status().success()
                    ? parseLastModified(resp.headers())
                    : Optional.empty();
                result.ifPresent(instant -> EcsLogger.debug("com.artipie.gradle")
                    .message("Found release date from module GET")
                    .eventCategory("repository")
                    .eventAction("cooldown_inspector")
                    .field("package.release_date", instant)
                    .log());
                return result;
            }).toCompletableFuture();
    }

    private CompletableFuture<Optional<Instant>> tryJarHead(
        final String path,
        final String artifact,
        final String version
    ) {
        return this.head.head(path).thenApply(headers -> {
            final Optional<Instant> result = headers.flatMap(GradleCooldownInspector::parseLastModified);
            if (result.isEmpty()) {
                EcsLogger.warn("com.artipie.gradle")
                    .message("Could not find release date")
                    .eventCategory("repository")
                    .eventAction("cooldown_inspector")
                    .eventOutcome("failure")
                    .field("package.name", artifact)
                    .field("package.version", version)
                    .log();
            } else {
                EcsLogger.debug("com.artipie.gradle")
                    .message("Found release date from JAR HEAD")
                    .eventCategory("repository")
                    .eventAction("cooldown_inspector")
                    .field("package.release_date", result.get())
                    .log();
            }
            return result;
        }).toCompletableFuture();
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
        // Try Gradle module metadata first, then fall back to POM
        return this.reader.readModuleMetadata(artifact, version).thenCompose(module -> {
            if (module.isPresent() && !module.get().isEmpty()) {
                return CompletableFuture.completedFuture(parseModuleDependencies(module.get()));
            }
            // Fallback to POM
            return this.reader.readPom(artifact, version).thenCompose(pom -> {
                if (pom.isEmpty() || pom.get().isEmpty()) {
                    return CompletableFuture.completedFuture(Collections.<CooldownDependency>emptyList());
                }
                final PomParser.PomView view = PomParser.parse(pom.get());
                final List<CooldownDependency> result = new ArrayList<>(view.dependencies());
                if (view.parent().isEmpty()) {
                    return CompletableFuture.completedFuture(result);
                }
                return collectParents(view.parent().get(), new HashSet<>())
                    .thenApply(parents -> {
                        result.addAll(parents);
                        return result;
                    });
            });
        }).exceptionally(throwable -> {
            EcsLogger.error("com.artipie.gradle")
                .message("Failed to read dependencies")
                .eventCategory("repository")
                .eventAction("cooldown_inspector")
                .eventOutcome("failure")
                .field("package.name", artifact)
                .field("package.version", version)
                .error(throwable)
                .log();
            return Collections.<CooldownDependency>emptyList();
        });
    }


    private static Optional<Instant> parseLastModified(final Headers headers) {
        return headers.stream()
            .filter(header -> "Last-Modified".equalsIgnoreCase(header.getKey()))
            .map(Header::getValue)
            .findFirst()
            .flatMap(GradleCooldownInspector::parseRfc1123Relaxed);
    }

    private static Optional<Instant> parseRfc1123Relaxed(final String raw) {
        String val = raw == null ? "" : raw.trim();
        if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
            val = val.substring(1, val.length() - 1);
        }
        val = val.replaceAll("\\s+", " ");
        try {
            return Optional.of(Instant.from(LAST_MODIFIED.parse(val)));
        } catch (final DateTimeParseException ex1) {
            try {
                final DateTimeFormatter relaxed =
                    DateTimeFormatter.ofPattern("EEE, dd MMM yyyy H:mm:ss z", Locale.US);
                return Optional.of(Instant.from(relaxed.parse(val)));
            } catch (final DateTimeParseException ex2) {
                EcsLogger.warn("com.artipie.gradle")
                    .message("Invalid Last-Modified header")
                    .eventCategory("repository")
                    .eventAction("cooldown_inspector")
                    .eventOutcome("failure")
                    .field("http.response.headers.Last-Modified", raw)
                    .log();
                return Optional.empty();
            }
        }
    }


    private static List<CooldownDependency> parseModuleDependencies(final String json) {
        // Simple JSON parsing for Gradle module metadata
        // Format: {"variants":[{"dependencies":[{"group":"...","module":"...","version":{"requires":"..."}}]}]}
        final List<CooldownDependency> result = new ArrayList<>();
        final String[] lines = json.split("\n");
        final ModuleDependencyParser parser = new ModuleDependencyParser();
        
        for (final String line : lines) {
            final String trimmed = line.trim();
            parser.parseLine(trimmed).ifPresent(result::add);
        }
        return result;
    }

    private static String extractJsonValue(final String line) {
        final int start = line.indexOf(':') + 1;
        if (start <= 0 || start >= line.length()) {
            return null;
        }
        String value = line.substring(start).trim();
        if (value.endsWith(",")) {
            value = value.substring(0, value.length() - 1).trim();
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.isEmpty() ? null : value;
    }


    private CompletableFuture<List<CooldownDependency>> collectParents(
        final CooldownDependency current,
        final Set<String> visited
    ) {
        final String coordinate = key(current.artifact(), current.version());
        if (!visited.add(coordinate)) {
            return CompletableFuture.completedFuture(Collections.<CooldownDependency>emptyList());
        }
        return this.reader.readPom(current.artifact(), current.version()).thenCompose(pom -> {
            final List<CooldownDependency> result = new ArrayList<>();
            result.add(current);
            if (pom.isEmpty() || pom.get().isEmpty()) {
                return CompletableFuture.completedFuture(result);
            }
            final PomParser.PomView view = PomParser.parse(pom.get());
            if (view.parent().isEmpty()) {
                return CompletableFuture.completedFuture(result);
            }
            return collectParents(view.parent().get(), visited).thenApply(parents -> {
                result.addAll(parents);
                return result;
            });
        }).exceptionally(throwable -> {
            EcsLogger.warn("com.artipie.gradle")
                .message("Failed to resolve parent chain")
                .eventCategory("repository")
                .eventAction("cooldown_inspector")
                .eventOutcome("failure")
                .field("package.name", current.artifact())
                .field("package.version", current.version())
                .error(throwable)
                .log();
            return List.of(current);
        });
    }


    private static String key(final String artifact, final String version) {
        return artifact.toLowerCase(Locale.US) + ':' + version;
    }


    /**
     * Parser for module dependencies from Gradle metadata JSON.
     */
    private static final class ModuleDependencyParser {
        private Optional<String> currentGroup = Optional.empty();
        private Optional<String> currentModule = Optional.empty();

        Optional<CooldownDependency> parseLine(final String trimmed) {
            if (trimmed.contains("\"group\"")) {
                this.currentGroup = Optional.ofNullable(extractJsonValue(trimmed));
                return Optional.empty();
            } else if (trimmed.contains("\"module\"")) {
                this.currentModule = Optional.ofNullable(extractJsonValue(trimmed));
                return Optional.empty();
            } else if (trimmed.contains("\"requires\"")) {
                final String version = extractJsonValue(trimmed);
                if (this.currentGroup.isPresent() && this.currentModule.isPresent() && version != null) {
                    final CooldownDependency dep = new CooldownDependency(
                        this.currentGroup.get() + "." + this.currentModule.get(),
                        version
                    );
                    this.currentGroup = Optional.empty();
                    this.currentModule = Optional.empty();
                    return Optional.of(dep);
                }
            }
            return Optional.empty();
        }
    }
}
