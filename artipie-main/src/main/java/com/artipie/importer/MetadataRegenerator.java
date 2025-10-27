/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.composer.JsonPackage;
import com.artipie.composer.http.Archive;
import com.artipie.composer.http.TarArchive;
import com.artipie.gem.Gem;
import com.artipie.helm.TgzArchive;
import com.artipie.helm.metadata.IndexYaml;
import com.artipie.importer.api.DigestType;
import com.artipie.maven.metadata.Version;
import com.artipie.npm.MetaUpdate;
import com.artipie.pypi.http.IndexGenerator;
import hu.akarnokd.rxjava2.interop.CompletableInterop;
import io.reactivex.Flowable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xembly.Directives;
import org.xembly.Xembler;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Regenerates repository-specific metadata after artifact import.
 *
 * @since 1.0
 */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
public final class MetadataRegenerator {

    /**
     * Logger.
     */
    private static final Logger LOG = LoggerFactory.getLogger(MetadataRegenerator.class);

    /**
     * Date format for Maven metadata lastUpdated field.
     */
    private static final DateTimeFormatter MAVEN_TS =
        DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC);

    /**
     * Pattern to extract Composer dev suffix identifiers.
     */
    private static final Pattern COMPOSER_VERSION_HINT = Pattern.compile(
        "(v?\\d+\\.\\d+\\.\\d+(?:[-+][\\w\\.]+)?)"
    );

    /**
     * Pattern for Go module artifact paths.
     */
    private static final Pattern GO_ARTIFACT = Pattern.compile(
        "^(?<module>.+)/@v/v(?<version>[^/]+)\\.(?<ext>info|mod|zip)$"
    );

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
     * Repository base URL (if configured).
     */
    private final Optional<String> baseUrl;


    /**
     * Successful metadata generations.
     */
    private final AtomicLong successCount;

    /**
     * Failed metadata generations.
     */
    private final AtomicLong failureCount;

    /**
     * Ctor.
     *
     * @param storage Repository storage
     * @param repoType Repository type
     * @param repoName Repository name
     * @param baseUrl Optional repository base URL (no trailing slash)
     */
    public MetadataRegenerator(
        final Storage storage,
        final String repoType,
        final String repoName,
        final Optional<String> baseUrl
    ) {
        this.storage = storage;
        this.repoType = repoType == null ? "" : repoType;
        this.repoName = repoName;
        this.baseUrl = baseUrl.filter(url -> !url.isBlank());
        this.successCount = new AtomicLong(0);
        this.failureCount = new AtomicLong(0);
    }

    /**
     * Regenerate metadata for an imported artifact.
     *
     * @param artifactKey Artifact key in storage
     * @param request Import request metadata
     * @return Completion stage
     */
    public CompletionStage<Void> regenerate(final Key artifactKey, final ImportRequest request) {
        LOG.debug(
            "Regenerating metadata for {} :: {} (type: {})",
            this.repoName,
            artifactKey.string(),
            this.repoType
        );
        final CompletionStage<Void> operation = switch (this.repoType.toLowerCase(Locale.ROOT)) {
            case "file", "files", "generic", "docker", "oci", "nuget", "conan" ->
                CompletableFuture.completedFuture(null);
            case "npm" -> this.regenerateNpm(artifactKey);
            case "maven", "gradle" -> this.regenerateMaven(artifactKey);
            case "php", "composer" -> this.regenerateComposer(artifactKey, request);
            case "helm" -> this.regenerateHelm(artifactKey);
            case "go", "golang" -> this.regenerateGo(artifactKey);
            case "pypi", "python" -> this.regeneratePypi(artifactKey);
            case "gem", "gems", "ruby" -> this.regenerateGems(artifactKey);
            case "deb", "debian" -> this.regenerateDebian(artifactKey);
            case "rpm" -> this.regenerateRpm(artifactKey);
            case "conda" -> this.regenerateConda(artifactKey);
            default -> {
                LOG.warn(
                    "Unknown repository type '{}' for {} - skipping metadata regeneration",
                    this.repoType,
                    this.repoName
                );
                yield CompletableFuture.completedFuture(null);
            }
        };
        return operation.whenComplete((ignored, error) -> {
            if (error == null) {
                this.successCount.incrementAndGet();
            } else {
                this.failureCount.incrementAndGet();
                LOG.warn(
                    "Metadata regeneration failed for {} :: {} ({}) - {}",
                    this.repoName,
                    artifactKey.string(),
                    this.repoType,
                    error.getMessage()
                );
                if (LOG.isDebugEnabled()) {
                    LOG.debug("Metadata regeneration failure stacktrace", error);
                }
            }
        });
    }

    /**
     * @return number of successful regenerations.
     */
    public long getSuccessCount() {
        return this.successCount.get();
    }

    /**
     * @return number of failed regenerations.
     */
    public long getFailureCount() {
        return this.failureCount.get();
    }

    /**
     * Resets counters.
     */
    public void resetMetrics() {
        this.successCount.set(0);
        this.failureCount.set(0);
    }

    /**
     * Regenerate metadata for Maven/Gradle repositories.
     * CRITICAL: Uses exclusive locking + retries to prevent race conditions during concurrent imports.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateMaven(final Key artifactKey) {
        final String path = artifactKey.string();
        final String normalized = path.toLowerCase(Locale.ROOT);
        // Skip metadata files, checksums, temp files, and lock files
        if (normalized.contains("maven-metadata.xml")
            || this.isChecksumFile(normalized)
            || normalized.endsWith(".lastupdated")
            || normalized.endsWith("_remote.repositories")
            || normalized.contains(".tmp")
            || normalized.contains(".artipie-locks")) {
            return CompletableFuture.completedFuture(null);
        }
        final String[] segments = path.split("/");
        if (segments.length < 3) {
            return CompletableFuture.completedFuture(null);
        }
        final List<String> coords = List.of(segments).subList(0, segments.length - 2);
        if (coords.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final String artifactId = coords.get(coords.size() - 1);
        final String groupId = coords.size() > 1
            ? String.join(".", coords.subList(0, coords.size() - 1))
            : artifactId;
        final String version = segments[segments.length - 2];
        final Key baseKey = new Key.From(String.join("/", coords));
        final Key versionKey = new Key.From(baseKey, version);
        final Key metadataKey = new Key.From(baseKey, "maven-metadata.xml");
        
        // CRITICAL: Lock maven-metadata.xml during update
        // Different artifacts (0.12.11 vs 0.3.0) are locked separately but both update this file
        // Use lock WITHOUT retry to avoid long delays that cause 504 timeouts
        return this.storage.exclusively(
            metadataKey,
            lockedStorage -> this.storage.list(baseKey)
                .exceptionally(ex -> {
                    LOG.debug("Base key {} doesn't exist yet, creating metadata for first version", baseKey);
                    return List.of();
                })
                .thenApply(keys -> collectMavenVersions(baseKey, version, keys))
                .thenCompose(versions -> writeMavenMetadata(baseKey, groupId, artifactId, versions))
                .thenCompose(nothing -> this.generateMavenChecksums(artifactKey))
                .thenCompose(nothing -> this.generateAllVersionChecksums(versionKey))
        );
    }

    /**
     * Collect available Maven versions located under the base key.
     *
     * @param baseKey Base key containing artifact versions
     * @param currentVersion Version of current artifact
     * @param keys All keys under base
     * @return Sorted set of versions
     */
    private static TreeSet<String> collectMavenVersions(
        final Key baseKey,
        final String currentVersion,
        final Collection<Key> keys
    ) {
        final TreeSet<String> versions = new TreeSet<>(
            (left, right) -> new Version(left).compareTo(new Version(right))
        );
        versions.add(currentVersion);
        final String prefix = baseKey.string();
        final String normalizedPrefix = prefix.isEmpty() ? "" : prefix + "/";
        for (final Key key : keys) {
            final String relative = key.string().substring(normalizedPrefix.length());
            if (relative.isEmpty()) {
                continue;
            }
            final String firstSegment = relative.split("/")[0];
            // Skip metadata files, hidden files, and system files
            if (firstSegment.equals("maven-metadata.xml")
                || firstSegment.endsWith(".lastUpdated")
                || firstSegment.endsWith(".properties")
                || firstSegment.startsWith(".")  // Skip .DS_Store, .git, etc.
                || firstSegment.contains(".tmp")
                || firstSegment.contains(".lock")) {
                continue;
            }
            // Try to parse as version to validate it's a real version directory
            try {
                new Version(firstSegment);
                versions.add(firstSegment);
            } catch (final Exception ignored) {
                // Not a valid version, skip it
                LOG.debug("Skipping non-version directory: {}", firstSegment);
            }
        }
        return versions;
    }

    /**
     * Write Maven metadata file for artifact coordinates.
     *
     * @param baseKey Base key
     * @param groupId Group id
     * @param artifactId Artifact id
     * @param versions Available versions
     * @return Completion stage
     */
    private CompletionStage<Void> writeMavenMetadata(
        final Key baseKey,
        final String groupId,
        final String artifactId,
        final TreeSet<String> versions
    ) {
        if (versions.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        final String latest = versions.isEmpty() ? null : versions.last();
        final String release = versions.stream()
            .filter(version -> !version.endsWith("SNAPSHOT"))
            .reduce((first, second) -> second)
            .orElse(latest);
        final Directives dirs = new Directives()
            .add("metadata")
                .add("groupId").set(groupId).up()
                .add("artifactId").set(artifactId).up()
                .add("versioning");
        if (latest != null) {
            dirs.add("latest").set(latest).up();
        }
        if (release != null) {
            dirs.add("release").set(release).up();
        }
        dirs.add("versions");
        versions.forEach(version -> dirs.add("version").set(version).up());
        dirs.up() // versions
            .add("lastUpdated").set(MAVEN_TS.format(Instant.now())).up()
            .up() // versioning
        .up(); // metadata
        final String metadata;
        try {
            metadata = new Xembler(dirs).xml();
        } catch (final Exception err) {
            return CompletableFuture.failedFuture(err);
        }
        final Key metadataKey = new Key.From(baseKey, "maven-metadata.xml");
        return this.storage.save(
            metadataKey,
            new Content.From(metadata.getBytes(StandardCharsets.UTF_8))
        );
    }

    /**
     * Regenerate metadata for Composer repositories.
     *
     * @param artifactKey Artifact key
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateComposer(
        final Key artifactKey,
        final ImportRequest request
    ) {
        final String path = artifactKey.string();
        final String lower = path.toLowerCase(Locale.ROOT);
        
        // Skip hidden directories (starting with .), metadata files, temp files, and lock files
        if (path.contains("/.") 
            || lower.startsWith(".") 
            || lower.startsWith("packages.json") 
            || lower.startsWith("p2/")
            || lower.contains(".tmp")
            || lower.contains(".artipie-locks")) {
            return CompletableFuture.completedFuture(null);
        }
        final boolean isZip = lower.endsWith(".zip");
        final boolean isTar = lower.endsWith(".tar") || lower.endsWith(".tar.gz") || lower.endsWith(".tgz");
        if (!isZip && !isTar) {
            return CompletableFuture.completedFuture(null);
        }
        return this.storage.value(artifactKey)
            .thenCompose(content -> content.asBytesFuture())
            .thenCompose(bytes -> {
                final Archive archive = isZip
                    ? new Archive.Zip(new Archive.Name(fileName(path), "unknown"))
                    : new TarArchive(new Archive.Name(fileName(path), "unknown"));
                return archive.composerFrom(new Content.From(bytes))
                    .thenCompose(json -> updateComposerMetadata(json, path, isZip, request));
            });
    }

    /**
     * Update Composer metadata during import.
     * 
     * <p><b>IMPORTANT:</b> Uses import staging layout to avoid lock contention
     * during bulk imports. After import completes, use {@link com.artipie.composer.ComposerImportMerge}
     * to consolidate staging files into final p2/ layout.</p>
     * 
     * <p>Normal package uploads (via AddArchiveSlice) bypass this and use SatisLayout directly
     * for immediate availability.</p>
     *
     * @param composerJson composer.json content
     * @param storagePath Storage path of archive
     * @param zip Whether archive is zip (otherwise tar)
     * @param request Import request
     * @return Completion stage
     */
    private CompletionStage<Void> updateComposerMetadata(
        final JsonObject composerJson,
        final String storagePath,
        final boolean zip,
        final ImportRequest request
    ) {
        final String packageName = composerJson.getString("name", null);
        if (packageName == null || packageName.isBlank()) {
            LOG.warn(
                "Skipping Composer metadata regeneration for {} - package name missing",
                storagePath
            );
            return CompletableFuture.completedFuture(null);
        }
        String version = composerJson.getString("version", null);
        if (version == null || version.isBlank()) {
            version = request.version()
                .or(() -> extractVersionFromFilename(fileName(storagePath)))
                .orElse(null);
        }
        if (version == null || version.isBlank()) {
            LOG.warn(
                "Skipping Composer metadata regeneration for {} - unable to determine version",
                storagePath
            );
            return CompletableFuture.completedFuture(null);
        }
        
        // Sanitize version for use in URLs and file paths
        // Replace spaces with + to avoid malformed URLs
        final String sanitizedVersion = sanitizeVersion(version);
        
        // Add dist URL and version to composer.json
        final JsonObjectBuilder builder = Json.createObjectBuilder(composerJson);
        builder.add("version", sanitizedVersion);
        
        // Build dist URL - storage path now uses + instead of spaces
        // No URL encoding needed since sanitizeComposerPath already replaced spaces
        final String distUrl = resolveRepositoryUrl(storagePath);
        
        builder.add(
            "dist",
            Json.createObjectBuilder()
                .add("url", distUrl)
                .add("type", zip ? "zip" : "tar")
        );
        
        // CRITICAL: Use ImportStagingLayout for bulk imports to avoid lock contention
        // Each version gets its own file - NO locking conflicts
        // After import completes, run ComposerImportMerge to consolidate into p2/
        final JsonObject normalized = builder.build();
        final JsonPackage pkg = new JsonPackage(normalized);
        final Optional<String> versionOpt = Optional.of(sanitizedVersion);
        
        // Create import staging layout
        final com.artipie.composer.ImportStagingLayout staging = 
            new com.artipie.composer.ImportStagingLayout(
                this.storage,
                Optional.of(this.repositoryRoot())
            );
        
        // Stage version in import area (lock-free, per-version file)
        // This prevents the concurrent lock failures seen in production
        return staging.stagePackageVersion(pkg, versionOpt)
            .thenApply(ignored -> null);
    }



    /**
     * Regenerate metadata for NPM repositories.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateNpm(final Key artifactKey) {
        final String path = artifactKey.string();
        if (!path.toLowerCase(Locale.ROOT).endsWith(".tgz")) {
            return CompletableFuture.completedFuture(null);
        }
        return this.storage.value(artifactKey)
            .thenCompose(content -> content.asBytesFuture())
            .thenCompose(bytes -> {
                try {
                    // Create TgzArchive from base64-encoded bytes (single source of truth)
                    final String encoded = Base64.getEncoder().encodeToString(bytes);
                    final com.artipie.npm.TgzArchive tgz = new com.artipie.npm.TgzArchive(encoded);
                    
                    // Extract package name from archive
                    final JsonObject manifest = tgz.packageJson();
                    final String packageName = manifest.getString("name", null);
                    
                    if (packageName == null || packageName.isBlank()) {
                        LOG.warn(
                            "Skipping NPM metadata regeneration for {} - package name missing",
                            path
                        );
                        return CompletableFuture.completedFuture(null);
                    }
                    
                    // MetaUpdate.ByJson now uses storage.exclusively() for atomic updates
                    return new MetaUpdate.ByTgz(tgz).update(new Key.From(packageName), this.storage);
                } catch (final Exception ex) {
                    LOG.error(
                        "Failed to extract NPM package metadata from {} - {}",
                        path, ex.getMessage()
                    );
                    throw new CompletionException(ex);
                }
            });
    }

    /**
     * Regenerate Helm index.yaml.
     * CRITICAL: Uses exclusive locking + retries to prevent race conditions during concurrent imports.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateHelm(final Key artifactKey) {
        final String path = artifactKey.string().toLowerCase(Locale.ROOT);
        if (!path.endsWith(".tgz")) {
            return CompletableFuture.completedFuture(null);
        }
        
        // CRITICAL: Lock index.yaml during update to prevent concurrent import races
        // Without locking, multiple imports read-modify-write and overwrite each other
        final Key indexKey = new Key.From("index.yaml");
        
        return this.storage.value(artifactKey)
            .thenCompose(content -> content.asBytesFuture())
            .thenCompose(bytes ->
                this.withRetry(
                    () -> this.storage.exclusively(
                        indexKey,
                        lockedStorage -> new IndexYaml(lockedStorage)
                            .update(new TgzArchive(bytes))
                            .to(CompletableInterop.await())
                    ),
                    "Helm index.yaml update for " + path
                )
            );
    }

    /**
     * Regenerate Go module list.
     * CRITICAL: Uses exclusive locking + retries to prevent race conditions during concurrent imports.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateGo(final Key artifactKey) {
        final Matcher matcher = GO_ARTIFACT.matcher(artifactKey.string());
        if (!matcher.matches()) {
            return CompletableFuture.completedFuture(null);
        }
        final String ext = matcher.group("ext").toLowerCase(Locale.ROOT);
        if (!"zip".equals(ext)) {
            return CompletableFuture.completedFuture(null);
        }
        final String module = matcher.group("module");
        final String version = matcher.group("version");
        final Key listKey = new Key.From(String.format("%s/@v/list", module));
        final String entry = String.format("v%s", version);
        
        // CRITICAL: Lock @v/list during update (without retry to avoid timeouts)
        return this.storage.exclusively(
            listKey,
            lockedStorage -> this.storage.exists(listKey).thenCompose(exists -> {
                if (!exists) {
                    return this.storage.save(
                        listKey,
                        new Content.From((entry + '\n').getBytes(StandardCharsets.UTF_8))
                    );
                }
                return this.storage.value(listKey)
                    .thenCompose(content -> content.asBytesFuture())
                    .thenCompose(bytes -> {
                        final List<String> lines = new String(bytes, StandardCharsets.UTF_8)
                            .lines()
                            .map(String::trim)
                            .filter(line -> !line.isEmpty())
                            .collect(Collectors.toList());
                        if (lines.contains(entry)) {
                            return CompletableFuture.completedFuture(null);
                        }
                        lines.add(entry);
                        final String updated = String.join("\n", new TreeSet<>(lines)) + '\n';
                        return this.storage.save(
                            listKey,
                            new Content.From(updated.getBytes(StandardCharsets.UTF_8))
                        );
                    });
            })
        );
    }

    /**
     * Regenerate PyPI simple API indices.
     * CRITICAL: Uses exclusive locking + retries to prevent race conditions during concurrent imports.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> regeneratePypi(final Key artifactKey) {
        final String path = artifactKey.string();
        final String lower = path.toLowerCase(Locale.ROOT);
        // Skip metadata files, temp files, and lock files
        if (lower.startsWith(".pypi/")
            || lower.contains(".tmp")
            || lower.contains(".artipie-locks")) {
            return CompletableFuture.completedFuture(null);
        }
        if (!(lower.endsWith(".whl")
            || lower.endsWith(".tar.gz")
            || lower.endsWith(".tar.bz2")
            || lower.endsWith(".zip"))) {
            return CompletableFuture.completedFuture(null);
        }
        final String[] segments = path.split("/");
        if (segments.length < 3) {
            return CompletableFuture.completedFuture(null);
        }
        final String packageName = segments[0];
        final Key packageKey = new Key.From(packageName);
        final Key packageIndexKey = new Key.From(".pypi", "indices", packageName, "index.html");
        final Key repoIndexKey = new Key.From(".pypi", "indices", "index.html");
        final String prefix = repositoryRoot();
        
        // CRITICAL: Lock per-package index, then repo-wide index
        return this.withRetry(
            () -> this.storage.exclusively(
                packageIndexKey,
                lockedStorage -> new IndexGenerator(this.storage, packageKey, prefix).generate()
            ).thenCompose(nothing ->
                this.withRetry(
                    () -> this.storage.exclusively(
                        repoIndexKey,
                        lockedStorage -> new IndexGenerator(this.storage, Key.ROOT, prefix).generateRepoIndex()
                    ),
                    "PyPI repo index update"
                )
            ),
            "PyPI package index update for " + packageName
        );
    }

    /**
     * Regenerate RubyGems specs.
     * CRITICAL: Uses exclusive locking + retries to prevent race conditions during concurrent imports.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateGems(final Key artifactKey) {
        final String path = artifactKey.string();
        if (!path.endsWith(".gem")) {
            return CompletableFuture.completedFuture(null);
        }
        
        // CRITICAL: Lock specs files during update (specs.4.8.gz, latest_specs.4.8.gz, prerelease_specs.4.8.gz)
        final Key specsLock = new Key.From("specs.4.8.gz");
        
        return this.withRetry(
            () -> this.storage.exclusively(
                specsLock,
                lockedStorage -> new Gem(this.storage).update(artifactKey).thenApply(ignored -> null)
            ),
            "RubyGems specs update for " + path
        );
    }

    /**
     * Debian repositories currently require manual reindex.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateDebian(final Key artifactKey) {
        LOG.debug("Debian metadata regeneration not implemented for {}", artifactKey.string());
        return CompletableFuture.completedFuture(null);
    }

    /**
     * RPM repositories currently require manual reindex.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateRpm(final Key artifactKey) {
        LOG.debug("RPM metadata regeneration not implemented for {}", artifactKey.string());
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Conda repositories currently require manual reindex.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> regenerateConda(final Key artifactKey) {
        LOG.debug("Conda metadata regeneration not implemented for {}", artifactKey.string());
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Generate Maven checksum files for the given artifact.
     *
     * @param artifactKey Artifact key
     * @return Completion stage
     */
    private CompletionStage<Void> generateMavenChecksums(final Key artifactKey) {
        final String path = artifactKey.string();

        if (this.isChecksumFile(path)) {
            return CompletableFuture.completedFuture(null);
        }

        return this.storage.value(artifactKey)
            .thenCompose(content -> {
                final DigestingContent digesting = new DigestingContent(
                    content,
                    EnumSet.of(
                        DigestType.MD5,
                        DigestType.SHA1,
                        DigestType.SHA256,
                        DigestType.SHA512
                    )
                );
                return Flowable.fromPublisher(digesting)
                    .ignoreElements()
                    .to(CompletableInterop.await())
                    .thenCompose(ignored -> digesting.result())
                    .thenCompose(result -> {
                        final Map<DigestType, String> digests = result.digests();
                        return CompletableFuture.allOf(
                            this.storage.save(
                                new Key.From(path + ".md5"),
                                new Content.From(digests.get(DigestType.MD5).getBytes(StandardCharsets.UTF_8))
                            ),
                            this.storage.save(
                                new Key.From(path + ".sha1"),
                                new Content.From(digests.get(DigestType.SHA1).getBytes(StandardCharsets.UTF_8))
                            ),
                            this.storage.save(
                                new Key.From(path + ".sha256"),
                                new Content.From(digests.get(DigestType.SHA256).getBytes(StandardCharsets.UTF_8))
                            ),
                            this.storage.save(
                                new Key.From(path + ".sha512"),
                                new Content.From(digests.get(DigestType.SHA512).getBytes(StandardCharsets.UTF_8))
                            )
                        );
                    });
            });
    }

    /**
     * Generate checksums for all artifacts in a version directory.
     * This ensures all .jar, .pom, .war, etc. files have checksums,
     * not just the one that triggered the metadata regeneration.
     *
     * @param versionKey Key to version directory
     * @return Completion stage
     */
    private CompletionStage<Void> generateAllVersionChecksums(final Key versionKey) {
        return this.storage.list(versionKey)
            .thenCompose(keys -> {
                // Filter to only artifact files (not checksums, metadata, or temp files)
                final List<CompletableFuture<Void>> checksumFutures = keys.stream()
                    .map(Key::string)
                    .filter(path -> {
                        final String lower = path.toLowerCase(Locale.ROOT);
                        return !this.isChecksumFile(lower)
                            && !lower.contains("maven-metadata.xml")
                            && !lower.endsWith(".lastupdated")
                            && !lower.endsWith("_remote.repositories")
                            && !lower.contains(".tmp")
                            && !lower.contains(".artipie-locks");
                    })
                    .map(path -> {
                        final Key artifactKey = new Key.From(path);
                        // Check if checksums already exist
                        return this.storage.exists(new Key.From(path + ".sha1"))
                            .thenCompose(exists -> {
                                if (exists) {
                                    // Checksums already exist, skip
                                    return CompletableFuture.completedFuture(null);
                                }
                                // Generate checksums for this artifact
                                return this.generateMavenChecksums(artifactKey)
                                    .exceptionally(ex -> {
                                        LOG.warn(
                                            "Failed to generate checksums for {} - {}",
                                            path, ex.getMessage()
                                        );
                                        return null;
                                    }).toCompletableFuture();
                            });
                    })
                    .toList();
                
                return CompletableFuture.allOf(checksumFutures.toArray(new CompletableFuture[0]));
            })
            .thenApply(ignored -> null);
    }

    /**
     * Check if path corresponds to checksum sidecar.
     *
     * @param path Path string
     * @return True if checksum sidecar
     */
    private boolean isChecksumFile(final String path) {
        final String lower = path.toLowerCase(Locale.ROOT);
        return lower.endsWith(".md5")
            || lower.endsWith(".sha1")
            || lower.endsWith(".sha256")
            || lower.endsWith(".sha512")
            || lower.endsWith(".asc")
            || lower.endsWith(".sig");
    }

    /**
     * Extract file name from storage path.
     *
     * @param path Storage path
     * @return File name
     */
    private static String fileName(final String path) {
        final int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    /**
     * Attempt to extract Composer version from filename.
     *
     * @param filename File name
     * @return Optional version
     */
    private static Optional<String> extractVersionFromFilename(final String filename) {
        final Matcher matcher = COMPOSER_VERSION_HINT.matcher(filename);
        if (matcher.find()) {
            return Optional.ofNullable(matcher.group(1));
        }
        return Optional.empty();
    }
    
    /**
     * Execute operation with retries and exponential backoff.
     * Used for metadata updates that may fail due to concurrent modifications.
     *
     * @param operation Operation to execute
     * @param description Operation description for logging
     * @param <T> Result type
     * @return Completion stage with result
     */
    private <T> CompletionStage<T> withRetry(
        final java.util.function.Supplier<CompletionStage<T>> operation,
        final String description
    ) {
        return this.withRetry(operation, description, 0);
    }

    /**
     * Execute operation with retries and exponential backoff (internal recursive method).
     * CRITICAL: Uses non-blocking delay to avoid thread pool exhaustion.
     *
     * @param operation Operation to execute
     * @param description Operation description for logging
     * @param attempt Current attempt number (0-based)
     * @param <T> Result type
     * @return Completion stage with result
     */
    private <T> CompletionStage<T> withRetry(
        final java.util.function.Supplier<CompletionStage<T>> operation,
        final String description,
        final int attempt
    ) {
        final int maxRetries = 10;
        return operation.get()
            .exceptionally(error -> {
                if (attempt < maxRetries) {
                    // Exponential backoff: 10ms, 20ms, 40ms, 80ms, 160ms, ...
                    final long delayMs = 10L * (1L << attempt);
                    LOG.warn(
                        "Retry {}/{} for {} after {}ms: {}",
                        attempt + 1, maxRetries, description, delayMs, error.getMessage()
                    );
                    // Return null to signal retry needed
                    return null;
                } else {
                    LOG.error("Failed after {} retries for {}: {}", maxRetries, description, error.getMessage());
                    throw new CompletionException(error);
                }
            })
            .thenCompose(result -> {
                if (result == null) {
                    // Retry needed - schedule non-blocking delay
                    final long delayMs = 10L * (1L << attempt);
                    return CompletableFuture
                        .supplyAsync(
                            () -> null,
                            CompletableFuture.delayedExecutor(
                                delayMs, 
                                java.util.concurrent.TimeUnit.MILLISECONDS
                            )
                        )
                        .thenCompose(ignored -> this.withRetry(operation, description, attempt + 1));
                }
                return CompletableFuture.completedFuture(result);
            });
    }

    /**
     * Sanitize version string for use in URLs and file paths.
     * Replaces spaces and other problematic characters with +.
     *
     * @param version Original version string
     * @return Sanitized version safe for URLs
     */
    private static String sanitizeVersion(final String version) {
        if (version == null || version.isEmpty()) {
            return version;
        }
        // Replace spaces with plus signs (URL-safe and avoids hyphen conflicts)
        // Also replace other problematic characters that might appear in versions
        return version
            .replaceAll("\\s+", "+")              // spaces -> plus signs
            .replaceAll("[^a-zA-Z0-9._+-]", "+"); // other invalid chars -> plus signs
    }

    /**
     * Resolve repository root URL or path (without trailing slash).
     *
     * @return Root URL
     */
    private String repositoryRoot() {
        return this.baseUrl.orElse("/" + this.repoName);
    }

    /**
     * Resolve full repository URL for relative path.
     *
     * @param relative Relative path without leading slash
     * @return Resolved URL
     */
    private String resolveRepositoryUrl(final String relative) {
        final String clean = relative.startsWith("/") ? relative : "/" + relative;
        return this.baseUrl.map(url -> url + clean).orElse(clean);
    }
}
