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
package com.auto1.pantera.npm.proxy.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.cooldown.metadata.CooldownMetadataService;
import com.auto1.pantera.cooldown.metadata.MetadataFilter;
import com.auto1.pantera.cooldown.metadata.MetadataParser;
import com.auto1.pantera.cooldown.metadata.MetadataRewriter;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.ClientBaseUrl;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.npm.misc.PackumentJson;
import com.auto1.pantera.npm.proxy.NpmProxy;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Test;

/**
 * Packument and version-manifest serves of {@link DownloadPackageSlice}, with
 * and without cooldown: nested tarball URLs, the filtered ETag, unpublished
 * packuments and documents too deep for a tree.
 *
 * @since 2.2.11
 */
final class DownloadPackageSliceServeTest {

    /**
     * Client-facing base stamped on every request.
     */
    private static final String PREFIX = "https://h/api/npm/npm_proxy";

    /**
     * Abbreviated packument media type.
     */
    private static final String ABBREVIATED = "application/vnd.npm.install-v1+json";

    /**
     * A pg-like packument as ingest stores it: every {@code tarball}
     * host-stripped, also the per-platform ones under {@code dist.bin}.
     */
    private static final String PG = "{\"name\":\"pg\",\"dist-tags\":{\"latest\":\"8.0.0\"},"
        + "\"versions\":{\"8.0.0\":{\"name\":\"pg\",\"version\":\"8.0.0\","
        + "\"dist\":{\"tarball\":\"/pg/-/pg-8.0.0.tgz\",\"bin\":{"
        + "\"linux-x64\":{\"tarball\":\"/pg/-/pg-linux.tgz\"},"
        + "\"darwin-arm64\":{\"tarball\":\"/pg/-/pg-darwin.tgz\"}}}},"
        + "\"7.0.0\":{\"dist\":{\"tarball\":\"/pg/-/pg-7.0.0.tgz\"}}},"
        + "\"time\":{\"7.0.0\":\"2019-01-01T00:00:00.000Z\",\"8.0.0\":\"2020-01-01T00:00:00.000Z\"}}";

    @Test
    void rawAndFilteredServeAgreeOnNestedTarballs() throws Exception {
        final JsonNode raw = DownloadPackageSliceServeTest.json(
            DownloadPackageSliceServeTest.ok(DownloadPackageSliceServeTest.store("pg", PG), null, "/pg", false)
        );
        final JsonNode filtered = DownloadPackageSliceServeTest.json(
            DownloadPackageSliceServeTest.ok(
                DownloadPackageSliceServeTest.store("pg", PG), new SharedVerdict(Set.of()), "/pg", false
            )
        );
        MatcherAssert.assertThat("same document", filtered, new IsEqual<>(raw));
        MatcherAssert.assertThat(
            "nested tarball re-rooted",
            raw.at("/versions/8.0.0/dist/bin/linux-x64/tarball").asText(),
            new IsEqual<>(PREFIX + "/pg/-/pg-linux.tgz")
        );
    }

    @Test
    void filteredEtagDoesNotDependOnWhichRequestKindFilledTheVerdict() throws Exception {
        for (final Set<String> blocked : java.util.List.of(Set.<String>of(), Set.of("8.0.0"))) {
            final Storage storage = DownloadPackageSliceServeTest.store("pg", PG);
            final SharedVerdict fullFirst = new SharedVerdict(blocked);
            final String full = DownloadPackageSliceServeTest.etag(storage, fullFirst, false);
            final String abbrevAfterFull = DownloadPackageSliceServeTest.etag(storage, fullFirst, true);
            final SharedVerdict abbrevFirst = new SharedVerdict(blocked);
            final String abbrev = DownloadPackageSliceServeTest.etag(storage, abbrevFirst, true);
            final String fullAfterAbbrev = DownloadPackageSliceServeTest.etag(storage, abbrevFirst, false);
            MatcherAssert.assertThat(
                "abbreviated ETag, blocked " + blocked, abbrevAfterFull, new IsEqual<>(abbrev)
            );
            MatcherAssert.assertThat(
                "full ETag, blocked " + blocked, fullAfterAbbrev, new IsEqual<>(full)
            );
        }
        final Storage storage = DownloadPackageSliceServeTest.store("pg", PG);
        MatcherAssert.assertThat(
            "a different verdict changes the ETag",
            DownloadPackageSliceServeTest.etag(storage, new SharedVerdict(Set.of("8.0.0")), false),
            new IsNot<>(
                new IsEqual<>(DownloadPackageSliceServeTest.etag(storage, new SharedVerdict(Set.of()), false))
            )
        );
    }

