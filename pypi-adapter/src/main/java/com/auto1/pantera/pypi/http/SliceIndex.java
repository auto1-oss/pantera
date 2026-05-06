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

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RequestLinePrefix;
import com.auto1.pantera.pypi.NormalizedProjectName;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import com.auto1.pantera.asto.rx.RxFuture;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.Single;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * SliceIndex returns formatted html output with index of repository packages.
 */
final class SliceIndex implements Slice {

    /**
     * Metadata folder for PyPI indices.
     */
    private static final String PYPI_METADATA = ".pypi";

    /**
     * Simple index filename for repo-level index (HTML).
     */
    private static final String SIMPLE_HTML = "simple.html";

    /**
     * Simple index filename for repo-level index (JSON, PEP 691).
     */
    private static final String SIMPLE_JSON = "simple.json";

    /**
     * Pantera artifacts storage.
     */
    private final Storage storage;

    /**
     * @param storage Storage
     */
    SliceIndex(final Storage storage) {
        this.storage = storage;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content publisher) {
        final SimpleApiFormat format = SimpleApiFormat.fromHeaders(headers);
        final String raw = line.uri().getPath();
        final String trimmed = raw.startsWith("/") ? raw.substring(1) : raw;
        final String normalized = trimmed.replaceAll("/+$", "");
        final List<String> segments = normalized.isEmpty()
            ? List.of()
            : Arrays.stream(normalized.split("/"))
                .filter(part -> !part.isEmpty())
                .collect(Collectors.toList());
        final String pathForPrefix = String.join("/", segments);
        final String prefix = new RequestLinePrefix(pathForPrefix, headers).get();

        final boolean repoIndex = isRepoIndexRequest(segments);
        // Resolve the persisted cache key for the requested format.
        // IndexGenerator writes both <pkg>.html (PEP 503) and
        // <pkg>.json (PEP 691) side-by-side so either can be served
        // directly from storage without regenerating per request.
        final Key indexKey;
        final Key listKey;
        final String packageName;
        if (repoIndex) {
            indexKey = new Key.From(
                PYPI_METADATA,
                format == SimpleApiFormat.JSON ? SIMPLE_JSON : SIMPLE_HTML
            );
            listKey = Key.ROOT;
            packageName = "";
        } else {
            final boolean underSimple = !segments.isEmpty() && isSimpleSegment(segments.get(0));
            final String last = segments.get(segments.size() - 1);
            final String rawPackageName;
            if (underSimple) {
                rawPackageName = segments.get(1);
            } else {
                final boolean endsWithIndex = "index.html".equalsIgnoreCase(last) && segments.size() > 1;
                rawPackageName = endsWithIndex
                    ? segments.get(segments.size() - 2)
                    : last;
            }
            // Normalize package name according to PEP 503
            // This ensures that requests for "sm-pipelines", "sm_pipelines", "SM-Pipelines" etc.
            // all resolve to the same normalized storage path
            packageName = new NormalizedProjectName.Simple(rawPackageName).value();
            listKey = new Key.From(packageName);
            final String indexFilename = packageName
                + (format == SimpleApiFormat.JSON ? ".json" : ".html");
            indexKey = new Key.From(PYPI_METADATA, packageName, indexFilename);
        }

        return this.storage.exists(indexKey).thenCompose(
            exists -> {
                // Fast path: the requested format is already persisted.
                // Both HTML and JSON can land here because IndexGenerator
                // writes them side-by-side on every upload. If a legacy
                // installation only has the HTML cache (pre-PEP-691
                // upload flow), the JSON fast path misses and we fall
                // through to the dynamic generator below, which also
                // populates nothing — the JSON cache will be created
                // the next time that package is uploaded to or
                // reindexed.
                if (exists) {
                    return this.storage.value(indexKey).thenApply(
                        content -> {
                            if (!packageName.isEmpty()) {
                                AuditLogger.resolution(packageName);
                            }
                            return ResponseBuilder.ok()
                                .header("Content-Type", format.contentType() + "; charset=utf-8")
                                .body(content)
                                .build();
                        }
                    );
                }
                // Self-healing cache: dynamically generate the index
                // for this request AND save the result to storage so
                // the next request takes the fast path above. This
                // covers packages uploaded before the dual-format
                // IndexGenerator was deployed — no separate backfill
                // tool is needed.
                return this.generateAndCacheIndex(
                    listKey, prefix, packageName, format, indexKey
                );
            }
        ).toCompletableFuture();
    }

