/*
 * Copyright (c) 2025-2026 Auto1 Group
 * Maintainers: Auto1 DevOps Team
 * Lead Maintainer: Ayd Asraf
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License v3.0.
 *
 * Originally based on Artipie (https://github.com/artipie/artipie), MIT License.
 */
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Concatenation;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Remaining;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.npm.PackageNameFromUrl;
import com.auto1.pantera.npm.Publish;
import com.auto1.pantera.npm.VersionExistsException;
import com.auto1.pantera.scheduling.ArtifactEvent;
import hu.akarnokd.rxjava2.interop.SingleInterop;

import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import javax.json.Json;

/**
 * UploadSlice.
 */
public final class UploadSlice implements Slice {

    /**
     * Repository type.
     */
    public static final String REPO_TYPE = "npm";

    /**
     * Publishes in immutable repositories, serialised per repository and
     * package (JVM-wide, so every publish route and a rebuilt slice share it).
     */
    private static final KeyedSerializer PUBLISHES = new KeyedSerializer();

    /**
     * The npm publish front.
     */
    private final Publish npm;

    /**
     * Abstract Storage.
     */
    private final Storage storage;

    /**
     * Artifact events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Synchronous artifact-index writer for read-after-write consistency.
     */
    private final SyncArtifactIndexer syncIndex;

    /**
     * Whether published versions are immutable. The publish front refuses
     * an overwrite; this slice serialises the publishes of one package so
     * that refusal check and write are not interleaved with a concurrent
     * publish of the same version, and answers the refusal with 409.
     */
    private final boolean immutable;

    /**
     * Legacy ctor (no synchronous index writer).
     *
     * @param npm Npm publish front
     * @param storage Abstract storage
     * @param events Artifact events queue
     * @param rname Repository name
     */
    public UploadSlice(final Publish npm, final Storage storage,
        final Optional<Queue<ArtifactEvent>> events, final String rname) {
        this(npm, storage, events, rname, SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous index writer.
     *
     * @param npm Npm publish front
     * @param storage Abstract storage
     * @param events Artifact events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     */
    public UploadSlice(final Publish npm, final Storage storage,
        final Optional<Queue<ArtifactEvent>> events, final String rname,
        final SyncArtifactIndexer syncIndex) {
        this(npm, storage, events, rname, syncIndex, false);
    }

    /**
     * Ctor with the repository's {@code immutable} setting. The publish
     * front must be built with the same setting (see
     * {@link CliPublish#CliPublish(Storage, boolean)}).
     *
     * @param npm Npm publish front
     * @param storage Abstract storage
     * @param events Artifact events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     * @param immutable Whether published versions are immutable
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    public UploadSlice(final Publish npm, final Storage storage,
        final Optional<Queue<ArtifactEvent>> events, final String rname,
        final SyncArtifactIndexer syncIndex, final boolean immutable) {
        this.npm = npm;
        this.storage = storage;
        this.events = events;
        this.rname = rname;
        this.syncIndex = syncIndex;
        this.immutable = immutable;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        final String pkg = new PackageNameFromUrl(line).value();
        if (!this.immutable) {
            return this.publish(pkg, headers, body);
        }
        final int dash = pkg.indexOf("/-/");
        final String name;
        if (dash > 0) {
            name = pkg.substring(0, dash);
        } else {
            name = pkg;
        }
        return UploadSlice.PUBLISHES.run(
            String.join("/", this.rname, name),
            () -> this.publish(pkg, headers, body)
        ).handle(
            (response, error) -> {
                final Throwable cause = UploadSlice.unwrap(error);
                if (cause instanceof VersionExistsException) {
                    return CompletableFuture.completedFuture(
                        this.conflict(pkg, (VersionExistsException) cause)
                    );
                }
                if (error != null) {
                    return CompletableFuture.<Response>failedFuture(error);
                }
                return CompletableFuture.completedFuture(response);
            }
        ).thenCompose(Function.identity());
    }

    /**
     * Store the upload and publish it.
     * @param pkg Package name (request path)
     * @param headers Request headers
     * @param body Request body
     * @return Response future
     */
    private CompletableFuture<Response> publish(
        final String pkg, final Headers headers, final Content body
    ) {
        final Key uploaded = new Key.From(String.format("%s-%s-uploaded", pkg, UUID.randomUUID()));
        // OPTIMIZATION: Use size hint for efficient pre-allocation
        final long bodySize = body.size().orElse(-1L);
        return Concatenation.withSize(body, bodySize).single()
            .map(Remaining::new)
            .map(Remaining::bytes)
            .to(SingleInterop.get())
            .thenCompose(bytes -> this.storage.save(uploaded, new Content.From(bytes)))
            .thenCompose(
                ignored -> this.events.map(
                    queue -> this.npm.publishWithInfo(new Key.From(pkg), uploaded)
                        .thenCompose(info -> {
                            final ArtifactEvent event = new ArtifactEvent(
                                UploadSlice.REPO_TYPE, this.rname,
                                new Login(headers).getValue(),
                                info.packageName(), info.packageVersion(), info.tarSize(),
                                System.currentTimeMillis(), null, info.packagePath()
                            ).withRequestContext(headers);
                            queue.add(event);
                            return this.syncIndex.recordSync(event);
                        })
                ).orElseGet(() -> this.npm.publish(new Key.From(pkg), uploaded))
            )
            .thenCompose(ignored -> this.storage.delete(uploaded))
            .whenComplete((ignored, error) -> {
                // Drop any cached 404 for this package so a request
                // that 404'd before publish (e.g. group fanout miss)
                // does not keep returning 404. npm uses the package
                // name verbatim — same form the negative cache stores.
                if (error == null) {
                    com.auto1.pantera.http.cache.NegativeCacheRegistry.instance()
                        .invalidateAfterUpload("npm", pkg);
                    com.auto1.pantera.cooldown.metadata
                        .FilteredMetadataCacheRegistry.instance()
                        .invalidateAfterUpload("npm", pkg);
                }
            })
            .thenApply(ignored -> ResponseBuilder.ok().build())
            .whenComplete(
                (ignored, error) -> {
                    if (UploadSlice.unwrap(error) instanceof VersionExistsException) {
                        this.storage.delete(uploaded);
                    }
                }
            )
            .toCompletableFuture();
    }

    /**
     * Refusal of a publish over a published version, readable by the npm
     * CLI (it prints the {@code error} field after the status line).
     * @param pkg Package name (request path)
     * @param refusal Refusal
     * @return 409 Conflict
     */
    private Response conflict(final String pkg, final VersionExistsException refusal) {
        EcsLogger.warn("com.auto1.pantera.npm")
            .message("Rejected publish over a published version in an immutable repository")
            .eventCategory("web")
            .eventAction("artifact_upload")
            .eventOutcome("failure")
            .field("event.reason", "artifact_immutable")
            .field("repository.name", this.rname)
            .field("package.name", pkg)
            .field("package.version", refusal.version())
            .field("log.source", "application")
            .log();
        return ResponseBuilder.from(RsStatus.CONFLICT)
            .jsonBody(
                Json.createObjectBuilder().add("error", refusal.getMessage()).build().toString()
            )
            .build();
    }

    /**
     * Unwrap completion wrappers.
     * @param error Error, may be null
     * @return Root completion cause, or null
     */
    private static Throwable unwrap(final Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
