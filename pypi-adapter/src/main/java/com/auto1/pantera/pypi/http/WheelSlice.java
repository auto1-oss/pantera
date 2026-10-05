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
package com.auto1.pantera.pypi.http;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentDisposition;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.headers.ReasonPhrase;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.multipart.RqMultipart;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.pypi.NormalizedProjectName;
import com.auto1.pantera.pypi.meta.Metadata;
import com.auto1.pantera.pypi.meta.PackageInfo;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import com.auto1.pantera.pypi.meta.ValidFilename;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.asto.rx.RxFuture;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.Single;
import org.reactivestreams.Publisher;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * WheelSlice save and manage whl and tgz entries.
 */
final class WheelSlice implements Slice {

    private static final String TYPE = "pypi";

    /**
     * Multipart field name carrying the distribution file content.
     */
    private static final String CONTENT_FIELD = "content";

    /**
     * Multipart field name carrying twine's client-declared SHA-256 digest.
     */
    private static final String SHA256_DIGEST_FIELD = "sha256_digest";

    private final Storage storage;

    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /** Synchronous artifact-index writer for read-after-write consistency. */
    private final com.auto1.pantera.index.SyncArtifactIndexer syncIndex;

    /**
     * Whether a published file may never be replaced. When {@code false}
     * a re-upload of an existing file name with different bytes
     * overwrites it and regenerates its sidecar and indexes.
     */
    private final boolean immutable;

