/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.npm.proxy.model;

import io.vertx.core.json.JsonObject;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

/**
 * NPM Package.
 * @since 0.1
 */
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public final class NpmPackage {
    /**
     * Package name.
     */
    private final String name;

    /**
     * JSON data.
     */
    private final String content;

    /**
     * Package metadata.
     */
    private final Metadata metadata;

    /**
     * Ctor.
     * @param name Package name
     * @param content JSON data
     * @param modified Last modified date
     * @param refreshed Last update date
     */
    public NpmPackage(final String name,
        final String content,
        final String modified,
        final OffsetDateTime refreshed) {
        this(name, content, new Metadata(modified, refreshed));
    }

    /**
     * Ctor with upstream ETag.
     * @param name Package name
     * @param content JSON data
     * @param modified Last modified date
     * @param refreshed Last update date
     * @param upstreamEtag Upstream ETag for conditional requests
     */
    public NpmPackage(final String name,
        final String content,
        final String modified,
        final OffsetDateTime refreshed,
        final String upstreamEtag) {
        this(name, content, new Metadata(modified, refreshed, null, null, upstreamEtag));
    }

    /**
     * Ctor.
     * @param name Package name
     * @param content JSON data
     * @param metadata Package metadata
     */
    public NpmPackage(final String name, final String content, final Metadata metadata) {
        this.name = name;
        this.content = content;
        this.metadata = metadata;
    }

    /**
     * Get package name.
     * @return Package name
     */
    public String name() {
        return this.name;
    }

    /**
     * Get package JSON.
     * @return Package JSON
     */
    public String content() {
        return this.content;
    }

    /**
     * Get package metadata.
     * @return Package metadata
     */
    public Metadata meta() {
        return this.metadata;
    }

    /**
     * NPM Package metadata.
     * @since 0.2
     */
    @SuppressWarnings("PMD.AvoidDuplicateLiterals")
    public static class Metadata {
        /**
         * Last modified date.
         */
        private final String modified;

        /**
         * Last refreshed date.
         */
        private final OffsetDateTime refreshed;

        /**
         * Pre-computed SHA-256 hash of full content (null if not available).
         */
        private final String contentHash;

        /**
         * Pre-computed SHA-256 hash of abbreviated content (null if not available).
         */
        private final String abbreviatedHash;

        /**
         * Upstream ETag for conditional requests (null if not available).
         */
        private final String upstreamEtag;

        /**
         * Ctor.
         * @param json JSON representation of metadata
         */
        public Metadata(final JsonObject json) {
            this(
                json.getString("last-modified"),
                OffsetDateTime.parse(
                    json.getString("last-refreshed"),
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME
                ),
                json.getString("content-hash", null),
                json.getString("abbreviated-hash", null),
                json.getString("upstream-etag", null)
            );
        }

        /**
         * Ctor.
         * @param modified Last modified date
         * @param refreshed Last refreshed date
         */
        public Metadata(final String modified, final OffsetDateTime refreshed) {
            this(modified, refreshed, null, null, null);
        }

        /**
         * Ctor with pre-computed hashes.
         * @param modified Last modified date
         * @param refreshed Last refreshed date
         * @param contentHash SHA-256 of full content (nullable)
         * @param abbreviatedHash SHA-256 of abbreviated content (nullable)
         */
        public Metadata(final String modified, final OffsetDateTime refreshed,
            final String contentHash, final String abbreviatedHash) {
            this(modified, refreshed, contentHash, abbreviatedHash, null);
        }

        /**
         * Full ctor with pre-computed hashes and upstream ETag.
         * @param modified Last modified date
         * @param refreshed Last refreshed date
         * @param contentHash SHA-256 of full content (nullable)
         * @param abbreviatedHash SHA-256 of abbreviated content (nullable)
         * @param upstreamEtag Upstream ETag for conditional requests (nullable)
         */
        public Metadata(final String modified, final OffsetDateTime refreshed,
            final String contentHash, final String abbreviatedHash,
            final String upstreamEtag) {
            this.modified = modified;
            this.refreshed = refreshed;
            this.contentHash = contentHash;
            this.abbreviatedHash = abbreviatedHash;
            this.upstreamEtag = upstreamEtag;
        }

        /**
         * Get last modified date.
         * @return Last modified date
         */
        public String lastModified() {
            return this.modified;
        }

        /**
         * Get last refreshed date.
         * @return The date of last attempt to refresh metadata
         */
        public OffsetDateTime lastRefreshed() {
            return this.refreshed;
        }

        /**
         * Get pre-computed content hash if available.
         * @return Optional SHA-256 hex of full content
         */
        public java.util.Optional<String> contentHash() {
            return java.util.Optional.ofNullable(this.contentHash);
        }

        /**
         * Get pre-computed abbreviated content hash if available.
         * @return Optional SHA-256 hex of abbreviated content
         */
        public java.util.Optional<String> abbreviatedHash() {
            return java.util.Optional.ofNullable(this.abbreviatedHash);
        }

        /**
         * Get upstream ETag for conditional requests.
         * @return Optional upstream ETag
         */
        public java.util.Optional<String> upstreamEtag() {
            return java.util.Optional.ofNullable(this.upstreamEtag);
        }

        /**
         * Get JSON representation of metadata.
         * @return JSON representation
         */
        public JsonObject json() {
            final JsonObject json = new JsonObject();
            json.put("last-modified", this.modified);
            json.put(
                "last-refreshed",
                DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(this.refreshed)
            );
            if (this.contentHash != null) {
                json.put("content-hash", this.contentHash);
            }
            if (this.abbreviatedHash != null) {
                json.put("abbreviated-hash", this.abbreviatedHash);
            }
            if (this.upstreamEtag != null) {
                json.put("upstream-etag", this.upstreamEtag);
            }
            return json;
        }
    }
}
