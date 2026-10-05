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
package com.auto1.pantera.npm;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import org.apache.commons.codec.binary.Hex;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonPatchBuilder;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Updating `meta.json` file.
 * @since 0.9
 */
public interface MetaUpdate {
    /**
     * Update `meta.json` file by the specified prefix.
     * @param prefix The package prefix
     * @param storage Abstract storage
     * @return Completion or error signal.
     */
    CompletableFuture<Void> update(Key prefix, Storage storage);

    /**
     * Update `meta.json` by adding information from the uploaded json.
     * 
     * <p>Uses per-version file layout to eliminate lock contention:</p>
     * <ul>
     *   <li>Each version writes to .versions/VERSION.json</li>
     *   <li>No locking needed - different versions don't compete</li>
     *   <li>132 versions = 132 parallel writes (not serial!)</li>
     * </ul>
     * 
     * @since 0.9
     */
    class ByJson implements MetaUpdate {
        /**
         * The uploaded json.
         */
        private final JsonObject json;

        /**
         * Ctor.
         * @param json Uploaded json. Usually this file is generated when
         *  command `npm publish` is completed
         */
        public ByJson(final JsonObject json) {
            this.json = json;
        }

        @Override
        public CompletableFuture<Void> update(final Key prefix, final Storage storage) {
            // One shared extractor for every write of the publish; an
            // inconsistent payload (target version absent from `versions`)
            // is refused instead of being written under the wrong version
            final String version;
            try {
                version = new PublishedVersion(this.json).validated();
            } catch (final InvalidPublishException ex) {
                return CompletableFuture.failedFuture(ex);
            }

            // Extract version-specific metadata from the "versions" field
            final JsonObject versionData;
            if (this.json.get("versions") instanceof JsonObject
                && this.json.getJsonObject("versions").containsKey(version)) {
                versionData = this.json.getJsonObject("versions").getJsonObject(version);
            } else {
                // Fallback: use the entire JSON if it doesn't have versions structure
                versionData = this.json;
            }

            // Use per-version layout - no locking needed!
            // Each version writes to its own file
            final PerVersionLayout layout = new PerVersionLayout(storage);
            return layout.addVersion(prefix, version, versionData)
                .thenCompose(ignored -> layout.mergeDistTags(prefix, this.extractDistTags(version)))
                .toCompletableFuture();
        }

        /**
         * The version this update writes.
         *
         * @return Version string, or {@code null} when the json carries none
         */
        public String version() {
            return new PublishedVersion(this.json).value();
        }

        /**
         * Extract the dist-tags the npm CLI asked to be set as part of this
         * publish. A normal {@code npm publish} sends
         * {@code {"latest": "<version>"}}; {@code npm publish --tag beta}
         * sends only {@code {"beta": "<version>"}}. When the payload carries no
         * dist-tags at all (non-standard client), default to tagging the
         * published version as {@code latest} so the package remains
         * installable.
         *
         * @param version Version just published, used as the fallback target
         * @return Tag-&gt;version map to persist into the sidecar
         */
        private JsonObject extractDistTags(final String version) {
            if (this.json.containsKey("dist-tags")
                && !this.json.getJsonObject("dist-tags").isEmpty()) {
                return this.json.getJsonObject("dist-tags");
            }
            return Json.createObjectBuilder().add("latest", version).build();
        }
    }

    /**
     * Update `meta.json` by adding information from the package file
     * from uploaded archive.
     * @since 0.9
     */
    class ByTgz implements MetaUpdate {
        /**
         * Uploaded tgz archive.
         */
        private final TgzArchive tgz;

        /**
         * Ctor.
         * @param tgz Uploaded tgz file
         */
        public ByTgz(final TgzArchive tgz) {
            this.tgz = tgz;
        }

        @Override
        public CompletableFuture<Void> update(final Key prefix, final Storage storage) {
            final String version = "version";
            final JsonPatchBuilder patch = Json.createPatchBuilder();
            patch.add("/dist", Json.createObjectBuilder().build());
            return ByTgz.hash(this.tgz, Digests.SHA512, true)
                .thenAccept(sha -> patch.add("/dist/integrity", String.format("sha512-%s", sha)))
                .thenCombine(
                    ByTgz.hash(this.tgz, Digests.SHA1, false),
                    (nothing, sha) -> patch.add("/dist/shasum", sha)
                ).thenApply(
                    nothing -> {
                        final JsonObject pkg = this.tgz.packageJson();
                        final String name = pkg.getString("name");
                        final String vers = pkg.getString(version);
                        patch.add("/_id", String.format("%s@%s", name, vers));
                        patch.add(
                            "/dist/tarball",
                            String.format("%s/-/%s-%s.tgz", prefix.string(), name, vers)
                        );
                        return patch.build().apply(pkg);
                    }
                )
                .thenApply(
                    json -> {
                        final JsonObject base = new NpmPublishJsonToMetaSkelethon(json).skeleton();
                        final String vers = json.getString(version);
                        final JsonPatchBuilder upd = Json.createPatchBuilder();
                        upd.add("/dist-tags", Json.createObjectBuilder().build());
                        upd.add("/dist-tags/latest", vers);
                        upd.add(String.format("/versions/%s", vers), json);
                        return upd.build().apply(base);
                    }
                )
                .thenCompose(json -> new ByJson(json).update(prefix, storage))
                .toCompletableFuture();
        }

        /**
         * Obtains specified hash value for passed archive.
         * @param tgz Tgz archive
         * @param dgst Digest mode
         * @param encoded Is encoded64?
         * @return Hash value.
         */
        private static CompletionStage<String> hash(
            final TgzArchive tgz, final Digests dgst, final boolean encoded
        ) {
            return new ContentDigest(new Content.From(tgz.bytes()), dgst)
                .bytes()
                .thenApply(
                    bytes -> {
                        final String res;
                        if (encoded) {
                            res = new String(Base64.getEncoder().encode(bytes));
                        } else {
                            res = Hex.encodeHexString(bytes);
                        }
                        return res;
                    }
                );
        }
    }
}
