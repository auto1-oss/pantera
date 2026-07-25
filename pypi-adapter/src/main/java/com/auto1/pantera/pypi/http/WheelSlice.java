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
import com.auto1.pantera.asto.Meta;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.streams.ContentAsStream;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.ContentDisposition;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.multipart.RqMultipart;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.slice.KeyFromPath;
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
import org.slf4j.MDC;

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
        this.storage = storage;
        this.events = events;
        this.rname = rname;
        this.syncIndex = syncIndex;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers iterable,
        final Content publisher
    ) {
        RequestContextHeaders.bindToMdc(iterable);
        final AuditContext auditCtx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        final String owner = new Login(iterable).getValue();
        final Key.From key = new Key.From(UUID.randomUUID().toString());
        return this.filePart(iterable, publisher, key).thenCompose(
            uploaded -> this.storage.value(key).thenCompose(
                val -> new ContentAsStream<Metadata.Extracted>(val).process(
                    input -> new Metadata.FromArchive(input, uploaded.filename()).readWithMetadata()
                )
            ).thenCompose(
                extracted -> this.handleParsed(line, iterable, key, uploaded, extracted, auditCtx, owner)
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
     * Handle a parsed archive: reject an invalid filename outright, otherwise
     * verify the declared digest and the no-overwrite invariant before persisting.
     * @param line Request line
     * @param headers Request headers
     * @param key Temp key holding the saved content bytes
     * @param uploaded Uploaded file descriptor (filename + declared digest)
     * @param extracted Parsed package info plus raw PEP 658 metadata bytes
     * @param auditCtx Request correlation context
     * @param owner Uploading user
     * @return HTTP response
     */
    private CompletionStage<Response> handleParsed(
        final RequestLine line, final Headers headers, final Key key,
        final UploadedFile uploaded, final Metadata.Extracted extracted,
        final AuditContext auditCtx, final String owner
    ) {
        final CompletionStage<RsStatus> status;
        if (new ValidFilename(extracted.info(), uploaded.filename()).valid()) {
            status = this.persistOrReject(line, headers, key, uploaded, extracted, auditCtx, owner);
        } else {
            status = this.storage.delete(key).thenApply(nothing -> RsStatus.BAD_REQUEST);
        }
        return status.thenApply(s -> ResponseBuilder.from(s).build());
    }

    /**
     * Verify the declared digest (when present) and the no-overwrite invariant,
     * rejecting the upload on either violation; persist otherwise.
     * @param line Request line
     * @param headers Request headers
     * @param key Temp key holding the saved content bytes
     * @param uploaded Uploaded file descriptor (filename + declared digest)
     * @param extracted Parsed package info plus raw PEP 658 metadata bytes
     * @param auditCtx Request correlation context
     * @param owner Uploading user
     * @return Resulting HTTP status
     */
    private CompletionStage<RsStatus> persistOrReject(
        final RequestLine line, final Headers headers, final Key key,
        final UploadedFile uploaded, final Metadata.Extracted extracted,
        final AuditContext auditCtx, final String owner
    ) {
        final PackageInfo info = extracted.info();
        final String packageName = new NormalizedProjectName.Simple(info.name()).value();
        final Key name = new Key.From(
            new KeyFromPath(line.uri().toString()), packageName, info.version(), uploaded.filename()
        );
        return this.digestMatches(key, uploaded.declaredSha256()).thenCompose(
            matches -> matches
                ? this.checkDuplicateAndPersist(
                    line, headers, key, name, packageName, extracted, uploaded.filename()
                )
                : this.rejectChecksumMismatch(key, packageName, info.version(), auditCtx, owner)
        );
    }

    /**
     * Reject a re-upload of an already-present distribution filename with 409;
     * persist otherwise.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletionStage<RsStatus> checkDuplicateAndPersist(
        final RequestLine line, final Headers headers, final Key key, final Key name,
        final String packageName, final Metadata.Extracted extracted, final String filename
    ) {
        return this.storage.exists(name).thenCompose(
            exists -> exists
                ? this.rejectDuplicate(key, packageName, extracted.info().version(), filename)
                : this.persistUpload(line, headers, key, name, packageName, extracted, filename)
        );
    }

    /**
     * Compare the client-declared {@code sha256_digest} (when present) against
     * the SHA-256 of the bytes actually saved to {@code key}. Absent declared
     * digest is treated as a pass-through (no verification requested).
     * @param key Temp key holding the saved content bytes
     * @param declaredSha256 Client-declared SHA-256 hex digest, or null/blank
     * @return True if there is no declared digest or it matches
     */
    private CompletionStage<Boolean> digestMatches(final Key key, final String declaredSha256) {
        final CompletionStage<Boolean> result;
        if (declaredSha256 == null || declaredSha256.isBlank()) {
            result = CompletableFuture.completedFuture(true);
        } else {
            result = this.storage.value(key).thenCompose(
                value -> new ContentDigest(value, Digests.SHA256).hex()
            ).thenApply(actual -> actual.equalsIgnoreCase(declaredSha256.trim()));
        }
        return result;
    }

    /**
     * Delete the temp upload and emit the {@code artifact_publish}/{@code
     * failure}/{@code checksum_mismatch} audit record.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletionStage<RsStatus> rejectChecksumMismatch(
        final Key key, final String packageName, final String version,
        final AuditContext auditCtx, final String owner
    ) {
        return this.storage.delete(key).thenApply(nothing -> {
            AuditLogger.publish(
                auditCtx, TYPE, this.rname, packageName, version, 0L, owner, null, null,
                AuditLogger.OUTCOME_FAILURE, AuditLogger.REASON_CHECKSUM_MISMATCH
            );
            return RsStatus.BAD_REQUEST;
        });
    }

    /**
     * Delete the temp upload and log the duplicate-filename rejection.
     */
    private CompletionStage<RsStatus> rejectDuplicate(
        final Key key, final String packageName, final String version, final String filename
    ) {
        return this.storage.delete(key).thenApply(nothing -> {
            EcsLogger.warn("com.auto1.pantera.pypi")
                .message("PyPI upload rejected: distribution file already exists")
                .eventCategory("file")
                .eventAction("upload")
                .eventOutcome("failure")
                .field("event.reason", "duplicate_filename")
                .field("repository.name", this.rname)
                .field("package.name", packageName)
                .field("package.version", version)
                .field("file.name", filename)
                .field("log.source", "application")
                .log();
            return RsStatus.CONFLICT;
        });
    }

    /**
     * Move the verified, non-duplicate upload into place, persist the PEP 658
     * {@code .metadata} sidecar file, and regenerate the package/repo indices.
     * @checkstyle ParameterNumberCheck (5 lines)
     */
    private CompletionStage<RsStatus> persistUpload(
        final RequestLine line, final Headers headers, final Key key, final Key name,
        final String packageName, final Metadata.Extracted extracted, final String filename
    ) {
        final PackageInfo info = extracted.info();
        CompletionStage<Void> move = this.storage.move(key, name);
        if (this.events.isPresent()) {
            move = move.thenCompose(
                ignored -> this.putArtifactToQueue(name, info, headers)
            );
        }
        // PEP 658: persist the distribution's core metadata as a sibling
        // "<file>.metadata" file so it can be served without downloading
        // the wheel body, then record its sha256 in the sidecar so the
        // index can advertise data-core-metadata / core-metadata.
        final Key metadataKey = new Key.From(name.string() + ".metadata");
        final CompletableFuture<String> metadataSha256 = this.storage.save(
            metadataKey, new Content.From(extracted.rawMetadata())
        ).thenCompose(
            ignored -> this.storage.value(metadataKey)
        ).thenCompose(
            value -> new ContentDigest(value, Digests.SHA256).hex()
        ).toCompletableFuture();
        move = move.thenCompose(ignored -> metadataSha256).thenCompose(
            sha256 -> PypiSidecar.write(
                this.storage,
                new Key.From(packageName, info.version(), filename),
                info.requiresPython(),
                Instant.now().truncatedTo(ChronoUnit.MICROS),
                sha256
            )
        );
        // Regenerate package-level index.html after upload
        final Key packageKey = new Key.From(
            new KeyFromPath(line.uri().toString()),
            packageName
        );
        move = move.thenCompose(
            ignored -> new IndexGenerator(
                this.storage,
                packageKey,
                line.uri().getPath()
            ).generate()
        );
        // Regenerate repository-level index.html
        final Key repoKey = new KeyFromPath(line.uri().toString());
        move = move.thenCompose(
            ignored -> new IndexGenerator(
                this.storage,
                repoKey,
                line.uri().getPath()
            ).generateRepoIndex()
        );
        return move.thenApply(ignored -> RsStatus.CREATED);
    }

    /**
     * File part from multipart body. Captures the {@code content} part
     * (saved to {@code temp}) and, when present, twine's {@code
     * sha256_digest} form field for later integrity verification.
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
                    size
                );
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
