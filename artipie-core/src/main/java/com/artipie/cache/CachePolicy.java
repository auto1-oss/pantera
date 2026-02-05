/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.cache;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * Cache policy that determines if a path is artifact (immutable) or metadata (mutable).
 * <p>
 * Artifacts are cached forever, metadata has a TTL with background refresh.
 * </p>
 *
 * @since 1.0
 */
public final class CachePolicy {

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Metadata TTL.
     */
    private final Duration metadataTtl;

    /**
     * Stale serve duration (serve stale content if upstream is down).
     */
    private final Duration staleDuration;

    /**
     * Create cache policy with defaults.
     *
     * @param repoType Repository type (e.g., "maven", "npm", "go")
     */
    public CachePolicy(final String repoType) {
        this(repoType, Duration.ofMinutes(5), Duration.ofHours(1));
    }

    /**
     * Create cache policy with custom TTL.
     *
     * @param repoType Repository type
     * @param metadataTtl Metadata TTL
     * @param staleDuration How long to serve stale content
     */
    public CachePolicy(
        final String repoType,
        final Duration metadataTtl,
        final Duration staleDuration
    ) {
        this.repoType = repoType;
        this.metadataTtl = metadataTtl;
        this.staleDuration = staleDuration;
    }

    /**
     * Check if path is metadata (mutable, has TTL).
     *
     * @param path Request path
     * @return true if metadata, false if artifact
     */
    public boolean isMetadata(final String path) {
        return switch (this.repoType) {
            case "maven", "maven-proxy" -> this.isMavenMetadata(path);
            case "npm", "npm-proxy" -> this.isNpmMetadata(path);
            case "go", "go-proxy" -> this.isGoMetadata(path);
            case "pypi", "pypi-proxy" -> this.isPypiMetadata(path);
            case "docker", "docker-proxy" -> this.isDockerMetadata(path);
            case "gradle", "gradle-proxy" -> this.isGradleMetadata(path);
            case "php", "php-proxy", "composer", "composer-proxy" -> this.isComposerMetadata(path);
            case "file", "file-proxy" -> false; // Files are always artifacts
            default -> false; // Default to artifact (immutable)
        };
    }

    /**
     * Check if path is an artifact (immutable, cache forever).
     *
     * @param path Request path
     * @return true if artifact, false if metadata
     */
    public boolean isArtifact(final String path) {
        return !this.isMetadata(path);
    }

    /**
     * Get TTL for a path.
     *
     * @param path Request path
     * @return Duration.ZERO for artifacts (cache forever), metadataTtl for metadata
     */
    public Duration ttl(final String path) {
        if (this.isMetadata(path)) {
            return this.metadataTtl;
        }
        // Artifacts cached forever (TTL = 0 means no expiry)
        return Duration.ZERO;
    }

    /**
     * Get stale serve duration for a path.
     *
     * @param path Request path
     * @return How long to serve stale content if upstream is down
     */
    public Duration staleDuration(final String path) {
        if (this.isMetadata(path)) {
            return this.staleDuration;
        }
        // Artifacts never go stale
        return Duration.ofDays(365 * 100); // ~100 years
    }

    /**
     * Check if path should trigger background refresh.
     * Background refresh is triggered when content is in "refresh zone"
     * (between 80% of TTL and 100% of TTL).
     *
     * @param path Request path
     * @param age Age of cached content
     * @return true if background refresh should be triggered
     */
    public boolean shouldRefreshInBackground(final String path, final Duration age) {
        if (this.isArtifact(path)) {
            return false;
        }
        final Duration ttl = this.ttl(path);
        final long refreshThreshold = (long) (ttl.toMillis() * 0.8);
        return age.toMillis() >= refreshThreshold && age.toMillis() < ttl.toMillis();
    }

    /**
     * Check if Maven path is metadata.
     * Maven metadata includes: maven-metadata.xml, -SNAPSHOT versions
     */
    private boolean isMavenMetadata(final String path) {
        return path.endsWith("/maven-metadata.xml")
            || path.endsWith("/maven-metadata.xml.sha1")
            || path.endsWith("/maven-metadata.xml.sha256")
            || path.endsWith("/maven-metadata.xml.sha512")
            || path.endsWith("/maven-metadata.xml.md5")
            || path.contains("-SNAPSHOT/");
    }

