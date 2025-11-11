/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.maven.http;

import com.artipie.cooldown.CooldownDependency;
import com.artipie.cooldown.CooldownInspector;
import com.artipie.asto.Content;
import com.artipie.asto.Remaining;
import com.artipie.http.Headers;
import com.artipie.http.headers.Header;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.jcabi.log.Logger;
import com.jcabi.xml.XML;
import com.jcabi.xml.XMLDocument;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import java.io.ByteArrayOutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

final class MavenCooldownInspector implements CooldownInspector {

    private static final DateTimeFormatter LAST_MODIFIED = DateTimeFormatter.RFC_1123_DATE_TIME;

    private final Slice remote;
    private final RepoHead head;

    MavenCooldownInspector(final Slice remote) {
        this.remote = remote;
        this.head = new RepoHead(remote);
    }

    @Override
    public CompletableFuture<Optional<Instant>> releaseDate(final String artifact, final String version) {
        final String pom = pomPath(artifact, version);
        final String jar = artifactPath(artifact, version, "jar");
        return this.head.head(pom)
            .thenCompose(headers -> {
                final Optional<Instant> lm = headers.flatMap(MavenCooldownInspector::parseLastModified);
                if (lm.isPresent()) {
                    return CompletableFuture.completedFuture(lm);
                }
                // Fallback 1: some upstreams don't send Last-Modified on HEAD; try GET headers
                return this.remote.response(new RequestLine(RqMethod.GET, pom), Headers.EMPTY, Content.EMPTY)
                    .thenCompose(resp -> {
                        final Optional<Instant> fromGet = resp.status().success()
                            ? parseLastModified(resp.headers())
                            : Optional.empty();
                        if (fromGet.isPresent()) {
                            return CompletableFuture.completedFuture(fromGet);
                        }
                        // Fallback 2: try artifact JAR HEAD
                        return this.head.head(jar)
                            .thenApply(h -> h.flatMap(MavenCooldownInspector::parseLastModified));
                    });
            }).toCompletableFuture();
    }

    @Override
    public CompletableFuture<List<CooldownDependency>> dependencies(final String artifact, final String version) {
        return this.readPom(artifact, version).thenCompose(pom -> {
            if (pom.isEmpty() || pom.get().isEmpty()) {
                return CompletableFuture.completedFuture(Collections.<CooldownDependency>emptyList());
            }
            final PomView view = parsePom(pom.get());
            final List<CooldownDependency> result = new ArrayList<>(view.dependencies());
            if (view.parent().isEmpty()) {
                return CompletableFuture.completedFuture(result);
            }
            return collectParents(view.parent().get(), new HashSet<>())
                .thenApply(parents -> {
                    result.addAll(parents);
                    return result;
                });
        }).exceptionally(throwable -> {
            Logger.error(
                this,
                "Failed to read dependencies for %s:%s - %s",
                artifact,
                version,
                throwable.getMessage()
            );
            return Collections.<CooldownDependency>emptyList();
        });
    }

    private CompletableFuture<Optional<String>> readPom(final String artifact, final String version) {
        final String path = pomPath(artifact, version);
        return this.remote.response(
            new RequestLine(RqMethod.GET, path),
            Headers.EMPTY,
            Content.EMPTY
        ).thenCompose(response -> {
            // CRITICAL: Always consume body to prevent Vert.x request leak
            return bodyBytes(response.body()).thenApply(bytes -> {
                if (!response.status().success()) {
                    Logger.warn(this, "Failed to fetch POM %s: %s", path, response.status());
                    return Optional.empty();
                }
                return Optional.of(new String(bytes, StandardCharsets.UTF_8));
            });
        });
    }

    private static Optional<Instant> parseLastModified(final Headers headers) {
        return headers.stream()
            .filter(header -> "Last-Modified".equalsIgnoreCase(header.getKey()))
            .map(Header::getValue)
            .findFirst()
            .flatMap(MavenCooldownInspector::parseRfc1123Relaxed);
    }

    private static Optional<Instant> parseRfc1123Relaxed(final String raw) {
        String val = raw == null ? "" : raw.trim();
        // strip surrounding quotes if present
        if (val.length() >= 2 && val.startsWith("\"") && val.endsWith("\"")) {
            val = val.substring(1, val.length() - 1);
        }
        // collapse multiple spaces to a single space
        val = val.replaceAll("\\s+", " ");
        try {
            return Optional.of(Instant.from(LAST_MODIFIED.parse(val)));
        } catch (final DateTimeParseException ex1) {
            try {
                // some upstreams send single-digit hour; accept with 'H'
                final DateTimeFormatter relaxed =
                    DateTimeFormatter.ofPattern(
                        "EEE, dd MMM yyyy H:mm:ss z", Locale.US
                    );
                return Optional.of(Instant.from(relaxed.parse(val)));
            } catch (final DateTimeParseException ex2) {
                Logger.warn(
                    MavenCooldownInspector.class,
                    "Invalid Last-Modified header: %s",
                    raw
                );
                return Optional.empty();
            }
        }
    }