    /**
     * Generate a dynamic index AND persist it to storage so the next
     * request hits the fast path. The response is served from the
     * dynamic generator immediately. If it's a successful response
     * (i.e., the package exists and has files), the body bytes are
     * saved to the cache key in the background (fire-and-forget).
     * 404 responses (empty/missing packages) are returned as-is
     * without caching.
     */
    private CompletableFuture<Response> generateAndCacheIndex(
        final Key list,
        final String prefix,
        final String packageName,
        final SimpleApiFormat format,
        final Key cacheKey
    ) {
        return this.generateDynamicIndex(list, prefix, packageName, format)
            .thenCompose(response -> {
                // Only cache successful responses. 404 (empty package)
                // must propagate untouched — caching it would make an
                // empty response sticky even after a future upload.
                if (!response.status().success()) {
                    return CompletableFuture.completedFuture(response);
                }
                // Drain the body into a byte array so we can both
                // serve it and persist it. The drain is fully async
                // (no .join()) so we never block the Vert.x event
                // loop even when the storage layer pushes chunks
                // asynchronously. Index bodies are small (a few KB
                // of HTML/JSON links) so the in-memory copy is fine.
                return drainBody(response.body()).thenApply(body -> {
                    // Fire-and-forget: save to storage so the next
                    // request uses the persisted cache.
                    if (body.length > 0) {
                        this.storage.save(
                            cacheKey,
                            new Content.From(body)
                        ).exceptionally(err -> null);
                    }
                    return ResponseBuilder.ok()
                        .header("Content-Type", format.contentType() + "; charset=utf-8")
                        .body(new Content.From(body))
                        .build();
                });
            });
    }

    /**
     * Drain a reactive Content body into a byte array. Fully async —
     * never blocks the calling thread (safe for Vert.x event loop).
     *
     * @param content Content to drain
     * @return Future yielding the complete body bytes
     */
    private static CompletableFuture<byte[]> drainBody(final Content content) {
        final CompletableFuture<byte[]> future = new CompletableFuture<>();
        content.subscribe(
            new org.reactivestreams.Subscriber<>() {
                private final java.io.ByteArrayOutputStream out =
                    new java.io.ByteArrayOutputStream();
                @Override
                public void onSubscribe(
                    final org.reactivestreams.Subscription sub
                ) {
                    sub.request(Long.MAX_VALUE);
                }
                @Override
                public void onNext(final java.nio.ByteBuffer buf) {
                    final byte[] arr = new byte[buf.remaining()];
                    buf.get(arr);
                    this.out.write(arr, 0, arr.length);
                }
                @Override
                public void onError(final Throwable err) {
                    future.completeExceptionally(err);
                }
                @Override
                public void onComplete() {
                    future.complete(this.out.toByteArray());
                }
            }
        );
        return future;
    }

