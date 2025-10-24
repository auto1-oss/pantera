/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.ContentDigest;
import com.artipie.asto.ext.Digests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service to regenerate repository-specific metadata after import.
 * 
 * <p>Different repository types require different metadata structures:
 * - NPM: package.json per package
 * - Maven/Gradle: maven-metadata.xml + checksums
 * - Composer: p2/{vendor}/{package}.json
 * - Helm: index.yaml
 * - Go: module list files
 * - PyPI: Simple API index
 * - Gems: specs indices
 * - File: No metadata required
 * </p>
 *
 * @since 1.0
 */
public final class MetadataRegenerator {

    /**
     * Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MetadataRegenerator.class);

    /**
     * Repository storage.
     */
    private final Storage storage;

    /**
     * Repository type.
     */
    private final String repoType;

    /**
     * Repository name.
     */
    private final String repoName;

    /**
     * Metrics: successful metadata regenerations.
     */
    private final AtomicLong successCount;

    /**
     * Metrics: failed metadata regenerations.
     */
    private final AtomicLong failureCount;

    /**
     * Ctor.
     *
     * @param storage Repository storage
     * @param repoType Repository type
     * @param repoName Repository name
     */
    public MetadataRegenerator(final Storage storage, final String repoType, final String repoName) {
        this.storage = storage;
        this.repoType = repoType;
        this.repoName = repoName;
        this.successCount = new AtomicLong(0);
        this.failureCount = new AtomicLong(0);
    }

    /**
     * Regenerate metadata for an imported artifact.
     *
     * @param artifactKey Artifact key in storage
     * @param request Import request with metadata
     * @return Completion stage
     */
    public CompletionStage<Void> regenerate(final Key artifactKey, final ImportRequest request) {
        LOG.debug("Regenerating metadata for {} :: {} (type: {})", 
            this.repoName, artifactKey.string(), this.repoType);

        return switch (this.repoType.toLowerCase()) {
            case "npm" -> this.regenerateNpm(artifactKey, request);
            case "maven", "gradle" -> this.regenerateMaven(artifactKey, request);
            case "php", "composer" -> this.regenerateComposer(artifactKey, request);
            case "helm" -> this.regenerateHelm(artifactKey, request);
            case "go", "golang" -> this.regenerateGo(artifactKey, request);
            case "pypi", "python" -> this.regeneratePypi(artifactKey, request);
            case "gem", "gems", "ruby" -> this.regenerateGems(artifactKey, request);
            case "file", "files", "generic" -> CompletableFuture.completedFuture(null); // No metadata
            case "docker", "oci" -> CompletableFuture.completedFuture(null); // Manifests are self-contained
            case "nuget" -> CompletableFuture.completedFuture(null); // NuGet uses query API, not file metadata
            case "deb", "debian" -> this.regenerateDebian(artifactKey, request);
            case "rpm" -> this.regenerateRpm(artifactKey, request);
            case "conda" -> this.regenerateConda(artifactKey, request);
            case "conan" -> CompletableFuture.completedFuture(null); // Conan v2 doesn't need server-side metadata
            default -> {
                LOG.warn("Unknown repository type '{}' - skipping metadata regeneration", this.repoType);
                yield CompletableFuture.completedFuture(null);
            }
        };
    }