    private static CompletableFuture<byte[]> bodyBytes(final org.reactivestreams.Publisher<ByteBuffer> body) {
        return Flowable.fromPublisher(body)
            .reduce(new ByteArrayOutputStream(), (stream, buffer) -> {
                try {
                    stream.write(new Remaining(buffer).bytes());
                    return stream;
                } catch (final java.io.IOException error) {
                    throw new UncheckedIOException(error);
                }
            })
            .map(ByteArrayOutputStream::toByteArray)
            .onErrorReturnItem(new byte[0])
            .to(SingleInterop.get())
            .toCompletableFuture();
    }

    private static PomView parsePom(final String pom) {
        final XMLDocument xml = new XMLDocument(pom);
        return new PomView(parseDependencies(xml), parseParent(xml));
    }

    private static List<CooldownDependency> parseDependencies(final XML xml) {
        final Collection<XML> deps = xml.nodes(
            "//*[local-name()='project']/*[local-name()='dependencies']/*[local-name()='dependency']"
        );
        if (deps.isEmpty()) {
            return Collections.<CooldownDependency>emptyList();
        }
        final List<CooldownDependency> result = new ArrayList<>(deps.size());
        for (final XML dep : deps) {
            final String scope = text(dep, "scope").map(val -> val.toLowerCase(Locale.US)).orElse("compile");
            final boolean optional = text(dep, "optional").map("true"::equalsIgnoreCase).orElse(false);
            if (optional || "test".equals(scope) || "provided".equals(scope)) {
                continue;
            }
            final Optional<String> group = text(dep, "groupId");
            final Optional<String> name = text(dep, "artifactId");
            final Optional<String> version = text(dep, "version");
            if (group.isEmpty() || name.isEmpty() || version.isEmpty()) {
                continue;
            }
            result.add(new CooldownDependency(group.get() + "." + name.get(), version.get()));
        }
        return result;
    }

    private static Optional<CooldownDependency> parseParent(final XML xml) {
        return xml.nodes("//*[local-name()='project']/*[local-name()='parent']").stream()
            .findFirst()
            .flatMap(node -> {
                final Optional<String> group = text(node, "groupId");
                final Optional<String> name = text(node, "artifactId");
                final Optional<String> version = text(node, "version");
                if (group.isEmpty() || name.isEmpty() || version.isEmpty()) {
                    return Optional.empty();
                }
                return Optional.of(new CooldownDependency(group.get() + "." + name.get(), version.get()));
            });
    }

    private CompletableFuture<List<CooldownDependency>> collectParents(
        final CooldownDependency current,
        final Set<String> visited
    ) {
        final String coordinate = key(current.artifact(), current.version());
        if (!visited.add(coordinate)) {
            return CompletableFuture.completedFuture(Collections.<CooldownDependency>emptyList());
        }
        return this.readPom(current.artifact(), current.version()).thenCompose(pom -> {
            final List<CooldownDependency> result = new ArrayList<>();
            result.add(current);
            if (pom.isEmpty() || pom.get().isEmpty()) {
                return CompletableFuture.completedFuture(result);
            }
            final PomView view = parsePom(pom.get());
            if (view.parent().isEmpty()) {
                return CompletableFuture.completedFuture(result);
            }
            return collectParents(view.parent().get(), visited).thenApply(parents -> {
                result.addAll(parents);
                return result;
            });
        }).exceptionally(throwable -> {
            Logger.warn(
                MavenCooldownInspector.class,
                "Failed to resolve parent chain for %s:%s - %s",
                current.artifact(),
                current.version(),
                throwable.getMessage()
            );
            return List.of(current);
        });
    }

    private static Optional<String> text(final XML xml, final String localName) {
        final List<String> values = xml.xpath(String.format("./*[local-name()='%s']/text()", localName));
        if (values.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(values.get(0).trim());
    }

    private static String pomPath(final String artifact, final String version) {
        return artifactPath(artifact, version, "pom");
    }

    private static String artifactPath(final String artifact, final String version, final String ext) {
        final int idx = artifact.lastIndexOf('.');
        final String group;
        final String name;
        if (idx == -1) {
            group = "";
            name = artifact;
        } else {
            group = artifact.substring(0, idx).replace('.', '/');
            name = artifact.substring(idx + 1);
        }
        final StringBuilder path = new StringBuilder();
        path.append('/');
        if (!group.isEmpty()) {
            path.append(group).append('/');
        }
        path.append(name).append('/').append(version).append('/').append(name)
            .append('-').append(version).append('.').append(ext);
        return path.toString();
    }

    private static String key(final String artifact, final String version) {
        return artifact.toLowerCase(Locale.US) + ':' + version;
    }

    private static final class PomView {

        private final List<CooldownDependency> dependencies;
        private final Optional<CooldownDependency> parent;

        PomView(final List<CooldownDependency> dependencies, final Optional<CooldownDependency> parent) {
            this.dependencies = dependencies;
            this.parent = parent;
        }

        List<CooldownDependency> dependencies() {
            return this.dependencies;
        }

        Optional<CooldownDependency> parent() {
            return this.parent;
        }
    }
}
