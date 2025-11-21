/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */

package com.artipie.pypi.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.ContentDigest;
import com.artipie.asto.ext.Digests;
import com.artipie.asto.ext.KeyLastPart;
import com.artipie.http.Headers;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.Response;
import com.artipie.http.Slice;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RequestLinePrefix;
import com.artipie.pypi.NormalizedProjectName;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import io.reactivex.Flowable;
import io.reactivex.Single;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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
     * Simple index filename for repo-level index.
     */
    private static final String SIMPLE_HTML = "simple.html";

    /**
     * Artipie artifacts storage.
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
        final Key indexKey;
        final Key listKey;
        final String packageName;
        if (repoIndex) {
            indexKey = new Key.From(PYPI_METADATA, SIMPLE_HTML);
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
            indexKey = new Key.From(PYPI_METADATA, packageName, packageName + ".html");
        }

        return this.storage.exists(indexKey).thenCompose(
            exists -> {
                if (exists) {
                    return this.storage.value(indexKey).thenApply(
                        content -> ResponseBuilder.ok()
                            .header("Content-Type", "text/html; charset=utf-8")
                            .body(content)
                            .build()
                    );
                }
                return this.generateDynamicIndex(listKey, prefix);
            }
        ).toCompletableFuture();
    }

    /**
     * Generate index dynamically from storage.
     * 
     * @param list List key to scan
     * @param prefix URL prefix
     * @return Response future
     */
    private CompletableFuture<Response> generateDynamicIndex(final Key list, final String prefix) {
        return SingleInterop.fromFuture(this.storage.list(list))
            .flatMap(keys -> {
                // Return 404 if package doesn't exist (empty directory)
                if (keys.isEmpty()) {
                    return Single.just(ResponseBuilder.notFound().build());
                }
                // Process all keys and generate index
                return Flowable.fromIterable(keys)
                    .flatMapSingle(
                        key -> Single.fromFuture(
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
                                                String.format("%s/%s", prefix, key.string()),
                                                hex,
                                                new KeyLastPart(key).get()
                                            )
                                        );
                                    } else {
                                        // It's a directory - process all files in it
                                        return Flowable.fromIterable(subKeys)
                                            .flatMapSingle(
                                                subKey -> Single.fromFuture(
                                                    this.storage.value(subKey).thenCompose(
                                                        value -> new ContentDigest(value, Digests.SHA256).hex()
                                                    ).thenApply(
                                                        hex -> String.format(
                                                            "<a href=\"%s#sha256=%s\">%s</a><br/>",
                                                            String.format("%s/%s", prefix, subKey.string()),
                                                            hex,
                                                            new KeyLastPart(subKey).get()
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
                        resp -> ResponseBuilder.ok()
                            .htmlBody(
                                String.format(
                                    "<!DOCTYPE html>\n<html>\n  </body>\n%s\n</body>\n</html>", resp.toString()
                                ), StandardCharsets.UTF_8)
                            .build()
                    );
            }).to(SingleInterop.get()).toCompletableFuture();
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
