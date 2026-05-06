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
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.asto.ext.KeyLastPart;
import com.auto1.pantera.asto.rx.RxFuture;
import com.auto1.pantera.pypi.meta.PypiSidecar;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.Single;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Generates and saves persistent index.html for a package.
 * 
 * @since 1.0
 */
public final class IndexGenerator {

    /**
     * Metadata folder for PyPI indices.
     */
    private static final String PYPI_METADATA = ".pypi";

    /**
     * Index filename.
     */
    private static final String INDEX_HTML = "index.html";

    /**
     * Simple index filename for repo-level index.
     */
    private static final String SIMPLE_HTML = "simple.html";

    /**
     * Simple index JSON filename for repo-level index (PEP 691).
     */
    private static final String SIMPLE_JSON = "simple.json";

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Package key (e.g., "pypi-repo/hello").
     */
    private final Key packageKey;

    /**
     * Ctor.
     *
     * @param storage Storage
     * @param packageKey Package key
     * @param prefix URL prefix (reserved for absolute-URL emission in future index variants)
     */
    public IndexGenerator(final Storage storage, final Key packageKey, final String prefix) { // NOPMD UnusedFormalParameter - public API; prefix is reserved for absolute-URL emission and kept for source-compatibility
        this.storage = storage;
        this.packageKey = packageKey;
    }

    /**
     * Structured entry collected once from storage and rendered into
     * both the HTML and JSON index variants. Avoids traversing storage
     * twice when we need to emit both formats side-by-side.
     */
    private static final class Entry {
        /** Filename shown in the index (last path segment). */
        private final String filename;
        /** Relative URL for the HTML anchor target. */
        private final String relativeHref;
        /** Hex SHA-256 of the file content. */
        private final String sha256;
        /** Sidecar metadata (may be empty for legacy uploads). */
        private final Optional<PypiSidecar.Meta> meta;

        Entry(
            final String filename,
            final String relativeHref,
            final String sha256,
            final Optional<PypiSidecar.Meta> meta
        ) {
            this.filename = filename;
            this.relativeHref = relativeHref;
            this.sha256 = sha256;
            this.meta = meta;
        }
    }

    /**
     * Generate and save the package index in BOTH the PEP 503 HTML and
     * PEP 691 JSON variants side-by-side.
     *
     * <p>Storage is traversed once, entries are collected into memory,
     * and both formats are rendered from the same structured data so
     * the per-upload cost stays bounded to a single storage pass.
     * Both files are written so that {@link SliceIndex} can serve
     * either format from persisted storage without per-request
     * regeneration.</p>
     *
     * @return Completion future that resolves when both files are saved
     */
    public CompletableFuture<Void> generate() {
        return RxFuture.single(this.storage.list(this.packageKey))
            .flatMapPublisher(Flowable::fromIterable)
            .concatMapSingle(
                key -> RxFuture.single(
                    this.storage.list(key).thenCompose(
                        subKeys -> {
                            final List<CompletableFuture<Entry>> futures = new ArrayList<>();
                            if (subKeys.isEmpty()) {
                                // Key is a file directly under the package dir
                                futures.add(buildEntry(key, new KeyLastPart(key).get()));
                            } else {
                                // Key is a version dir; iterate files
                                for (final Key subKey : subKeys) {
                                    final String versionPath = new KeyLastPart(
                                        new Key.From(subKey.parent().get())
                                    ).get();
                                    final String filename = new KeyLastPart(subKey).get();
                                    futures.add(buildEntry(
                                        subKey,
                                        String.format("%s/%s", versionPath, filename)
                                    ));
                                }
                            }
                            return allOf(futures);
                        }
                    )
                )
            )
            .flatMapIterable(chunk -> chunk)
            .toList()
            .to(SingleInterop.get())
            .thenCompose(
                entries -> {
                    final String packageName = new KeyLastPart(this.packageKey).get();
                    final String html = renderHtml(entries);
                    final String json = renderJson(packageName, entries);
                    final Key htmlKey = new Key.From(
                        PYPI_METADATA, packageName, packageName + ".html"
                    );
                    final Key jsonKey = new Key.From(
                        PYPI_METADATA, packageName, packageName + ".json"
                    );
                    return CompletableFuture.allOf(
                        this.storage.save(
                            htmlKey,
                            new Content.From(html.getBytes(StandardCharsets.UTF_8))
                        ).toCompletableFuture(),
                        this.storage.save(
                            jsonKey,
                            new Content.From(json.getBytes(StandardCharsets.UTF_8))
                        ).toCompletableFuture()
                    );
                }
            )
            .toCompletableFuture();
    }

