/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
            // Extract version from JSON
            final String version = this.extractVersion();
            if (version == null) {
                return CompletableFuture.failedFuture(
                    new IllegalArgumentException("No version found in package JSON")
                );
            }
            
            // Extract version-specific metadata from the "versions" field
            final JsonObject versionData;
            if (this.json.containsKey("versions") 
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
                .toCompletableFuture();
        }
        
        /**
         * Extract version from JSON.
         * Tries multiple locations where version might be specified.
         * 
         * @return Version string or null if not found
         */
        private String extractVersion() {
            // Try direct version field
            if (this.json.containsKey("version")) {
                return this.json.getString("version");
            }
            
            // Try dist-tags/latest
            if (this.json.containsKey("dist-tags")) {
                final JsonObject distTags = this.json.getJsonObject("dist-tags");
                if (distTags.containsKey("latest")) {
                    return distTags.getString("latest");
                }
            }
            
            // Try first version in versions object
            if (this.json.containsKey("versions")) {
                final JsonObject versions = this.json.getJsonObject("versions");
                if (!versions.isEmpty()) {
                    final String firstKey = versions.keySet().iterator().next();
                    return firstKey;
                }
            }
            
            return null;
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