    /**
     * Generate index dynamically from storage.
     *
     * @param list List key to scan
     * @param prefix URL prefix
     * @param packageName Normalized package name (used for JSON rendering)
     * @param format Response format (HTML or JSON)
     * @return Response future
     */
    private CompletableFuture<Response> generateDynamicIndex(
        final Key list,
        final String prefix,
        final String packageName,
        final SimpleApiFormat format
    ) {
        // Use non-blocking RxFuture.single instead of blocking SingleInterop.fromFuture
        return RxFuture.single(this.storage.list(list))
            .flatMap(keys -> {
                // Return 404 if package doesn't exist (empty directory)
                if (keys.isEmpty()) {
                    return Single.just(ResponseBuilder.notFound().build());
                }
                if (format == SimpleApiFormat.JSON) {
                    // JSON path: collect FileEntry objects for each file.
                    // PEP 691 recommends relative URLs. We use the same
                    // relative href pattern as the HTML path (key.string()
                    // relative to the package dir), NOT prefix + key which
                    // can produce protocol-relative "//pkg/..." URLs when
                    // the prefix is "/".
                    return Flowable.fromIterable(keys)
                        .concatMapSingle(
                            key -> RxFuture.single(
                                this.storage.list(key).thenCompose(
                                    subKeys -> {
                                        if (subKeys.isEmpty()) {
                                            // It's a file, not a directory
                                            return this.storage.value(key).thenCompose(
                                                value -> new ContentDigest(value, Digests.SHA256).hex()
                                            ).thenCompose(
                                                hex -> PypiSidecar.read(this.storage, key).thenApply(
                                                    meta -> {
                                                        final List<SimpleJsonRenderer.FileEntry> result = new ArrayList<>(1);
                                                        result.add(buildJsonEntry(
                                                            new KeyLastPart(key).get(),
                                                            key.string(),
                                                            hex,
                                                            meta
                                                        ));
                                                        return result;
                                                    }
                                                )
                                            );
                                        } else {
                                            // It's a directory - process all files in it
                                            return Flowable.fromIterable(subKeys)
                                                .concatMapSingle(
                                                    subKey -> RxFuture.single(
                                                        this.storage.value(subKey).thenCompose(
                                                            value -> new ContentDigest(value, Digests.SHA256).hex()
                                                        ).thenCompose(
                                                            hex -> PypiSidecar.read(this.storage, subKey).thenApply(
                                                                meta -> {
                                                                    final String versionPath = new KeyLastPart(
                                                                        new Key.From(subKey.parent().get())
                                                                    ).get();
                                                                    final String filename = new KeyLastPart(subKey).get();
                                                                    return buildJsonEntry(
                                                                        filename,
                                                                        String.format("%s/%s", versionPath, filename),
                                                                        hex,
                                                                        meta
                                                                    );
                                                                }
                                                            )
                                                        )
                                                    )
                                                )
                                                .toList()
                                                .to(SingleInterop.get())
                                                .toCompletableFuture();
                                        }
                                    }
                                )
                            )
                        )
                        .flatMapIterable(chunk -> chunk)
                        .toList()
                        .map(
                            entries -> {
                                final String json = SimpleJsonRenderer.render(packageName, entries);
                                AuditLogger.resolution(packageName);
                                return ResponseBuilder.ok()
                                    .header("Content-Type", SimpleApiFormat.JSON.contentType())
                                    .body(
                                        new Content.From(json.getBytes(StandardCharsets.UTF_8))
                                    )
                                    .build();
                            }
                        );
                } else {
                    // HTML path: build enriched HTML with sidecar attributes
                    return Flowable.fromIterable(keys)
                        .concatMapSingle(
                            // Use non-blocking RxFuture.single instead of blocking Single.fromFuture
                            key -> RxFuture.single(
                                // Try to list this key as a directory (version folder)
                                this.storage.list(key).thenCompose(
                                    subKeys -> {
                                        if (subKeys.isEmpty()) {
                                            // It's a file, not a directory - process it directly
                                            return this.storage.value(key).thenCompose(
                                                value -> new ContentDigest(value, Digests.SHA256).hex()
                                            ).thenCompose(
                                                hex -> PypiSidecar.read(this.storage, key).thenApply(
                                                    meta -> {
                                                        final String attrs = meta
                                                            .map(SliceIndex::buildHtmlAttributes)
                                                            .orElse("");
                                                        return String.format(
                                                            "<a href=\"%s#sha256=%s\"%s>%s</a><br/>",
                                                            String.format("%s/%s", prefix, key.string()),
                                                            hex,
                                                            attrs,
                                                            new KeyLastPart(key).get()
                                                        );
                                                    }
                                                )
                                            );
                                        } else {
                                            // It's a directory - process all files in it
                                            // Use concatMapSingle to preserve ordering
                                            return Flowable.fromIterable(subKeys)
                                                .concatMapSingle(
                                                    // Use non-blocking RxFuture.single
                                                    subKey -> RxFuture.single(
                                                        this.storage.value(subKey).thenCompose(
                                                            value -> new ContentDigest(value, Digests.SHA256).hex()
                                                        ).thenCompose(
                                                            hex -> PypiSidecar.read(this.storage, subKey).thenApply(
                                                                meta -> {
                                                                    final String attrs = meta
                                                                        .map(SliceIndex::buildHtmlAttributes)
                                                                        .orElse("");
                                                                    return String.format(
                                                                        "<a href=\"%s#sha256=%s\"%s>%s</a><br/>",
                                                                        String.format("%s/%s", prefix, subKey.string()),
                                                                        hex,
                                                                        attrs,
                                                                        new KeyLastPart(subKey).get()
                                                                    );
                                                                }
                                                            )
                                                        )
                                                    )
                                                )
                                                .collect(StringBuilder::new, StringBuilder::append)
                                                .map(StringBuilder::toString)
                                                .to(SingleInterop.get())
                                                .toCompletableFuture();
                                        }
                                    }
                                )
                            )
                        )
                        .collect(StringBuilder::new, StringBuilder::append)
                        .map(
                            resp -> {
                                AuditLogger.resolution(packageName);
                                return ResponseBuilder.ok()
                                    .htmlBody(
                                        String.format(
                                            "<!DOCTYPE html>\n<html>\n  <body>\n%s\n</body>\n</html>",
                                            resp.toString()
                                        ), StandardCharsets.UTF_8)
                                    .build();
                            }
                        );
                }
            }).to(SingleInterop.get()).toCompletableFuture();
    }

