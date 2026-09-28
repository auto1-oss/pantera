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
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.asto.lock.storage.IndexUpdateLock;
import com.auto1.pantera.conda.asto.AstoMergedJson;
import com.auto1.pantera.conda.meta.InfoIndex;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentDisposition;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.multipart.RqMultipart;
import com.auto1.pantera.scheduling.ArtifactEvent;
import io.reactivex.Flowable;
import org.reactivestreams.Publisher;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Slice to update the repository.
 */
public final class UpdateSlice implements Slice {

    /**
     * Regex to obtain uploaded package architecture and name from request line.
     */
    private static final Pattern PKG = Pattern.compile(".*/((.*)/(.*(\\.tar\\.bz2|\\.conda)))$");

    /**
     * Temporary upload key.
     */
    private static final Key TMP = new Key.From(".upload");

    /**
     * Repository type and artifact file extension.
     */
    private static final String CONDA = "conda";

    /**
     * Package size metadata json field.
     */
    private static final String SIZE = "size";

    /**
     * Abstract storage.
     */
    private final Storage asto;

    /**
     * Artifacts events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * @param asto Abstract storage
     * @param events Artifact events
     * @param repoName Repository name
     */
    public UpdateSlice(Storage asto, Optional<Queue<ArtifactEvent>> events, String repoName) {
        this(asto, events, repoName, com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /** Synchronous artifact-index writer. */
    private final com.auto1.pantera.index.SyncArtifactIndexer syncIndex;

    /**
     * Ctor with synchronous index writer.
     */
    public UpdateSlice(Storage asto, Optional<Queue<ArtifactEvent>> events, String repoName,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex) {
        this.asto = asto;
        this.events = events;
        this.repoName = repoName;
        this.syncIndex = syncIndex;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final Matcher matcher = UpdateSlice.PKG.matcher(line.uri().getPath());
        if (matcher.matches()) {
            final Key temp = new Key.From(UpdateSlice.TMP, matcher.group(1));
            final Key main = new Key.From(matcher.group(1));
            return this.asto.exclusively(
                main,
                target -> target.exists(main)
                    .thenCompose(repo -> this.asto.exists(temp).thenApply(upl -> repo || upl))
                    .thenCompose(
                        exists -> this.asto.save(temp, new Content.From(UpdateSlice.filePart(headers, body)))
                        .thenCompose(empty -> this.infoJson(matcher.group(1), temp))
                        .thenCompose(json -> this.addChecksum(temp, Digests.MD5, json))
                        .thenCompose(json -> this.addChecksum(temp, Digests.SHA256, json))
                        .thenApply(JsonObjectBuilder::build)
                        .thenCompose(
                            json -> {
                                // Merge and move under the repodata lock the
                                // management-API delete prunes under: the
                                // package is listed only once its file is
                                // in place, and neither side loses the
                                // other's change.
                                final Key repodata =
                                    new Key.From(matcher.group(2), "repodata.json");
                                CompletionStage<Void> action = new IndexUpdateLock(
                                    this.asto, repodata
                                ).run(
                                    locked -> new AstoMergedJson(locked, repodata).merge(
                                        Collections.singletonMap(matcher.group(3), json)
                                    ).thenCompose(
                                        ignored -> locked.move(temp, main)
                                    )
                                );
                                action = action.thenCompose(nothing -> {
                                    final String pkgName = json.getString("name", "<no name>");
                                    // Real storage key: matcher.group(1) is
                                    // the exact "<arch>/<filename>" key this
                                    // request just moved the package to
                                    // (`main`, above) — the indexed name is
                                    // a synthetic "name_arch" composite
                                    // unrelated to it, so pathPrefix is the
                                    // only way browse-to-directory can find
                                    // the real per-arch directory.
                                    final ArtifactEvent event = new ArtifactEvent(
                                        UpdateSlice.CONDA, this.repoName,
                                        new Login(headers).getValue(),
                                        String.join("_", pkgName, json.getString("arch", "<no arch>")),
                                        json.getString("version"),
                                        json.getJsonNumber(UpdateSlice.SIZE).longValue(),
                                        System.currentTimeMillis(), null,
                                        matcher.group(1)
                                    ).withRequestContext(headers);
                                    this.events.ifPresent(queue -> queue.add(event));
                                    com.auto1.pantera.http.cache.NegativeCacheRegistry
                                        .instance()
                                        .invalidateAfterUpload("conda", pkgName);
                                    com.auto1.pantera.cooldown.metadata
                                        .FilteredMetadataCacheRegistry.instance()
                                        .invalidateAfterUpload("conda", pkgName);
                                    return this.syncIndex.recordSync(event);
                                });
                                return action;
                            }
                        ).thenApply(
                            ignored -> ResponseBuilder.created().build()
                        ).handle(
                            (rsp, err) -> this.completed(temp, rsp, err)
                        ).thenCompose(Function.identity())
                )
            ).toCompletableFuture();
        }
        return ResponseBuilder.badRequest().completedFuture();
    }

    /**
     * Finish an upload: on failure the temporary upload is removed, and a body
     * that is not a conda package is answered with {@code 400} and the reason
     * instead of {@code 500}.
     * @param temp Temporary upload key
     * @param rsp Response of a successful upload
     * @param err Failure, null on success
     * @return Response
     */
    private CompletionStage<Response> completed(final Key temp, final Response rsp,
        final Throwable err) {
        final CompletionStage<Response> res;
        if (err == null) {
            res = CompletableFuture.completedFuture(rsp);
        } else {
            final Throwable cause = UpdateSlice.cause(err);
            res = this.asto.exists(temp).thenCompose(
                present -> present ? this.asto.delete(temp)
                    : CompletableFuture.<Void>completedFuture(null)
            ).thenApply(
                ignored -> {
                    if (cause instanceof InvalidPackageException) {
                        return ResponseBuilder.badRequest()
                            .textBody(cause.getMessage())
                            .build();
                    }
                    throw new CompletionException(cause);
                }
            );
        }
        return res;
    }

    /**
     * Unwrap completion wrappers.
     * @param err Error
     * @return Underlying cause
     */
    private static Throwable cause(final Throwable err) {
        Throwable res = err;
        while ((res instanceof CompletionException || res instanceof ExecutionException)
            && res.getCause() != null) {
            res = res.getCause();
        }
        return res;
    }

    /**
     * Adds checksum of the package to json.
     * @param key Package key
     * @param alg Digest algorithm
     * @param json Json to add value to
     * @return JsonObjectBuilder with added checksum as completion action
     */
    private CompletionStage<JsonObjectBuilder> addChecksum(final Key key, final Digests alg,
        final JsonObjectBuilder json) {
        return this.asto.value(key).thenCompose(val -> new ContentDigest(val, alg).hex())
            .thenApply(hex -> json.add(alg.name().toLowerCase(Locale.US), hex));
    }

    /**
     * Get info index json from uploaded package.
     * @param name Package name
     * @param key Package input stream
     * @return JsonObjectBuilder with package info as completion action
     */
    private CompletionStage<JsonObjectBuilder> infoJson(final String name, final Key key) {
        return this.asto.value(key).thenCompose(
            val -> new ContentAsStream<JsonObjectBuilder>(val).process(
                input -> {
                    final InfoIndex info;
                    if (name.endsWith(UpdateSlice.CONDA)) {
                        info = new InfoIndex.Conda(input);
                    } else {
                        info = new InfoIndex.TarBz(input);
                    }
                    return Json.createObjectBuilder(UpdateSlice.index(info))
                        .add(UpdateSlice.SIZE, val.size().get());
                }
            )
        );
    }

    /**
     * Package metadata ({@code info/index.json}) of an uploaded package.
     * @param info Package index reader
     * @return Index json
     * @throws InvalidPackageException If the upload is not a conda package or
     *  its index lacks the name or version
     */
    private static JsonObject index(final InfoIndex info) {
        final JsonObject json;
        try {
            json = info.json();
        } catch (final IOException | RuntimeException ex) {
            // Compressor, archive and JSON parsers all fail with their own
            // exception types on a body that is not a package.
            throw new InvalidPackageException(
                String.format("The upload is not a valid conda package: %s", ex.getMessage()),
                ex
            );
        }
        for (final String field : new String[]{"name", "version"}) {
            if (!(json.get(field) instanceof JsonString)) {
                throw new InvalidPackageException(
                    String.format(
                        "The upload is not a valid conda package: info/index.json has no %s",
                        field
                    ),
                    null
                );
            }
        }
        return json;
    }

    /**
     * Obtain file part from multipart body.
     * @param headers Request headers
     * @param body Request body
     * @return File part as Publisher of ByteBuffer
     * @todo #32:30min Obtain Content-Length from another multipart body part and return from this
     *  method Content built with length. Content-Length of the file is provided in format:
     *  --multipart boundary
     *  Content-Disposition: form-data; name="Content-Length"
     *  //empty line
     *  2123
     *  --multipart boundary
     *  ...
     *  Multipart body format can be also checked in logs of
     *  CondaSliceITCase#canPublishWithCondaBuild() test method.
     */
    private static Publisher<ByteBuffer> filePart(final Headers headers,
        final Publisher<ByteBuffer> body) {
        return Flowable.fromPublisher(
            new RqMultipart(headers, body).inspect(
                (part, inspector) -> {
                    if ("file".equals(new ContentDisposition(part.headers()).fieldName())) {
                        inspector.accept(part);
                    } else {
                        inspector.ignore(part);
                    }
                    final CompletableFuture<Void> res = new CompletableFuture<>();
                    res.complete(null);
                    return res;
                }
            )
        ).flatMap(part -> part);
    }

    /**
     * The uploaded body is not a conda package.
     * @since 2.2.9
     */
    private static final class InvalidPackageException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Ctor.
         * @param message Reason for the client
         * @param cause Cause, may be null
         */
        InvalidPackageException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}