    /**
     * Legacy ctor (no synchronous index writer).
     *
     * @param storage Storage.
     * @param events Events queue
     * @param rname Repository name
     */
    WheelSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname) {
        this(storage, events, rname,
            com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous index writer.
     *
     * @param storage Storage.
     * @param events Events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     */
    WheelSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex) {
        this(storage, events, rname, syncIndex, true);
    }

    /**
     * Primary ctor.
     *
     * @param storage Storage.
     * @param events Events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     * @param immutable Whether a published file may never be replaced
     */
    WheelSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname,
        final com.auto1.pantera.index.SyncArtifactIndexer syncIndex,
        final boolean immutable) {
        this.storage = storage;
        this.events = events;
        this.rname = rname;
        this.syncIndex = syncIndex;
        this.immutable = immutable;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers iterable,
        final Content publisher
    ) {
        RequestContextHeaders.bindToMdc(iterable);
        // Captured at slice entry, before any async hop: the pooled threads
        // that run the continuations below never had this request's MDC.
        final AuditContext auditCtx = new AuditContext(iterable);
        final String owner = new Login(iterable).getValue();
        final Key.From key = new Key.From(UUID.randomUUID().toString());
        return this.filePart(iterable, publisher, key).thenCompose(
            uploaded -> this.storage.value(key).thenCompose(
                val -> new ContentAsStream<Metadata.Extracted>(val).process(
                    input -> new Metadata.FromArchive(input, uploaded.filename()).readWithMetadata()
                )
            ).thenCompose(
                extracted -> this.validated(key, uploaded, extracted, iterable, auditCtx, owner)
            )
        ).handle(
            (response, throwable) -> {
                if (throwable != null) {
                    return ResponseBuilder.badRequest(throwable).build();
                }
                return response;
            }
        ).toCompletableFuture();
    }

    /**
     * Check the parsed upload before publishing it: the filename must match
     * the name and version the archive declares about itself, and the saved
     * bytes must match the {@code sha256_digest} twine declared for them
     * (when it declared one). Either violation deletes the temporary upload
     * and answers 400; nothing is stored.
     *
     * @param temp Temporary key holding the upload
     * @param uploaded Uploaded file descriptor (filename + declared digest)
     * @param extracted Package metadata read from the archive
     * @param headers Request headers
     * @param auditCtx Request correlation context captured at slice entry
     * @param owner Uploading user
     * @return Response
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletionStage<Response> validated(
        final Key temp, final UploadedFile uploaded, final Metadata.Extracted extracted,
        final Headers headers, final AuditContext auditCtx, final String owner
    ) {
        final PackageInfo info = extracted.info();
        final String filename = uploaded.filename();
        final CompletionStage<Response> res;
        if (new ValidFilename(info, filename).valid()) {
            res = this.digestMatches(temp, uploaded.declaredSha256()).thenCompose(
                matches -> {
                    final CompletionStage<Response> next;
                    if (matches) {
                        next = this.publish(temp, filename, extracted, headers);
                    } else {
                        next = this.rejectChecksumMismatch(temp, info, filename, auditCtx, owner);
                    }
                    return next;
                }
            );
        } else {
            res = this.storage.delete(temp).thenApply(
                nothing -> ResponseBuilder.badRequest()
                    .textBody(
                        String.format(
                            "Filename '%s' does not match the package metadata"
                                + " (name '%s', version '%s')",
                            filename, info.name(), info.version()
                        )
                    )
                    .build()
            );
        }
        return res;
    }

    /**
     * Compare the client-declared {@code sha256_digest} (when present)
     * against the SHA-256 of the bytes actually saved to {@code temp}. An
     * absent declared digest is a pass (no verification was requested).
     *
     * @param temp Temporary key holding the upload
     * @param declared Client-declared SHA-256 hex digest, or null/blank
     * @return True when there is no declared digest or it matches
     */
    private CompletionStage<Boolean> digestMatches(final Key temp, final String declared) {
        final CompletionStage<Boolean> result;
        if (declared == null || declared.isBlank()) {
            result = CompletableFuture.completedFuture(true);
        } else {
            result = this.sha256(temp).thenApply(
                actual -> actual.equalsIgnoreCase(declared.trim())
            );
        }
        return result;
    }

    /**
     * Refuse an upload whose bytes do not match the digest the client
     * declared for them: delete the temporary upload, emit the
     * {@code artifact_publish}/{@code failure}/{@code checksum_mismatch}
     * audit record and answer 400.
     *
     * @param temp Temporary key holding the upload
     * @param info Package metadata read from the archive
     * @param filename Uploaded filename
     * @param auditCtx Request correlation context captured at slice entry
     * @param owner Uploading user
     * @return 400 response
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletionStage<Response> rejectChecksumMismatch(
        final Key temp, final PackageInfo info, final String filename,
        final AuditContext auditCtx, final String owner
    ) {
        final String packageName = new NormalizedProjectName.Simple(info.name()).value();
        return this.storage.delete(temp).thenApply(
            nothing -> {
                EcsLogger.warn("com.auto1.pantera.pypi")
                    .message("Refused upload whose bytes do not match the declared sha256_digest")
                    .eventCategory("web")
                    .eventAction("artifact_upload")
                    .eventOutcome("failure")
                    .field("event.reason", "checksum_mismatch")
                    .field("repository.name", this.rname)
                    .field("file.name", filename)
                    .field("log.source", "application")
                    .log();
                AuditLogger.publish(
                    auditCtx, WheelSlice.TYPE, this.rname, packageName, info.version(), 0L,
                    owner, null, null,
                    AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_CHECKSUM_MISMATCH
                );
                return ResponseBuilder.badRequest()
                    // twine prints only the status line: say why there, as PyPI does.
                    .header(new ReasonPhrase("Digest mismatch"))
                    .textBody(
                        String.format(
                            "The sha256_digest supplied for '%s' does not match a digest"
                                + " calculated from the uploaded file.",
                            filename
                        )
                    )
                    .build();
            }
        );
    }

    /**
     * Publish a validated upload.
     *
     * <p>The target key is always {@code <normalized-name>/<version>/<file>}
     * relative to the repository root, whatever sub-path the client posted
     * to (twine's conventional {@code /legacy/} included): the storage key,
     * the sidecar and both indexes must agree on one layout, or the package
     * index is rebuilt from the wrong prefix and hides every earlier
     * release.</p>
     *
     * <p>On an immutable repository a released file cannot be replaced
     * (PyPI file-name reuse policy): an identical re-upload is an idempotent
     * 200, a different file under an existing name is refused with 400
     * "File already exists" so pinned hashes keep verifying. On a mutable
     * repository the different file overwrites the existing one (201) and
     * its sidecar and both indexes are regenerated.</p>
     *
     * @param temp Temporary key holding the upload
     * @param filename Uploaded filename
     * @param extracted Package metadata read from the archive
     * @param headers Request headers
     * @return Response
     */
    private CompletionStage<Response> publish(
        final Key temp, final String filename, final Metadata.Extracted extracted,
        final Headers headers
    ) {
        final PackageInfo info = extracted.info();
        final String packageName = new NormalizedProjectName.Simple(info.name()).value();
        final Key name = new Key.From(packageName, info.version(), filename);
        return this.storage.exists(name).thenCompose(
            exists -> {
                final CompletionStage<Response> res;
                if (exists) {
                    res = this.existing(temp, name, packageName, extracted, headers);
                } else {
                    res = this.store(temp, name, packageName, extracted, headers)
                        .thenApply(ignored -> ResponseBuilder.from(RsStatus.CREATED).build());
                }
                return res;
            }
        );
    }

    /**
     * Answer an upload whose target file already exists.
     *
     * @param temp Temporary key holding the upload
     * @param name Existing file key
     * @param packageName Normalized package name
     * @param extracted Package metadata (and PEP 658 METADATA) read from the archive
     * @param headers Request headers
     * @return 200 when the bytes are identical; otherwise 201 after an
     *  overwrite on a mutable repository, 400 on an immutable one
     */
    private CompletionStage<Response> existing(
        final Key temp, final Key name, final String packageName,
        final Metadata.Extracted extracted, final Headers headers
    ) {
        return this.sha256(temp).thenCombine(this.sha256(name), String::equals)
            .thenCompose(
                same -> {
                    final CompletionStage<Response> res;
                    if (same) {
                        res = this.storage.delete(temp)
                            .thenApply(nothing -> ResponseBuilder.ok().build());
                    } else if (this.immutable) {
                        res = this.storage.delete(temp)
                            .thenApply(nothing -> this.refuse(new KeyLastPart(name).get()));
                    } else {
                        res = this.store(temp, name, packageName, extracted, headers).thenApply(
                            ignored -> {
                                EcsLogger.info("com.auto1.pantera.pypi")
                                    .message("Overwrote an existing file on a mutable repository")
                                    .eventCategory("web")
                                    .eventAction("artifact_upload")
                                    .eventOutcome("success")
                                    .field("repository.name", this.rname)
                                    .field("file.name", new KeyLastPart(name).get())
                                    .field("log.source", "application")
                                    .log();
                                return ResponseBuilder.from(RsStatus.CREATED).build();
                            }
                        );
                    }
                    return res;
                }
            );
    }

    /**
     * Refuse the replacement of an existing file with different content.
     *
     * @param filename Uploaded filename
     * @return 400 "File already exists"
     */
    private Response refuse(final String filename) {
        EcsLogger.warn("com.auto1.pantera.pypi")
            .message("Refused re-upload of an existing file with different content")
            .eventCategory("web")
            .eventAction("artifact_upload")
            .eventOutcome("failure")
            .field("event.reason", "file_exists")
            .field("repository.name", this.rname)
            .field("file.name", filename)
            .field("log.source", "application")
            .log();
        return ResponseBuilder.badRequest()
            // twine prints only the status line: say why there, as PyPI does.
            .header(new ReasonPhrase("File already exists"))
            .textBody(
                String.format(
                    "File already exists: '%s'. A published file cannot be"
                        + " replaced; publish a new version instead.",
                    filename
                )
            )
            .build();
    }

    /**
     * SHA-256 of a stored value.
     * @param key Key
     * @return Hex digest
     */
    private CompletionStage<String> sha256(final Key key) {
        return this.storage.value(key).thenCompose(
            value -> new ContentDigest(value, Digests.SHA256).hex()
        );
    }

    /**
     * Move the upload into place, record it, persist its PEP 658
     * {@code .metadata} file, write its sidecar and rebuild the package and
     * repository indexes.
     *
     * @param temp Temporary key holding the upload
     * @param name Target key
     * @param packageName Normalized package name
     * @param extracted Package metadata read from the archive
     * @param headers Request headers
     * @return Completion
     */
    private CompletionStage<Void> store(
        final Key temp, final Key name, final String packageName,
        final Metadata.Extracted extracted, final Headers headers
    ) {
        final PackageInfo info = extracted.info();
        CompletionStage<Void> move = this.storage.move(temp, name);
        if (this.events.isPresent()) {
            move = move.thenCompose(ignored -> this.putArtifactToQueue(name, info, headers));
        }
        // PEP 658: persist the distribution's core metadata as a sibling
        // "<file>.metadata" so resolvers can read it without downloading the
        // archive, then record its digest in the sidecar so the index can
        // advertise it as data-core-metadata / core-metadata (PEP 714).
        final byte[] metadata = extracted.rawMetadata();
        final Key metadataKey = new Key.From(name.string() + ".metadata");
        return move.thenCompose(
            ignored -> this.storage.save(metadataKey, new Content.From(metadata))
        ).thenCompose(
            ignored -> new ContentDigest(new Content.From(metadata), Digests.SHA256).hex()
        ).thenCompose(
            sha256 -> PypiSidecar.write(
                this.storage,
                name,
                info.requiresPython(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                sha256
            )
        ).thenCompose(
            ignored -> new IndexGenerator(
                this.storage, new Key.From(packageName), "/"
            ).generate()
        ).thenCompose(
            ignored -> new IndexGenerator(this.storage, Key.ROOT, "/").generateRepoIndex()
        );
    }

    /**
     * File part from multipart body.
     * @param headers Request headers
     * @param body Request body
     * @param temp Temp key to save the part
     * @return Uploaded file descriptor
     */
    private CompletionStage<UploadedFile> filePart(final Headers headers,
        final Publisher<ByteBuffer> body, final Key temp) {
        return Flowable.fromPublisher(
            new RqMultipart(headers, body).inspect(
                (part, inspector) -> {
                    final String field = new ContentDisposition(part.headers()).fieldName();
                    if (CONTENT_FIELD.equals(field) || SHA256_DIGEST_FIELD.equals(field)) {
                        inspector.accept(part);
                    } else {
                        inspector.ignore(part);
                    }
                    final CompletableFuture<Void> res = new CompletableFuture<>();
                    res.complete(null);
                    return res;
                }
            )
        ).doOnNext(
            part -> EcsLogger.debug("com.auto1.pantera.pypi")
                .message("WS: multipart request body parsed, part found: " + part.toString())
                .eventCategory("web")
                .eventAction("upload")
                .field("log.source", "application")
                .log()
        ).flatMapSingle(
            part -> this.readPart(part, temp)
        ).toList().map(
            WheelSlice::toUploadedFile
        ).to(SingleInterop.get());
    }

    /**
     * Read a single accepted multipart field. The {@code content} field is
     * streamed to the temp storage key; any other accepted field ({@code
     * sha256_digest}) is buffered as a trimmed UTF-8 string.
     * @param part Accepted multipart part
     * @param temp Temp key to save the {@code content} part to
     * @return Field name/value pair
     */
    private Single<PartValue> readPart(final RqMultipart.Part part, final Key temp) {
        final String field = new ContentDisposition(part.headers()).fieldName();
        final Single<PartValue> result;
        if (CONTENT_FIELD.equals(field)) {
            result = RxFuture.single(
                this.storage.save(temp, new Content.From(part))
                    .thenRun(() -> EcsLogger.debug("com.auto1.pantera.pypi")
                        .message("WS: content saved to temp file")
                        .eventCategory("web")
                        .eventAction("upload")
                        .field("file.name", temp.string())
                        .log())
                    .thenApply(nothing ->
                        new PartValue(field, new ContentDisposition(part.headers()).fileName()))
            );
        } else {
            result = RxFuture.single(
                new Content.From(part).asStringFuture()
                    .thenApply(value -> new PartValue(field, value.trim()))
            );
        }
        return result;
    }

    /**
     * Combine the accepted parts into a single {@link UploadedFile},
     * enforcing the "exactly one content part" invariant.
     * @param items Accepted field name/value pairs
     * @return Uploaded file descriptor
     */
    private static UploadedFile toUploadedFile(final List<PartValue> items) {
        String filename = null;
        String digest = null;
        for (final PartValue item : items) {
            if (CONTENT_FIELD.equals(item.field())) {
                if (filename != null) {
                    throw new PanteraException("multiple content parts were found");
                }
                filename = item.value();
            } else if (SHA256_DIGEST_FIELD.equals(item.field())) {
                digest = item.value();
            }
        }
        if (filename == null) {
            throw new PanteraException("content part was not found");
        }
        return new UploadedFile(filename, digest);
    }

    /**
     * Put uploaded artifact info into events queue.
     * @param key Artifact key in the storage
     * @param info Artifact info
     * @param headers Request headers
     * @return Completion action
     */
    private CompletionStage<Void> putArtifactToQueue(
        final Key key, final PackageInfo info,
        Headers headers
    ) {
        final String normalized = new NormalizedProjectName.Simple(info.name()).value();
        return this.storage.metadata(key).thenApply(meta -> meta.read(Meta.OP_SIZE).get())
            .thenCompose(size -> {
                final ArtifactEvent event = new ArtifactEvent(
                    WheelSlice.TYPE,
                    this.rname,
                    new Login(headers).getValue(),
                    normalized,
                    info.version(),
                    size,
                    System.currentTimeMillis(),
                    null,
                    key.string()
                ).withRequestContext(headers);
                this.events.ifPresent(queue -> queue.add(event));
                // Drop any cached 404 for this package so requests that
                // 404'd before publish do not keep returning 404.
                com.auto1.pantera.http.cache.NegativeCacheRegistry.instance()
                    .invalidateAfterUpload("pypi", normalized);
                com.auto1.pantera.cooldown.metadata.FilteredMetadataCacheRegistry.instance()
                    .invalidateAfterUpload("pypi", normalized);
                return this.syncIndex.recordSync(event);
            });
    }

    /**
     * Multipart form fields captured from a twine upload: the file content's
     * declared name and, when present, the client-declared SHA-256 digest.
     * @param filename Declared distribution filename (from the content part)
     * @param declaredSha256 Client-declared SHA-256 hex digest, or null when absent
     */
    private record UploadedFile(String filename, String declaredSha256) {
    }

    /**
     * A single accepted multipart field: its form field name and captured value.
     * @param field Multipart field name
     * @param value Field value — the temp-saved filename for {@code content},
     *              the raw digest string for {@code sha256_digest}
     */
    private record PartValue(String field, String value) {
    }
}