    /**
     * Build PEP 503 HTML data-* attribute string from sidecar metadata.
     *
     * @param meta Sidecar metadata
     * @return Attribute string (may be empty)
     */
    private static String buildHtmlAttributes(final PypiSidecar.Meta meta) {
        final StringBuilder attrs = new StringBuilder();
        if (meta.requiresPython() != null && !meta.requiresPython().isEmpty()) {
            attrs.append(String.format(" data-requires-python=\"%s\"",
                meta.requiresPython().replace(">", "&gt;").replace("<", "&lt;")));
        }
        if (meta.yanked()) {
            final String reason = meta.yankedReason().orElse("");
            attrs.append(String.format(" data-yanked=\"%s\"", reason));
        }
        if (meta.distInfoMetadata().isPresent()) {
            attrs.append(String.format(" data-dist-info-metadata=\"sha256=%s\"",
                meta.distInfoMetadata().get()));
        }
        return attrs.toString();
    }

    /**
     * Build a {@link SimpleJsonRenderer.FileEntry} from storage artifacts.
     *
     * @param filename File name (last path segment)
     * @param url Full URL for the file
     * @param sha256 SHA-256 hex digest
     * @param meta Optional sidecar metadata
     * @return FileEntry for JSON rendering
     */
    private static SimpleJsonRenderer.FileEntry buildJsonEntry(
        final String filename,
        final String url,
        final String sha256,
        final Optional<PypiSidecar.Meta> meta
    ) {
        final String requiresPython = meta.map(PypiSidecar.Meta::requiresPython).orElse(null);
        final java.time.Instant uploadTime = meta.map(PypiSidecar.Meta::uploadTime).orElse(null);
        final boolean yanked = meta.map(PypiSidecar.Meta::yanked).orElse(false);
        final Optional<String> yankedReason = meta.flatMap(PypiSidecar.Meta::yankedReason);
        final Optional<String> distInfoMetadata = meta.flatMap(PypiSidecar.Meta::distInfoMetadata);
        return new SimpleJsonRenderer.FileEntry(
            filename, url, sha256, requiresPython, uploadTime, yanked, yankedReason, distInfoMetadata
        );
    }

    private static boolean isRepoIndexRequest(final List<String> segments) {
        return segments.isEmpty()
            || (segments.size() == 1 && isSimpleSegment(segments.get(0)))
            || (segments.size() == 1 && "index.html".equalsIgnoreCase(segments.get(0)))
            || (segments.size() == 2 && isSimpleSegment(segments.get(0))
                && "index.html".equalsIgnoreCase(segments.get(1)));
    }

    private static boolean isSimpleSegment(final String segment) {
        return "simple".equalsIgnoreCase(segment);
    }
}
