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
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.Single;

import java.nio.charset.StandardCharsets;
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
     * Storage.
     */
    private final Storage storage;

    /**
     * Package key (e.g., "pypi-repo/hello").
     */
    private final Key packageKey;

    /**
     * Base URL prefix for links.
     */
    private final String prefix;

    /**
     * Ctor.
     * 
     * @param storage Storage
     * @param packageKey Package key
     * @param prefix URL prefix
     */
    public IndexGenerator(final Storage storage, final Key packageKey, final String prefix) {
        this.storage = storage;
        this.packageKey = packageKey;
        this.prefix = prefix;
    }

    /**
     * Generate and save index.html for the package.
     * 
     * @return Completion future
     */
    public CompletableFuture<Void> generate() {
        // Use non-blocking RxFuture.single instead of blocking SingleInterop.fromFuture
        return RxFuture.single(this.storage.list(this.packageKey))
            .flatMapPublisher(Flowable::fromIterable)
            // Use concatMapSingle to preserve ordering (flatMapSingle doesn't preserve order)
            .concatMapSingle(
                key -> RxFuture.single(
                    // Try to list this key as a directory (version folder)
                    this.storage.list(key).thenCompose(
                        subKeys -> {
                            if (subKeys.isEmpty()) {
                                // It's a file, not a directory - process it directly
                                return this.storage.value(key).thenCompose(
                                    value -> new ContentDigest(value, Digests.SHA256).hex()
                                ).thenApply(
                                    hex -> String.format(
                                        "<a href=\"%s#sha256=%s\">%s</a><br/>",
                                        String.format("%s/%s", this.prefix, key.string()),
                                        hex,
                                        new KeyLastPart(key).get()
                                    )
                                );
                            } else {
                                // It's a directory - process all files in it
                                // Use concatMapSingle to preserve ordering
                                return Flowable.fromIterable(subKeys)
                                    .concatMapSingle(
                                        subKey -> RxFuture.single(
                                            this.storage.value(subKey).thenCompose(
                                                value -> new ContentDigest(value, Digests.SHA256).hex()
                                            ).thenApply(
                                                hex -> {
                                                    // Generate relative URL from package index to file
                                                    // e.g., from /pypi/hello/ to /pypi/hello/1.0.0/hello-1.0.0.whl
                                                    final String filename = new KeyLastPart(subKey).get();
                                                    final String versionPath = new KeyLastPart(
                                                        new Key.From(subKey.parent().get())
                                                    ).get();
                                                    return String.format(
                                                        "<a href=\"%s/%s#sha256=%s\">%s</a><br/>",
                                                        versionPath,
                                                        filename,
                                                        hex,
                                                        filename
                                                    );
                                                }
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
                content -> String.format(
                    "<!DOCTYPE html>\n<html>\n  <body>\n%s\n</body>\n</html>",
                    content.toString()
                )
            )
            .to(SingleInterop.get())
            .thenCompose(
                html -> {
                    // Save to .pypi/<package_name>/<package_name>.html
                    final String packageName = new KeyLastPart(this.packageKey).get();
                    final Key indexKey = new Key.From(
                        PYPI_METADATA,
                        packageName,
                        packageName + ".html"
                    );
                    return this.storage.save(
                        indexKey,
                        new Content.From(html.getBytes(StandardCharsets.UTF_8))
                    );
                }
            )
            .toCompletableFuture();
    }

    /**
     * Generate repository-level index.html listing all packages.
     * 
     * @return Completion future
     */
    public CompletableFuture<Void> generateRepoIndex() {
        // Use non-blocking RxFuture.single instead of blocking SingleInterop.fromFuture
        return RxFuture.single(this.storage.list(this.packageKey))
            .map(allKeys -> {
                // Extract unique package names from all keys
                // Keys look like: pypi/hello/0.1.0/hello-0.1.0.whl
                // We want just: hello
                final String prefix = this.packageKey.string().isEmpty() ? "" : this.packageKey.string() + "/";
                return allKeys.stream()
                    .map(key -> {
                        String keyStr = key.string();
                        if (keyStr.startsWith(prefix)) {
                            String relative = keyStr.substring(prefix.length());
                            // Get the first path segment (package name)
                            int slashIndex = relative.indexOf('/');
                            if (slashIndex > 0) {
                                return relative.substring(0, slashIndex);
                            }
                        }
                        return null;
                    })
                    .filter(packageName -> packageName != null && 
                            !packageName.equals(INDEX_HTML) &&
                            packageName.matches("[A-Za-z0-9._-]+"))
                    .distinct()
                    .sorted()
                    .map(packageName -> String.format(
                        "<a href=\"%s/\">%s</a><br/>",
                        packageName,
                        packageName
                    ))
                    .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                    .toString();
            })
            .map(content -> String.format(
                "<!DOCTYPE html>\n<html>\n  <body>\n%s\n</body>\n</html>",
                content
            ))
            .to(SingleInterop.get())
            .thenCompose(
                html -> {
                    // Save to .pypi/simple.html for repo-level index
                    final Key indexKey = new Key.From(PYPI_METADATA, SIMPLE_HTML);
                    return this.storage.save(
                        indexKey,
                        new Content.From(html.getBytes(StandardCharsets.UTF_8))
                    );
                }
            )
            .toCompletableFuture();
    }
}