    /**
     * Build an {@link Entry} from a storage key by reading the file
     * content for the SHA-256 digest and the sidecar metadata.
     */
    private CompletableFuture<Entry> buildEntry(final Key key, final String relativeHref) {
        return this.storage.value(key).thenCompose(
            value -> new ContentDigest(value, Digests.SHA256).hex()
        ).thenCompose(
            hex -> PypiSidecar.read(this.storage, key).thenApply(
                optMeta -> new Entry(
                    new KeyLastPart(key).get(),
                    relativeHref,
                    hex,
                    optMeta
                )
            )
        ).toCompletableFuture();
    }

    /**
     * Combine a list of entry futures into a single future yielding
     * an ordered list of entries. Preserves input order.
     */
    private static CompletableFuture<List<Entry>> allOf(
        final List<CompletableFuture<Entry>> futures
    ) {
        return CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        ).thenApply(ignored -> {
            final List<Entry> out = new ArrayList<>(futures.size());
            for (final CompletableFuture<Entry> f : futures) {
                out.add(f.join());
            }
            return out;
        });
    }

    /**
     * Render the PEP 503 HTML index for a package from collected entries.
     */
    private static String renderHtml(final List<Entry> entries) {
        final StringBuilder body = new StringBuilder();
        for (final Entry entry : entries) {
            final String attrs = entry.meta
                .map(IndexGenerator::buildHtmlAttributes).orElse("");
            body.append(String.format(
                "<a href=\"%s#sha256=%s\"%s>%s</a><br/>",
                entry.relativeHref, entry.sha256, attrs, entry.filename
            ));
        }
        return String.format(
            "<!DOCTYPE html>\n<html>\n  <body>\n%s\n</body>\n</html>",
            body.toString()
        );
    }

    /**
     * Render the PEP 691 JSON index for a package from collected entries.
     */
    private static String renderJson(final String packageName, final List<Entry> entries) {
        final List<SimpleJsonRenderer.FileEntry> files = new ArrayList<>(entries.size());
        for (final Entry entry : entries) {
            // PEP 691: "It is RECOMMENDED that the value of url be a
            // relative URL". Using the same relative href as the HTML
            // anchor avoids the protocol-relative URL bug where a
            // prefix of "/" + key "hello/..." produced "//hello/..."
            // which pip resolved as http://hello:80/... (hostname).
            files.add(new SimpleJsonRenderer.FileEntry(
                entry.filename,
                entry.relativeHref,
                entry.sha256,
                entry.meta.map(PypiSidecar.Meta::requiresPython).orElse(null),
                entry.meta.map(PypiSidecar.Meta::uploadTime).orElse(null),
                entry.meta.map(PypiSidecar.Meta::yanked).orElse(false),
                entry.meta.flatMap(PypiSidecar.Meta::yankedReason),
                entry.meta.flatMap(PypiSidecar.Meta::distInfoMetadata)
            ));
        }
        return SimpleJsonRenderer.render(packageName, files);
    }

    /**
     * Generate repository-level index listing all packages, in BOTH
     * HTML (PEP 503) and JSON (PEP 691) variants side-by-side.
     *
     * @return Completion future that resolves when both files are saved
     */
    public CompletableFuture<Void> generateRepoIndex() {
        return RxFuture.single(this.storage.list(this.packageKey))
            .map(allKeys -> {
                // Extract unique package names from all keys
                // Keys look like: pypi/hello/0.1.0/hello-0.1.0.whl
                // We want just: hello
                final String prefix = this.packageKey.string().isEmpty()
                    ? "" : this.packageKey.string() + "/";
                return allKeys.stream()
                    .map(key -> {
                        final String keyStr = key.string();
                        if (keyStr.startsWith(prefix)) {
                            final String relative = keyStr.substring(prefix.length());
                            final int slashIndex = relative.indexOf('/');
                            if (slashIndex > 0) {
                                return relative.substring(0, slashIndex);
                            }
                        }
                        return null;
                    })
                    .filter(packageName -> packageName != null
                        && !INDEX_HTML.equals(packageName)
                        && !packageName.startsWith(".")
                        && packageName.matches("[A-Za-z0-9._-]+"))
                    .distinct()
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            })
            .to(SingleInterop.get())
            .thenCompose(
                packageNames -> {
                    final String html = renderRepoHtml(packageNames);
                    final String json = renderRepoJson(packageNames);
                    final Key htmlKey = new Key.From(PYPI_METADATA, SIMPLE_HTML);
                    final Key jsonKey = new Key.From(PYPI_METADATA, SIMPLE_JSON);
                    return CompletableFuture.allOf(
                        this.storage.save(
                            htmlKey,
                            new Content.From(html.getBytes(StandardCharsets.UTF_8))
                        ).toCompletableFuture(),
                        this.storage.save(
                            jsonKey,
                            new Content.From(json.getBytes(StandardCharsets.UTF_8))
                        ).toCompletableFuture()
                    );
                }
            )
            .toCompletableFuture();
    }

    /**
     * Render the repo-level PEP 503 HTML index from a sorted package list.
     */
    private static String renderRepoHtml(final List<String> packageNames) {
        final StringBuilder body = new StringBuilder();
        for (final String name : packageNames) {
            body.append(String.format("<a href=\"%s/\">%s</a><br/>", name, name));
        }
        return String.format(
            "<!DOCTYPE html>\n<html>\n  <body>\n%s\n</body>\n</html>",
            body.toString()
        );
    }

    /**
     * Render the repo-level PEP 691 JSON index from a sorted package list.
     */
    private static String renderRepoJson(final List<String> packageNames) {
        final javax.json.JsonArrayBuilder projects = javax.json.Json.createArrayBuilder();
        for (final String name : packageNames) {
            projects.add(javax.json.Json.createObjectBuilder().add("name", name));
        }
        return javax.json.Json.createObjectBuilder()
            .add("meta", javax.json.Json.createObjectBuilder().add("api-version", "1.1"))
            .add("projects", projects)
            .build()
            .toString();
    }

    /**
     * Builds HTML data-attribute string from sidecar metadata for PEP 503/592/658 compliance.
     *
     * @param meta Sidecar metadata
     * @return Space-prefixed attribute string, or empty string if no attributes apply
     */
    private static String buildHtmlAttributes(final PypiSidecar.Meta meta) {
        final StringBuilder attrs = new StringBuilder();
        if (meta.requiresPython() != null && !meta.requiresPython().isEmpty()) {
            attrs.append(String.format(
                " data-requires-python=\"%s\"",
                meta.requiresPython().replace(">", "&gt;").replace("<", "&lt;")
            ));
        }
        if (meta.yanked()) {
            final String reason = meta.yankedReason().orElse("");
            attrs.append(String.format(" data-yanked=\"%s\"", reason));
        }
        if (meta.distInfoMetadata().isPresent()) {
            attrs.append(String.format(
                " data-dist-info-metadata=\"sha256=%s\"",
                meta.distInfoMetadata().get()
            ));
        }
        return attrs.toString();
    }
}