    @Test
    void unpublishedPackumentKeepsTimeUnpublishedUnderCooldown() throws Exception {
        final String unpublished = "{\"time\":\"2024-02-01T00:00:00.000Z\",\"versions\":[\"1.0.0\"]}";
        final String doc = "{\"_id\":\"gone\",\"name\":\"gone\",\"time\":{\"created\":\"2020-01-01T00:00:00.000Z\","
            + "\"modified\":\"2024-02-01T00:00:00.000Z\",\"unpublished\":" + unpublished + "}}";
        MatcherAssert.assertThat(
            DownloadPackageSliceServeTest.json(
                DownloadPackageSliceServeTest.ok(
                    DownloadPackageSliceServeTest.store("gone", doc), new SharedVerdict(Set.of()), "/gone", false
                )
            ).at("/time/unpublished"),
            new IsEqual<>(PackumentJson.MAPPER.readTree(unpublished))
        );
    }

    @Test
    void servesAVersionTooDeepForATree() throws Exception {
        final int levels = 100_000;
        final String doc = "{\"name\":\"deep\",\"dist-tags\":{\"latest\":\"1.0.0\"},\"versions\":{"
            + "\"1.0.0\":{\"name\":\"deep\",\"deep\":" + "[".repeat(levels) + "]".repeat(levels)
            + ",\"dist\":{\"tarball\":\"/deep/-/deep-1.0.0.tgz\"}}},"
            + "\"time\":{\"1.0.0\":\"2020-01-01T00:00:00.000Z\"}}";
        for (final SharedVerdict cooldown : new SharedVerdict[] {null, new SharedVerdict(Set.of())}) {
            for (final String path : new String[] {"/deep/1.0.0", "/deep/latest"}) {
                final byte[] body = DownloadPackageSliceServeTest.ok(
                    DownloadPackageSliceServeTest.storeFull("deep", doc), cooldown, path, false
                ).body().asBytesFuture().join();
                MatcherAssert.assertThat(
                    path + " is one JSON value, cooldown " + (cooldown != null),
                    DownloadPackageSliceServeTest.rootValues(body),
                    new IsEqual<>(1)
                );
                MatcherAssert.assertThat(
                    path + " tarball, cooldown " + (cooldown != null),
                    new String(body, StandardCharsets.UTF_8)
                        .endsWith("\"dist\":{\"tarball\":\"" + PREFIX + "/deep/-/deep-1.0.0.tgz\"}}"),
                    new IsEqual<>(true)
                );
            }
        }
    }

    private static String etag(final Storage storage, final SharedVerdict cooldown, final boolean abbreviated)
        throws Exception {
        final Response response = DownloadPackageSliceServeTest.ok(storage, cooldown, "/pg", abbreviated);
        response.body().asBytesFuture().join();
        return response.headers().values("ETag").get(0);
    }

    private static Response ok(
        final Storage storage, final SharedVerdict cooldown, final String path, final boolean abbreviated
    ) throws Exception {
        Headers headers = Headers.from(ClientBaseUrl.HEADER, PREFIX);
        if (abbreviated) {
            headers = headers.copy().add("Accept", DownloadPackageSliceServeTest.ABBREVIATED);
        }
        final DownloadPackageSlice slice;
        final NpmProxy npm = new NpmProxy(storage, new SliceSimple(ResponseBuilder.notFound().build()));
        if (cooldown == null) {
            slice = new DownloadPackageSlice(npm, new PackagePath(""), Optional.empty());
        } else {
            slice = new DownloadPackageSlice(
                npm, new PackagePath(""), Optional.empty(), cooldown, "npm", "npm-proxy"
            );
        }
        final Response response = slice.response(
            new RequestLine(RqMethod.GET, path), headers, Content.EMPTY
        ).get();
        MatcherAssert.assertThat("status of " + path, response.status(), new IsEqual<>(RsStatus.OK));
        return response;
    }

    private static JsonNode json(final Response response) throws Exception {
        return PackumentJson.MAPPER.readTree(response.body().asBytesFuture().join());
    }

