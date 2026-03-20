/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http;

import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.http.slice.ContentWithSize;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.scheduling.ArtifactEvent;

import hu.akarnokd.rxjava2.interop.SingleInterop;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Go repository upload slice. Handles uploads of module artifacts and emits metadata events.
 *
 * @since 1.0
 */
final class GoUploadSlice implements Slice {

    /**
     * Repository type identifier for metadata events.
     */
    private static final String REPO_TYPE = "go";

    /**
     * Path pattern for Go module artifacts.
     * Matches: /module/path/@v/v1.2.3.{info|mod|zip}
     */
    private static final Pattern ARTIFACT = Pattern.compile(
        "^/?(?<module>.+)/@v/v(?<version>[^/]+)\\.(?<ext>info|mod|zip)$"
    );

    /**
     * Repository storage.
     */
    private final Storage storage;

    /**
     * Optional metadata events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String repo;

    /**
     * New Go upload slice.
     *
     * @param storage Repository storage
     * @param repo Repository name
     * @param events Metadata events queue
     */
    GoUploadSlice(
        final Storage storage,
        final String repo,
        final Optional<Queue<ArtifactEvent>> events
    ) {
        this.storage = storage;
        this.repo = repo;
        this.events = events;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Strip semicolon-separated metadata properties from the path to avoid exceeding
        // filesystem filename length limits (typically 255 bytes). These properties are
        // added by build tools (e.g., vcs.revision, build.timestamp)
        // but are not part of the actual module filename.
        final String path = line.uri().getPath();
        final String sanitizedPath;
        final int semicolonIndex = path.indexOf(';');
        if (semicolonIndex > 0) {
            sanitizedPath = path.substring(0, semicolonIndex);
            EcsLogger.debug("com.auto1.pantera.go")
                .message("Stripped metadata properties from path")
                .eventCategory("repository")
                .eventAction("upload")
                .field("url.original", path)
                .field("url.path", sanitizedPath)
                .log();
        } else {
            sanitizedPath = path;
        }

        final Key key = new KeyFromPath(sanitizedPath);
        final Matcher matcher = ARTIFACT.matcher(normalise(sanitizedPath));
        final CompletableFuture<Void> stored = this.storage.save(
            key,
            new ContentWithSize(body, headers)
        );
        final CompletableFuture<Void> extra;
        if (matcher.matches()) {
            final String module = matcher.group("module");
            final String version = matcher.group("version");
            final String ext = matcher.group("ext").toLowerCase(Locale.ROOT);
            if ("zip".equals(ext)) {
                extra = stored.thenCompose(
                    nothing -> this.recordEvent(headers, module, version, key)
                ).thenCompose(
                    nothing -> this.updateList(module, version)
                );
            } else {
                extra = stored;
            }
        } else {
            extra = stored;
        }
        return extra.thenApply(ignored -> ResponseBuilder.created().build());
    }

    /**
     * Record artifact upload event after the binary is stored.
     *
     * @param headers Request headers
     * @param module Module path
     * @param version Module version (without leading `v`)
     * @param key Storage key for uploaded artifact
     * @return Completion stage
     */
    private CompletableFuture<Void> recordEvent(
        final Headers headers,
        final String module,
        final String version,
        final Key key
    ) {
        if (this.events.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return this.storage.metadata(key)
            .thenApply(meta -> meta.read(Meta.OP_SIZE).orElseThrow())
            .thenAccept(
                size -> this.events.ifPresent(
                    queue -> queue.add(
                        new ArtifactEvent(
                            REPO_TYPE,
                            this.repo,
                            owner(headers),
                            module,
                            version,
                            size
                        )
                    )
                )
            );
    }

    /**
     * Update {@code list} file with provided module version.
     *
     * @param module Module path
     * @param version Module version (without leading `v`)
     * @return Completion stage
     */
    private CompletableFuture<Void> updateList(
        final String module,
        final String version
    ) {
        final Key list = new Key.From(String.format("%s/@v/list", module));
        final String entry = String.format("v%s", version);
        return this.storage.exists(list).thenCompose(
            exists -> {
                if (!exists) {
                    return this.storage.save(
                        list,
                        new Content.From((entry + '\n').getBytes(StandardCharsets.UTF_8))
                    );
                }
                return this.storage.value(list).thenCompose(
                    content -> {
                        // OPTIMIZATION: Use size hint for efficient pre-allocation
                        final long knownSize = content.size().orElse(-1L);
                        return Concatenation.withSize(content, knownSize).single()
                            .map(Remaining::new)
                            .map(Remaining::bytes)
                            .map(bytes -> new String(bytes, StandardCharsets.UTF_8))
                            .to(SingleInterop.get())
                            .thenCompose(existing -> {
                                final LinkedHashSet<String> versions = new LinkedHashSet<>();
                                existing.lines()
                                    .map(String::trim)
                                    .filter(line -> !line.isEmpty())
                                    .forEach(versions::add);
                                if (!versions.add(entry)) {
                                    return CompletableFuture.completedFuture(null);
                                }
                                final String updated = String.join("\n", versions) + '\n';
                                return this.storage.save(
                                    list,
                                    new Content.From(updated.getBytes(StandardCharsets.UTF_8))
                                );
                            });
                    }
                );
            }
        );
    }

    /**
     * Extract owner from request headers.
     *
     * @param headers Request headers
     * @return Owner name or default value
     */
    private static String owner(final Headers headers) {
        final String value = new Login(headers).getValue();
        if (value == null || value.isBlank()) {
            return ArtifactEvent.DEF_OWNER;
        }
        return value;
    }

    /**
     * Remove leading slash if present.
     *
     * @param path Request path
     * @return Normalised path
     */
    private static String normalise(final String path) {
        if (path.isEmpty()) {
            return path;
        }
        return path.charAt(0) == '/' ? path.substring(1) : path;
    }
}