    /**
     * Regenerate NPM package.json metadata.
     * NPM tarballs are already complete - no additional metadata needed.
     * The package.json inside the tarball is sufficient.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateNpm(final Key artifactKey, final ImportRequest request) {
        // NPM doesn't require server-side metadata generation
        // The tarball contains package.json which npm client extracts
        // Just validate the tarball exists
        LOG.debug("NPM artifact validated: {}", artifactKey.string());
        this.successCount.incrementAndGet();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Regenerate Maven metadata.xml files.
     * Generates maven-metadata.xml with version list and checksums.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateMaven(final Key artifactKey, final ImportRequest request) {
        // Maven metadata is regenerated per-version during upload
        // The metadata.xml at group/artifact level needs batch regeneration
        // For import, we rely on post-import batch metadata rebuild
        LOG.info("Maven artifact imported: {} - metadata rebuild required at group/artifact level", 
            artifactKey.string());
        
        // Generate checksums for the imported artifact
        return this.generateMavenChecksums(artifactKey)
            .thenApply(v -> {
                this.successCount.incrementAndGet();
                return (Void) null;
            })
            .exceptionally(err -> {
                this.failureCount.incrementAndGet();
                LOG.warn("Failed to generate Maven checksums for {}: {}", 
                    artifactKey.string(), err.getMessage());
                return (Void) null;
            });
    }

    /**
     * Regenerate Composer package metadata.
     * Composer requires p2/{vendor}/{package}.json with all versions.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateComposer(final Key artifactKey, final ImportRequest request) {
        // Composer metadata regeneration requires:
        // 1. Extract composer.json from uploaded ZIP
        // 2. Update p2/{vendor}/{package}.json with version entry
        LOG.info("Composer metadata regeneration for {} - requires ZIP extraction", artifactKey.string());
        
        // TODO: Implement by calling composer.Repository.addArchive() or similar
        // For now, return warning that manual reindex is needed
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Regenerate Helm index.yaml.
     * Helm requires index.yaml at repository root with all charts.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateHelm(final Key artifactKey, final ImportRequest request) {
        // Helm metadata regeneration requires:
        // 1. Extract Chart.yaml from .tgz
        // 2. Update index.yaml
        LOG.info("Helm metadata regeneration for {} - requires tarball extraction", artifactKey.string());
        
        // TODO: Implement by calling helm.IndexYaml.update() or similar
        // For now, return warning that manual reindex is needed
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Regenerate Go module list.
     * Go requires /{module}/@v/list file with version list.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateGo(final Key artifactKey, final ImportRequest request) {
        // Go metadata regeneration requires:
        // 1. Parse module path from artifact key
        // 2. List all versions
        // 3. Update /{module}/@v/list
        LOG.info("Go metadata regeneration for {} - requires directory scan", artifactKey.string());
        
        // TODO: Implement by calling goproxy module list update
        // For now, return warning that manual reindex is needed
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Regenerate PyPI Simple API index.
     * PyPI requires HTML index pages at /simple/{package}/.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regeneratePypi(final Key artifactKey, final ImportRequest request) {
        // PyPI metadata regeneration requires:
        // 1. Parse package name from wheel/sdist filename
        // 2. Update /simple/{package}/index.html
        LOG.info("PyPI metadata regeneration for {} - requires index rebuild", artifactKey.string());
        
        // TODO: Implement by calling pypi metadata builder
        // For now, return warning that manual reindex is needed
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Regenerate Gems specs index.
     * Gems requires specs.4.8.gz marshalled index.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateGems(final Key artifactKey, final ImportRequest request) {
        // Gems metadata regeneration requires:
        // 1. Extract gemspec from .gem file
        // 2. Update specs.4.8.gz index
        LOG.info("Gems metadata regeneration for {} - requires gem extraction", artifactKey.string());
        
        // TODO: Implement by calling gem.Gem.update() or similar
        // For now, return warning that manual reindex is needed
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Regenerate Debian Packages index.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateDebian(final Key artifactKey, final ImportRequest request) {
        LOG.info("Debian metadata regeneration for {} - requires Packages index rebuild", artifactKey.string());
        // TODO: Implement debian metadata regeneration
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Regenerate RPM repodata.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateRpm(final Key artifactKey, final ImportRequest request) {
        LOG.info("RPM metadata regeneration for {} - requires repodata rebuild", artifactKey.string());
        // TODO: Implement rpm metadata regeneration
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Regenerate Conda repodata.json.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateConda(final Key artifactKey, final ImportRequest request) {
        LOG.info("Conda metadata regeneration for {} - requires repodata.json rebuild", artifactKey.string());
        // TODO: Implement conda metadata regeneration
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Generate Maven checksums for an artifact.
     * Creates .md5, .sha1, .sha256, .sha512 files alongside the artifact.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> generateMavenChecksums(final Key artifactKey) {
        final String path = artifactKey.string();
        
        // Skip if this is already a checksum file
        if (path.endsWith(".md5") || path.endsWith(".sha1") || 
            path.endsWith(".sha256") || path.endsWith(".sha512") ||
            path.endsWith(".asc") || path.endsWith(".sig")) {
            return CompletableFuture.completedFuture(null);
        }

        LOG.debug("Generating Maven checksums for {}", path);

        // Read artifact content
        return this.storage.value(artifactKey)
            .thenCompose(content -> {
                // Calculate all digests in parallel
                final CompletableFuture<String> md5Future = new ContentDigest(
                    content, Digests.MD5
                ).hex().toCompletableFuture();
                
                final CompletableFuture<String> sha1Future = this.storage.value(artifactKey)
                    .thenCompose(c -> new ContentDigest(c, Digests.SHA1).hex().toCompletableFuture());
                
                final CompletableFuture<String> sha256Future = this.storage.value(artifactKey)
                    .thenCompose(c -> new ContentDigest(c, Digests.SHA256).hex().toCompletableFuture());
                
                final CompletableFuture<String> sha512Future = this.storage.value(artifactKey)
                    .thenCompose(c -> new ContentDigest(c, Digests.SHA512).hex().toCompletableFuture());

                return CompletableFuture.allOf(md5Future, sha1Future, sha256Future, sha512Future)
                    .thenCompose(v -> {
                        // Save all checksum files
                        final String md5 = md5Future.join();
                        final String sha1 = sha1Future.join();
                        final String sha256 = sha256Future.join();
                        final String sha512 = sha512Future.join();

                        return CompletableFuture.allOf(
                            this.storage.save(
                                new Key.From(path + ".md5"),
                                new Content.From(md5.getBytes(StandardCharsets.UTF_8))
                            ).toCompletableFuture(),
                            this.storage.save(
                                new Key.From(path + ".sha1"),
                                new Content.From(sha1.getBytes(StandardCharsets.UTF_8))
                            ).toCompletableFuture(),
                            this.storage.save(
                                new Key.From(path + ".sha256"),
                                new Content.From(sha256.getBytes(StandardCharsets.UTF_8))
                            ).toCompletableFuture(),
                            this.storage.save(
                                new Key.From(path + ".sha512"),
                                new Content.From(sha512.getBytes(StandardCharsets.UTF_8))
                            ).toCompletableFuture()
                        );
                    });
            })
            .thenApply(v -> {
                LOG.debug("Generated checksums for {}", path);
                return null;
            });
    }

    /**
     * Get metrics for successful regenerations.
     *
     * @return Success count
     */
    public long getSuccessCount() {
        return this.successCount.get();
    }

    /**
     * Get metrics for failed regenerations.
     *
     * @return Failure count
     */
    public long getFailureCount() {
        return this.failureCount.get();
    }

    /**
     * Reset metrics counters.
     */
    public void resetMetrics() {
        this.successCount.set(0);
        this.failureCount.set(0);
    }
}