    /**
     * Check if NPM path is metadata.
     * NPM metadata includes: package.json, all non-tarball paths
     */
    private boolean isNpmMetadata(final String path) {
        // Tarballs are artifacts
        if (path.endsWith(".tgz") || path.endsWith(".tar.gz")) {
            return false;
        }
        // package.json and registry API calls are metadata
        return path.endsWith("/package.json")
            || path.contains("/-/package/")
            || !path.contains("/-/");
    }

    /**
     * Check if Go module path is metadata.
     * Go metadata includes: @v/list, @latest, .info files
     */
    private boolean isGoMetadata(final String path) {
        return path.contains("/@v/list")
            || path.contains("/@latest")
            || path.endsWith(".info")
            || path.endsWith(".mod");
    }

    /**
     * Check if PyPI path is metadata.
     * PyPI metadata includes: /simple/ paths (package index)
     */
    private boolean isPypiMetadata(final String path) {
        return path.contains("/simple/")
            || path.contains("/simple")
            || path.endsWith("/");
    }

    /**
     * Check if Docker path is metadata.
     * Docker metadata includes: manifests (by tag, not by digest)
     */
    private boolean isDockerMetadata(final String path) {
        // Manifest by digest (sha256:...) is immutable
        if (path.contains("/sha256:")) {
            return false;
        }
        // Blobs are always artifacts
        if (path.contains("/blobs/")) {
            return false;
        }
        // Manifests by tag are metadata
        return path.contains("/manifests/");
    }

    /**
     * Check if Gradle path is metadata.
     * Gradle metadata follows Maven patterns plus Gradle-specific files
     */
    private boolean isGradleMetadata(final String path) {
        return this.isMavenMetadata(path)
            || path.endsWith(".module")
            || path.endsWith(".module.sha1")
            || path.endsWith(".module.sha256")
            || path.endsWith(".module.sha512")
            || path.endsWith(".module.md5");
    }

    /**
     * Check if Composer/PHP path is metadata.
     * Composer metadata includes: packages.json, provider files
     */
    private boolean isComposerMetadata(final String path) {
        // Zip files are artifacts
        if (path.endsWith(".zip")) {
            return false;
        }
        return path.endsWith("/packages.json")
            || path.contains("/p/")
            || path.contains("/p2/")
            || path.contains("/provider-")
            || path.endsWith(".json");
    }

    /**
     * Get the repository type.
     *
     * @return Repository type
     */
    public String repoType() {
        return this.repoType;
    }

    /**
     * Get metadata TTL.
     *
     * @return Metadata TTL
     */
    public Duration metadataTtl() {
        return this.metadataTtl;
    }

    /**
     * Create a builder for CachePolicy.
     *
     * @return Builder instance
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for CachePolicy.
     */
    public static final class Builder {
        private String repoType = "file";
        private Duration metadataTtl = Duration.ofMinutes(5);
        private Duration staleDuration = Duration.ofHours(1);

        /**
         * Set repository type.
         *
         * @param type Repository type
         * @return this builder
         */
        public Builder repoType(final String type) {
            this.repoType = type;
            return this;
        }

        /**
         * Set metadata TTL.
         *
         * @param ttl Metadata TTL
         * @return this builder
         */
        public Builder metadataTtl(final Duration ttl) {
            this.metadataTtl = ttl;
            return this;
        }

        /**
         * Set stale serve duration.
         *
         * @param duration Stale serve duration
         * @return this builder
         */
        public Builder staleDuration(final Duration duration) {
            this.staleDuration = duration;
            return this;
        }

        /**
         * Build the CachePolicy.
         *
         * @return CachePolicy instance
         */
        public CachePolicy build() {
            return new CachePolicy(this.repoType, this.metadataTtl, this.staleDuration);
        }
    }
}
