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
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.ext.ContentDigest;
import com.auto1.pantera.asto.ext.Digests;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.ContentWithSize;
import com.auto1.pantera.http.slice.KeyFromPath;
import com.auto1.pantera.index.SyncArtifactIndexer;
import com.auto1.pantera.maven.metadata.MavenTimestamp;
import com.auto1.pantera.maven.metadata.Version;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.jcabi.xml.XMLDocument;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Simple upload slice that saves files directly to storage, similar to Gradle adapter.
 * No temporary directories, no complex validation - just save and optionally emit events.
 * @since 0.8
 */
public final class UploadSlice implements Slice {

    /**
     * Supported checksum algorithms.
     */
    private static final List<String> CHECKSUM_ALGS = Arrays.asList("sha512", "sha256", "sha1", "md5");

    /**
     * Storage.
     */
    private final Storage storage;

    /**
     * Artifact events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Synchronous artifact-index writer. Runs inline with upload so the
     * group resolver's index lookup sees the new artifact immediately —
     * no stale-index window. Defaults to {@link SyncArtifactIndexer#NOOP}
     * when no DB is configured.
     */
    private final SyncArtifactIndexer syncIndex;

    /**
     * Ctor without events.
     * @param storage Abstract storage
     */
    public UploadSlice(final Storage storage) {
        this(storage, Optional.empty(), "maven", SyncArtifactIndexer.NOOP);
    }

    /**
     * Legacy ctor — no synchronous index writer. Kept for callers that
     * have not been updated yet; tests use this overload.
     * @param storage Storage
     * @param events Artifact events queue
     * @param rname Repository name
     */
    public UploadSlice(
        final Storage storage,
        final Optional<Queue<ArtifactEvent>> events,
        final String rname
    ) {
        this(storage, events, rname, SyncArtifactIndexer.NOOP);
    }

    /**
     * Ctor with synchronous index writer.
     * @param storage Storage
     * @param events Artifact events queue
     * @param rname Repository name
     * @param syncIndex Synchronous artifact-index writer
     */
    public UploadSlice(
        final Storage storage,
        final Optional<Queue<ArtifactEvent>> events,
        final String rname,
        final SyncArtifactIndexer syncIndex
    ) {
        this.storage = storage;
        this.events = events;
        this.rname = rname;
        this.syncIndex = syncIndex;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content body
    ) {
        // Strip semicolon-separated metadata properties from the path to avoid exceeding
        // filesystem filename length limits (typically 255 bytes). These properties are
        // added by JFrog Artifactory and Maven build tools (e.g., vcs.revision, build.timestamp)
        // but are not part of the actual artifact filename.
        final String path = line.uri().getPath();
        final String sanitizedPath;
        final int semicolonIndex = path.indexOf(';');
        if (semicolonIndex > 0) {
            sanitizedPath = path.substring(0, semicolonIndex);
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Stripped metadata properties from path: " + path + " -> " + sanitizedPath)
                .eventCategory("web")
                .eventAction("path_sanitization")
                .log();
        } else {
            sanitizedPath = path;
        }

        final Key key = new KeyFromPath(sanitizedPath);
        final String owner = new Login(headers).getValue();
        
        // Get content length from headers for event record
        final long size = headers.stream()
            .filter(h -> "Content-Length".equalsIgnoreCase(h.getKey()))
            .findFirst()
            .map(h -> Long.parseLong(h.getValue()))
            .orElse(0L);
        
        // Track upload metric
        this.recordMetric(() ->
            com.auto1.pantera.metrics.PanteraMetrics.instance().upload(this.rname, "maven")
        );

        // Track bandwidth (upload)
        if (size > 0) {
            this.recordMetric(() ->
                com.auto1.pantera.metrics.PanteraMetrics.instance().bandwidth(this.rname, "maven", "upload", size)
            );
        }
        
        final String keyPath = key.string();
        
        // Special handling for maven-metadata.xml - fix it BEFORE saving
        if (keyPath.contains("maven-metadata.xml") && !keyPath.endsWith(".sha1") && !keyPath.endsWith(".md5")) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Intercepting maven-metadata.xml upload for fixing")
                .eventCategory("web")
                .eventAction("metadata_upload")
                .field("package.path", keyPath)
                .log();
            return new ContentWithSize(body, headers).asBytesFuture().thenCompose(
                bytes -> this.fixMetadataBytes(bytes).thenCompose(
                    fixedBytes -> {
                        // Save the FIXED metadata
                        return this.storage.save(key, new Content.From(fixedBytes)).thenCompose(
                            nothing -> {
                                EcsLogger.debug("com.auto1.pantera.maven")
                                    .message("Saved fixed maven-metadata.xml, generating checksums")
                                    .eventCategory("web")
                                    .eventAction("metadata_upload")
                                    .field("package.path", keyPath)
                                    .log();
                                // Generate checksums for the fixed content
                                return this.generateChecksums(key);
                            }
                        );
                    }
                )
            ).thenCompose(
                sha256 -> this.addEvent(key, owner, size, sha256)
                    .thenApply(ignored -> ResponseBuilder.created().build())
            ).exceptionally(
                throwable -> {
                    EcsLogger.error("com.auto1.pantera.maven")
                        .message("Failed to save artifact")
                        .eventCategory("web")
                        .eventAction("artifact_upload")
                        .eventOutcome("failure")
                        .error(throwable)
                        .field("package.path", keyPath)
                        .log();
                    return ResponseBuilder.internalError().build();
                }
            );
        }
        
