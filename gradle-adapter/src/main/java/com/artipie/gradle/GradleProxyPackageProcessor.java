/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle;

import com.artipie.asto.Key;
import com.artipie.asto.Meta;
import com.artipie.asto.Storage;
import com.artipie.asto.ext.KeyLastPart;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.scheduling.ProxyArtifactEvent;
import com.artipie.scheduling.QuartzJob;
import com.jcabi.log.Logger;
import java.util.Collection;
import java.util.Queue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.quartz.JobExecutionContext;

/**
 * Processes artifacts uploaded by Gradle proxy and adds info to artifacts metadata events queue.
 * Gradle uses Maven repository format, so we parse artifact coordinates from the path structure.
 * 
 * @since 1.0
 */
public final class GradleProxyPackageProcessor extends QuartzJob {

    /**
     * Repository type.
     */
    private static final String REPO_TYPE = "gradle-proxy";

    /**
     * Pattern to match Gradle/Maven artifact files (jar, aar, war, etc).
     * Matches: groupId/artifactId/version/artifactId-version[-classifier].extension
     */
    private static final Pattern ARTIFACT_PATTERN = 
        Pattern.compile(".*?/([^/]+)/([^/]+)/([^/]+)-\\2(?:-([^.]+))?\\.([^.]+)$");

    /**
     * Artifact events queue.
     */
    private Queue<ArtifactEvent> events;

    /**
     * Queue with packages and owner names.
     */
    private Queue<ProxyArtifactEvent> packages;

    /**
     * Repository storage.
     */
    private Storage asto;

    @Override
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.CognitiveComplexity", "PMD.EmptyControlStatement"})
    public void execute(final JobExecutionContext context) {
        if (this.asto == null || this.packages == null || this.events == null) {
            Logger.warn(this, "Gradle proxy processor not initialized properly - stopping job");
            super.stopJob(context);
        } else {
            Logger.debug(this, "Gradle proxy processor running, queue size: %d", this.packages.size());
            while (!this.packages.isEmpty()) {
                final ProxyArtifactEvent event = this.packages.poll();
                if (event != null) {
                    final Key key = event.artifactKey();
                    Logger.debug(this, "Processing Gradle proxy event for key: %s", key.string());
                    try {
                        // Find the actual artifact file (jar, aar, war, etc)
                        final Collection<Key> keys = this.asto.list(key).join();
                        Logger.debug(this, "Found %d keys under %s", keys.size(), key.string());
                        final Key artifactFile = findArtifactFile(keys);
                        
                        if (artifactFile == null) {
                            Logger.warn(
                                this,
                                "No artifact file found for %s (found %d keys), skipping",
                                key.string(),
                                keys.size()
                            );
                            continue;
                        }

                        // Parse artifact coordinates from path
                        final ArtifactCoordinates coords = parseCoordinates(artifactFile);
                        if (coords == null) {
                            Logger.debug(
                                this,
                                "Could not parse coordinates from %s, skipping",
                                artifactFile.string()
                            );
                            continue;
                        }

                        final String owner = event.ownerLogin();
                        final long created = System.currentTimeMillis();
                        final Long release = event.releaseMillis().orElse(null);
                        
                        this.events.add(
                            new ArtifactEvent(
                                GradleProxyPackageProcessor.REPO_TYPE,
                                event.repoName(),
                                owner == null || owner.isBlank()
                                    ? ArtifactEvent.DEF_OWNER
                                    : owner,
                                coords.artifactName(),
                                coords.version(),
                                this.asto.metadata(artifactFile)
                                    .thenApply(meta -> meta.read(Meta.OP_SIZE)).join().get(),
                                created,
                                release
                            )
                        );
                        
                        Logger.info(
                            this,
                            "Recorded Gradle proxy artifact %s:%s (repo=%s, release=%s)",
                            coords.artifactName(),
                            coords.version(),
                            event.repoName(),
                            release == null ? "unknown" : java.time.Instant.ofEpochMilli(release).toString()
                        );
                        
                        // Remove all duplicate events from queue
                        while (this.packages.remove(event)) {
                            // Continue removing duplicates
                        }
                        
                    } catch (final Exception err) {
                        Logger.error(
                            this,
                            "Failed to process gradle proxy package %s: %s",
                            key.string(),
                            err.getMessage()
                        );
                    }
                }
            }
        }
    }

    /**
     * Find the main artifact file (jar, aar, war) from a list of keys.
     * Excludes POM files, metadata, checksums, and signatures.
     * 
     * @param keys Collection of keys
     * @return Main artifact key or null if not found
     */
    private static Key findArtifactFile(final Collection<Key> keys) {
        return keys.stream()
            .filter(key -> {
                final String name = new KeyLastPart(key).get().toLowerCase(java.util.Locale.ROOT);
                return (name.endsWith(".jar") || name.endsWith(".aar") || name.endsWith(".war"))
                    && !name.endsWith(".pom")
                    && !name.endsWith(".md5")
                    && !name.endsWith(".sha1")
                    && !name.endsWith(".sha256")
                    && !name.endsWith(".sha512")
                    && !name.endsWith(".asc");
            })
            .findFirst()
            .orElse(null);
    }

    /**
     * Parse artifact coordinates from Maven/Gradle path structure.
     * Path format: groupId/artifactId/version/artifactId-version[-classifier].extension
     * 
     * @param key Artifact key
     * @return Artifact coordinates or null if parsing fails
     */
    private static ArtifactCoordinates parseCoordinates(final Key key) {
        final String path = key.string();
        final Matcher matcher = ARTIFACT_PATTERN.matcher(path);
        
        if (!matcher.matches()) {
            return null;
        }

        final String artifactId = matcher.group(1);
        final String version = matcher.group(2);
        
        // Extract groupId from path (everything before artifactId/version)
        final String prefix = path.substring(0, path.indexOf("/" + artifactId + "/" + version));
        final String groupId = prefix.replace('/', '.');
        
        final String artifactName = groupId.isEmpty() ? artifactId : groupId + ":" + artifactId;
        
        return new ArtifactCoordinates(artifactName, version);
    }

    /**
     * Setter for events queue.
     * @param queue Events queue
     */
    public void setEvents(final Queue<ArtifactEvent> queue) {
        this.events = queue;
    }

    /**
     * Packages queue setter.
     * @param queue Queue with package key and owner
     */
    public void setPackages(final Queue<ProxyArtifactEvent> queue) {
        this.packages = queue;
    }

    /**
     * Repository storage setter.
     * @param storage Storage
     */
    public void setStorage(final Storage storage) {
        this.asto = storage;
    }

    /**
     * Artifact coordinates holder.
     */
    private static final class ArtifactCoordinates {
        private final String artifactName;
        private final String version;

        ArtifactCoordinates(final String artifactName, final String version) {
            this.artifactName = artifactName;
            this.version = version;
        }

        String artifactName() {
            return this.artifactName;
        }

        String version() {
            return this.version;
        }
    }
}