    /**
     * Store a packument with an abbreviated document whose members are in
     * the order the abbreviated document has them, and both hashes.
     */
    private static Storage store(final String pkg, final String doc) throws Exception {
        final Storage storage = new InMemoryStorage();
        final JsonNode tree = PackumentJson.MAPPER.readTree(doc);
        final com.fasterxml.jackson.databind.node.ObjectNode abbrev = PackumentJson.MAPPER.createObjectNode();
        abbrev.set("name", tree.get("name"));
        abbrev.put("modified", "2020-01-01T00:00:00.000Z");
        if (tree.has("time")) {
            abbrev.set("time", tree.get("time"));
        }
        if (tree.has("dist-tags")) {
            abbrev.set("dist-tags", tree.get("dist-tags"));
        }
        if (tree.has("versions")) {
            abbrev.set("versions", tree.get("versions"));
        }
        final byte[] full = doc.getBytes(StandardCharsets.UTF_8);
        final byte[] small = PackumentJson.MAPPER.writeValueAsBytes(abbrev);
        storage.save(new Key.From(pkg, "meta.json"), new Content.From(full)).join();
        storage.save(new Key.From(pkg, "meta.abbreviated.json"), new Content.From(small)).join();
        storage.save(
            new Key.From(pkg, "meta.meta"),
            new Content.From(
                Json.createObjectBuilder()
                    .add("last-modified", "2020-05-13T16:30:30+01:00")
                    .add("last-refreshed", java.time.OffsetDateTime.now().toString())
                    .add("content-hash", DownloadPackageSliceServeTest.sha(full))
                    .add("abbreviated-hash", DownloadPackageSliceServeTest.sha(small))
                    .build().toString().getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        return storage;
    }

    /**
     * Store a packument without an abbreviated document; the document is
     * never parsed into a tree.
     */
    private static Storage storeFull(final String pkg, final String doc) {
        final Storage storage = new InMemoryStorage();
        storage.save(new Key.From(pkg, "meta.json"), new Content.From(doc.getBytes(StandardCharsets.UTF_8)))
            .join();
        storage.save(
            new Key.From(pkg, "meta.meta"),
            new Content.From(
                Json.createObjectBuilder()
                    .add("last-modified", "2020-05-13T16:30:30+01:00")
                    .add("last-refreshed", java.time.OffsetDateTime.now().toString())
                    .build().toString().getBytes(StandardCharsets.UTF_8)
            )
        ).join();
        return storage;
    }

    private static String sha(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    /**
     * Root values of a body, counted token by token so any depth is fine.
     */
    private static int rootValues(final byte[] body) throws Exception {
        int roots = 0;
        try (JsonParser parser = PackumentJson.FACTORY.createParser(body)) {
            JsonToken token = parser.nextToken();
            while (token != null) {
                if (parser.getParsingContext().inRoot()) {
                    ++roots;
                }
                token = parser.nextToken();
            }
        }
        return roots;
    }

    /**
     * Cooldown service whose verdict cache is shared by every request kind,
     * like the filtered-metadata envelope: the first skeleton evaluated for a
     * package is filtered with the real npm rules and reused for the rest.
     */
    private static final class SharedVerdict implements CooldownMetadataService {

        /**
         * Versions to withhold.
         */
        private final Set<String> blocked;

        /**
         * Filtered skeleton per package.
         */
        private final Map<String, byte[]> cache;

        SharedVerdict(final Set<String> blocked) {
            this.blocked = blocked;
            this.cache = new ConcurrentHashMap<>();
        }

        @Override
        public <T> CompletableFuture<byte[]> filterMetadata(
            final String repoType,
            final String repoName,
            final String packageName,
            final byte[] rawMetadata,
            final MetadataParser<T> parser,
            final MetadataFilter<T> filter,
            final MetadataRewriter<T> rewriter
        ) {
            return CompletableFuture.completedFuture(
                this.cache.computeIfAbsent(
                    packageName,
                    name -> {
                        try {
                            return rewriter.rewrite(filter.filter(parser.parse(rawMetadata), this.blocked));
                        } catch (final Exception ex) {
                            throw new CompletionException(ex);
                        }
                    }
                )
            );
        }

        @Override
        public void invalidate(final String repoType, final String repoName, final String packageName) {
            this.cache.remove(packageName);
        }

        @Override
        public void invalidateAll(final String repoType, final String repoName) {
            this.cache.clear();
        }

        @Override
        public void clearAll() {
            this.cache.clear();
        }

        @Override
        public String stats() {
            return "SharedVerdict";
        }
    }
}