        // For maven-metadata.xml checksums, SKIP them - we generated our own
        if (keyPath.contains("maven-metadata.xml") && (keyPath.endsWith(".sha1") || keyPath.endsWith(".md5") || keyPath.endsWith(".sha256") || keyPath.endsWith(".sha512"))) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Skipping Maven-uploaded checksum for metadata (using generated checksums)")
                .eventCategory("web")
                .eventAction("checksum_upload")
                .field("package.path", keyPath)
                .log();
            // Don't save Maven's checksums - we already generated correct ones
            return CompletableFuture.completedFuture(ResponseBuilder.created().build());
        }
        
        // Save file first (normal flow for non-metadata files)
        return this.storage.save(key, new ContentWithSize(body, headers)).thenCompose(
            nothing -> {
                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Saved artifact file")
                    .eventCategory("web")
                    .eventAction("artifact_upload")
                    .field("package.path", keyPath)
                    .field("package.size", size)
                    .log();

                // For non-metadata/checksum files, generate checksums
                if (this.shouldGenerateChecksums(key)) {
                    return this.generateChecksums(key);
                } else {
                    return CompletableFuture.<String>completedFuture(null);
                }
            }
        ).thenCompose(
            sha256 -> this.addEvent(key, owner, size, sha256)
                .thenApply(ignored -> ResponseBuilder.created().build())
        ).exceptionally(
            throwable -> {
                EcsLogger.error("com.auto1.pantera.maven")
                    .message("Failed to save artifact")
                    .eventCategory("web")
                    .eventAction("artifact_upload")
                    .eventOutcome("failure")
                    .error(throwable)
                    .field("package.path", keyPath)
                    .log();
                return ResponseBuilder.internalError().build();
            }
        );
    }

    /**
     * Normalize maven-metadata.xml bytes.
     *
     * <p>Ensures {@code <latest>} is the highest version (adding it if absent) and
     * normalises {@code <lastUpdated>} to Maven-standard {@code yyyyMMddHHmmss} UTC.
     * Epoch-millisecond values written by older clients are corrected here.
     *
     * @param bytes Original metadata XML bytes
     * @return Completable future with normalised bytes
     */
    @SuppressWarnings("PMD.CognitiveComplexity")
    private CompletableFuture<byte[]> fixMetadataBytes(final byte[] bytes) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                final String xml = new String(bytes, StandardCharsets.UTF_8);
                // Parse via our own DocumentBuilder with a silent ErrorHandler so
                // malformed XML (BOM, HTML error pages served as metadata) does
                // NOT spill "[Fatal Error] :1:1: ..." to stderr — the SAX default
                // handler prints before the exception propagates.
                final XMLDocument doc = new XMLDocument(parseSilently(xml));
                final List<String> versions = doc.xpath("//version/text()");
                if (versions.isEmpty()) {
                    return bytes;
                }

                final String highestVersion = versions.stream()
                    .max(Comparator.comparing(Version::new))
                    .orElse(versions.get(versions.size() - 1));

                final List<String> currentLatest = doc.xpath("//latest/text()");
                final String existingLatest = currentLatest.isEmpty() ? null : currentLatest.get(0);

                final String newLatest;
                if (existingLatest == null || existingLatest.isEmpty()) {
                    newLatest = highestVersion;
                } else {
                    final Version existing = new Version(existingLatest);
                    final Version highest = new Version(highestVersion);
                    newLatest = highest.compareTo(existing) > 0 ? highestVersion : existingLatest;
                }

                String result = xml;

                // Update existing <latest> or insert it before <release>/<versions>
                if (existingLatest != null && !existingLatest.isEmpty()) {
                    result = result.replaceFirst(
                        "<latest>[^<]*</latest>",
                        Matcher.quoteReplacement("<latest>" + newLatest + "</latest>")
                    );
                } else if (result.contains("<release>")) {
                    result = result.replaceFirst(
                        "<release>",
                        Matcher.quoteReplacement("<latest>" + newLatest + "</latest>\n    <release>")
                    );
                } else if (result.contains("<versions>")) {
                    result = result.replaceFirst(
                        "<versions>",
                        Matcher.quoteReplacement("<latest>" + newLatest + "</latest>\n    <versions>")
                    );
                }

                // Always normalise <lastUpdated> to yyyyMMddHHmmss UTC.
                // This repairs epoch-millisecond values from older clients/versions.
                final String timestamp = MavenTimestamp.now();
                if (result.contains("<lastUpdated>")) {
                    result = result.replaceFirst(
                        "<lastUpdated>[^<]*</lastUpdated>",
                        Matcher.quoteReplacement("<lastUpdated>" + timestamp + "</lastUpdated>")
                    );
                } else {
                    result = result.replaceFirst(
                        "</versioning>",
                        Matcher.quoteReplacement(
                            "    <lastUpdated>" + timestamp + "</lastUpdated>\n  </versioning>"
                        )
                    );
                }

                EcsLogger.debug("com.auto1.pantera.maven")
                    .message("Normalised maven-metadata.xml")
                    .eventCategory("web")
                    .eventAction("metadata_fix")
                    .eventOutcome("success")
                    .field("package.version", newLatest)
                    .log();
                return result.getBytes(StandardCharsets.UTF_8);
            } catch (final IllegalArgumentException | SAXException | IOException
                           | ParserConfigurationException ex) {
                EcsLogger.warn("com.auto1.pantera.maven")
                    .message("Failed to parse metadata XML, using original")
                    .eventCategory("web")
                    .eventAction("metadata_fix")
                    .eventOutcome("failure")
                    .field("error.message", ex.getMessage())
                    .log();
                return bytes;
            }
        });
    }

    /**
     * Silent SAX error handler — lets the exception propagate so the caller
     * logs a structured WARN, but prevents the default handler from writing
     * {@code [Fatal Error] :1:1: Content is not allowed in prolog.} to stderr.
     */
    private static final ErrorHandler SILENT_SAX_HANDLER = new ErrorHandler() {
        @Override
        public void warning(final SAXParseException ex) { /* ignore */ }

        @Override
        public void error(final SAXParseException ex) throws SAXException {
            throw ex;
        }

        @Override
        public void fatalError(final SAXParseException ex) throws SAXException {
            throw ex;
        }
    };

    /**
     * Parse XML into a DOM document without printing SAX errors to stderr.
     * Disallows DOCTYPE declarations to defuse XXE and billion-laughs attacks.
     */
    private static Document parseSilently(final String xml)
        throws SAXException, IOException, ParserConfigurationException {
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        final DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(SILENT_SAX_HANDLER);
        return builder.parse(new InputSource(new StringReader(xml)));
    }

    /**
     * Check if we should generate checksums for this file.
     * Don't generate checksums for checksum files themselves.
     * @param key File key
     * @return True if checksums should be generated
     */
    private boolean shouldGenerateChecksums(final Key key) {
        final String path = key.string();
        return !path.endsWith(".md5") 
            && !path.endsWith(".sha1") 
            && !path.endsWith(".sha256") 
            && !path.endsWith(".sha512");
    }

    /**
     * Generate checksum sidecar files (MD5, SHA-1, SHA-256, SHA-512) for the
     * given artifact and return its SHA-256 hex digest.
     *
     * <p>The SHA-256 is surfaced explicitly so upload handlers can attach it
     * to the {@link ArtifactEvent} via {@link ArtifactEvent#withChecksum(String)}
     * — the audit log uses this for {@code package.checksum}.</p>
     *
     * @param key Original file key
     * @return Completable future yielding the SHA-256 hex digest
     */
    private CompletableFuture<String> generateChecksums(final Key key) {
        final List<CompletableFuture<String>> perAlg = CHECKSUM_ALGS.stream().map(
            alg -> this.storage.value(key).thenCompose(
                content -> new ContentDigest(
                    content, Digests.valueOf(alg.toUpperCase(Locale.US))
                ).hex()
            ).thenCompose(
                hex -> this.storage.save(
                    new Key.From(String.format("%s.%s", key.string(), alg)),
                    new Content.From(hex.getBytes(StandardCharsets.UTF_8))
                ).thenApply(ignored -> "sha256".equalsIgnoreCase(alg) ? hex : null)
            ).toCompletableFuture()
        ).toList();
        return CompletableFuture.allOf(perAlg.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> perAlg.stream()
                .map(CompletableFuture::join)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null));
    }

    /**
     * Add artifact event to queue for primary artifact uploads.
     *
     * <p>Uses structural filename-prefix detection — NOT an extension whitelist.
     * An upload qualifies as a primary artifact when:
     * <ul>
     *   <li>It lives under the Maven layout: {@code /{groupId}/{artifactId}/{version}/{filename}}</li>
     *   <li>The filename starts with {@code {artifactId}-} (Maven naming convention)</li>
     *   <li>It is NOT a companion file: metadata, checksum, signature, sources, or javadoc</li>
     * </ul>
     *
     * <p>This matches the invariant used by {@code ArtifactNameParser.parseMaven} on the
     * read path, keeping write- and read-side logic consistent. Any extension — {@code .yaml},
     * {@code .json}, {@code .zip}, future types — gets indexed as long as the filename follows
     * Maven naming.
     *
     * @param key Artifact key
     * @param owner Owner
     * @param size Artifact size
     */
    private CompletableFuture<Void> addEvent(
        final Key key, final String owner, final long size, final String sha256
    ) {
        final String path = key.string().startsWith("/") ? key.string() : "/" + key.string();

        if (!this.isPrimaryArtifactPath(path)) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Skipping non-primary artifact file for event")
                .eventCategory("web")
                .eventAction("event_creation")
                .field("package.path", path)
                .log();
            return CompletableFuture.completedFuture(null);
        }

        // pkg = "{groupId}/{artifactId}/{version}" (everything before the filename)
        final String pkg = path.substring(0, path.lastIndexOf('/'));
        return this.createAndAddEvent(pkg, owner, size, sha256);
    }

    /**
     * Check if a path represents a primary Maven artifact worth indexing.
     * See {@link #addEvent} for the full contract.
     *
     * @param path File path (always starts with '/')
     * @return True if this upload should produce an {@link ArtifactEvent}
     */
    private boolean isPrimaryArtifactPath(final String path) {
        if (this.isMetadataOrChecksum(path)) {
            return false;
        }
        if (path.endsWith(".asc") || path.endsWith(".sig")) {
            return false;
        }
        if (path.endsWith("-sources.jar") || path.endsWith("-javadoc.jar")) {
            return false;
        }
        final String[] segments = path.split("/");
        // Minimum: ["", groupId, artifactId, version, filename] = 5 segments
        if (segments.length < 5) {
            return false;
        }
        final String artifactId = segments[segments.length - 3];
        final String filename = segments[segments.length - 1];
        return filename.startsWith(artifactId + "-");
    }

    /**
     * Check if path is metadata or checksum file.
     * @param path File path
     * @return True if metadata or checksum
     */
    private boolean isMetadataOrChecksum(final String path) {
        return path.contains("maven-metadata.xml")
            || path.endsWith(".md5")
            || path.endsWith(".sha1")
            || path.endsWith(".sha256")
            || path.endsWith(".sha512");
    }

    /**
     * Create and add artifact event from package path.
     * @param pkg Package path (group/artifact/version)
     * @param owner Owner
     * @param size Artifact size
     */
    private CompletableFuture<Void> createAndAddEvent(
        final String pkg, final String owner, final long size, final String sha256
    ) {
        // Extract version (last directory before the file)
        final String[] parts = pkg.split("/");
        final String version = parts.length > 0 ? parts[parts.length - 1] : "unknown";

        // Remove version from pkg to get group/artifact only
        String groupArtifact = pkg.substring(0, pkg.lastIndexOf('/'));

        // Remove leading slash if present
        if (groupArtifact.startsWith("/")) {
            groupArtifact = groupArtifact.substring(1);
        }

        // Format artifact name as group.artifact (replacing / with .)
        final String artifactName = MavenSlice.EVENT_INFO.formatArtifactName(groupArtifact);

        final ArtifactEvent base = new ArtifactEvent(
            "maven",
            this.rname,
            owner == null || owner.isBlank() ? ArtifactEvent.DEF_OWNER : owner,
            artifactName,
            version,
            size,
            System.currentTimeMillis(),
            (Long) null  // No release date for uploads
        );
        final ArtifactEvent event = sha256 == null ? base : base.withChecksum(sha256);
        // Async path: queue for audit/metrics consumers (DbConsumer batches).
        this.events.ifPresent(queue -> queue.add(event));
        EcsLogger.debug("com.auto1.pantera.maven")
            .message("Added artifact event")
            .eventCategory("web")
            .eventAction("event_creation")
            .eventOutcome("success")
            .field("package.name", artifactName)
            .field("package.version", version)
            .field("package.size", size)
            .log();
        // Sync path: write the index row inline so the next group lookup
        // sees the artifact without waiting for the async batch.
        return this.syncIndex.recordSync(event);
    }

    /**
     * Record metric safely (only if metrics are enabled).
     * @param metric Metric recording action
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void recordMetric(final Runnable metric) {
        try {
            if (com.auto1.pantera.metrics.PanteraMetrics.isEnabled()) {
                metric.run();
            }
        } catch (final Exception ex) {
            EcsLogger.debug("com.auto1.pantera.maven")
                .message("Failed to record metric")
                .error(ex)
                .log();
        }
    }
}
