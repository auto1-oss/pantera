/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.http.cache;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.headers.Header;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Persists response metadata (headers, size, digests) alongside cached artifacts.
 * Used by proxy slices to serve cached content without revalidating via upstream HEAD calls.
 */
public final class CachedArtifactMetadataStore {

    /**
     * Metadata file suffix.
     */
    private static final String META_SUFFIX = ".artipie-meta.json";

    /**
     * Backing storage.
     */
    private final Storage storage;

    /**
     * New metadata store.
     *
     * @param storage Storage for metadata and checksum sidecars.
     */
    public CachedArtifactMetadataStore(final Storage storage) {
        this.storage = storage;
    }

    /**
     * Persist headers, size and computed digests for an artifact.
     * Also materialises checksum sidecar files for quick subsequent lookup.
     *
     * @param key Artifact key.
     * @param headers Upstream response headers.
     * @param digests Computed digests and size information.
     * @return Future that completes when metadata and checksum files are written.
     */
    public CompletableFuture<Headers> save(
        final Key key,
        final Headers headers,
        final ComputedDigests digests
    ) {
        final Headers normalized = ensureContentLength(headers, digests.size());
        final CompletableFuture<Void> meta = this.saveMetadataFile(key, normalized, digests);
        final List<CompletableFuture<Void>> checksumWrites = new ArrayList<>(4);
        digests.sha1().ifPresent(checksum ->
            checksumWrites.add(this.saveChecksum(key, ".sha1", checksum))
        );
        digests.sha256().ifPresent(checksum ->
            checksumWrites.add(this.saveChecksum(key, ".sha256", checksum))
        );
        digests.sha512().ifPresent(checksum ->
            checksumWrites.add(this.saveChecksum(key, ".sha512", checksum))
        );
        digests.md5().ifPresent(checksum ->
            checksumWrites.add(this.saveChecksum(key, ".md5", checksum))
        );
        checksumWrites.add(meta);
        return CompletableFuture
            .allOf(checksumWrites.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> normalized);
    }

    /**
     * Load metadata for cached artifact if present.
     *
     * @param key Artifact key.
     * @return Metadata optional.
     */
    public CompletableFuture<Optional<Metadata>> load(final Key key) {
        final Key meta = this.metaKey(key);
        return this.storage.exists(meta).thenCompose(
            exists -> {
                if (!exists) {
                    return CompletableFuture.completedFuture(Optional.empty());
                }
                return this.storage.value(meta).thenCompose(
                    content -> content.asStringFuture()
                        .thenApply(str -> Optional.of(this.parseMetadata(str)))
                );
            }
        );
    }

    private CompletableFuture<Void> saveChecksum(
        final Key artifact,
        final String extension,
        final String value
    ) {
        final Key checksum = new Key.From(String.format("%s%s", artifact.string(), extension));
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        return this.storage.save(checksum, new Content.From(bytes));
    }

    private CompletableFuture<Void> saveMetadataFile(
        final Key key,
        final Headers headers,
        final ComputedDigests digests
    ) {
        final JsonObjectBuilder root = Json.createObjectBuilder()
            .add("size", digests.size());
        final JsonArrayBuilder hdrs = Json.createArrayBuilder();
        for (Header header : headers) {
            hdrs.add(
                Json.createObjectBuilder()
                    .add("name", header.getKey())
                    .add("value", header.getValue())
            );
        }
        root.add("headers", hdrs);
        final JsonObjectBuilder checksums = Json.createObjectBuilder();
        digests.sha1().ifPresent(val -> checksums.add("sha1", val));
        digests.sha256().ifPresent(val -> checksums.add("sha256", val));
        digests.sha512().ifPresent(val -> checksums.add("sha512", val));
        digests.md5().ifPresent(val -> checksums.add("md5", val));
        root.add("digests", checksums.build());
        final byte[] bytes = root.build().toString().getBytes(StandardCharsets.UTF_8);
        return this.storage.save(this.metaKey(key), new Content.From(bytes));
    }

    private Metadata parseMetadata(final String raw) {
        try (JsonReader reader = Json.createReader(new StringReader(raw))) {
            final JsonObject json = reader.readObject();
            final long size = json.getJsonNumber("size").longValue();
            final JsonArray hdrs = json.getJsonArray("headers");
            final List<Header> headers = new ArrayList<>(hdrs.size());
            for (int idx = 0; idx < hdrs.size(); idx++) {
                final JsonObject item = hdrs.getJsonObject(idx);
                headers.add(new Header(item.getString("name"), item.getString("value")));
            }
            final JsonObject digests = json.getJsonObject("digests");
            final Map<String, String> map = new HashMap<>();
            if (digests.containsKey("sha1")) {
                map.put("sha1", digests.getString("sha1"));
            }
            if (digests.containsKey("sha256")) {
                map.put("sha256", digests.getString("sha256"));
            }
            if (digests.containsKey("sha512")) {
                map.put("sha512", digests.getString("sha512"));
            }
            if (digests.containsKey("md5")) {
                map.put("md5", digests.getString("md5"));
            }
            return new Metadata(
                new Headers(new ArrayList<>(headers)),
                new ComputedDigests(size, map)
            );
        }
    }

    private Key metaKey(final Key key) {
        return new Key.From(String.format("%s%s", key.string(), CachedArtifactMetadataStore.META_SUFFIX));
    }

    private static Headers ensureContentLength(final Headers headers, final long size) {
        final Headers normalized = headers.copy();
        final boolean present = normalized.values("Content-Length").stream()
            .findFirst()
            .isPresent();
        if (!present) {
            normalized.add("Content-Length", String.valueOf(size));
        }
        return normalized;
    }

    /**
     * Computed artifact digests and size.
     */
    public static final class ComputedDigests {

        /**
         * Artifact size.
         */
        private final long size;

        /**
         * Digests map.
         */
        private final Map<String, String> digests;

        /**
         * New computed digests.
         *
         * @param size Artifact size.
         * @param digests Digests map.
         */
        public ComputedDigests(final long size, final Map<String, String> digests) {
            this.size = size;
            this.digests = new HashMap<>(digests);
        }

        public long size() {
            return this.size;
        }

        public Optional<String> sha1() {
            return Optional.ofNullable(this.digests.get("sha1"));
        }

        public Optional<String> sha256() {
            return Optional.ofNullable(this.digests.get("sha256"));
        }

        public Optional<String> sha512() {
            return Optional.ofNullable(this.digests.get("sha512"));
        }

        public Optional<String> md5() {
            return Optional.ofNullable(this.digests.get("md5"));
        }
    }

    /**
     * Cached metadata.
     */
    public static final class Metadata {

        /**
         * Response headers.
         */
        private final Headers headers;

        /**
         * Digests and size.
         */
        private final ComputedDigests digests;

        Metadata(final Headers headers, final ComputedDigests digests) {
            this.headers = headers;
            this.digests = digests;
        }

        public Headers headers() {
            return this.headers;
        }

        public ComputedDigests digests() {
            return this.digests;
        }
    }
}
