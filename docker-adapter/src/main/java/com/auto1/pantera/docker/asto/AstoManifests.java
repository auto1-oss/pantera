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
package com.auto1.pantera.docker.asto;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Manifests;
import com.auto1.pantera.docker.Tags;
import com.auto1.pantera.docker.error.InvalidManifestException;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.manifest.ManifestLayer;
import com.auto1.pantera.docker.manifest.ReferrerDescriptor;
import com.auto1.pantera.docker.manifest.Referrers;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.log.EcsLogger;
import com.google.common.base.Strings;

import javax.json.JsonException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Asto implementation of {@link Manifests}.
 */
public final class AstoManifests implements Manifests {

    /**
     * Asto storage.
     */
    private final Storage storage;

    /**
     * Blobs storage.
     */
    private final Blobs blobs;

    /**
     * Repository name.
     */
    private final String name;

    /**
     * @param asto Asto storage
     * @param blobs Blobs storage.
     * @param name Repository name
     */
    public AstoManifests(Storage asto, Blobs blobs, String name) {
        this.storage = asto;
        this.blobs = blobs;
        this.name = name;
    }

    @Override
    public CompletableFuture<Manifest> put(ManifestReference ref, Content content) {
        return content.asBytesFuture()
            .thenCompose(
                bytes -> this.blobs.put(new TrustedBlobSource(bytes))
                    .thenApply(digest -> new Manifest(digest, bytes))
                    .thenCompose(
                        manifest -> this.validate(manifest)
                            .thenCompose(nothing -> this.addManifestLinks(ref, manifest.digest()))
                            .thenCompose(nothing -> this.indexReferrer(manifest))
                            .thenApply(nothing -> manifest)
                    )
            );
    }

    @Override
    public CompletableFuture<Manifest> putUnchecked(ManifestReference ref, Content content) {
        return content.asBytesFuture()
            .thenCompose(
                bytes -> this.blobs.put(new TrustedBlobSource(bytes))
                    .thenApply(digest -> new Manifest(digest, bytes))
                    .thenCompose(
                        manifest -> this.addManifestLinks(ref, manifest.digest())
                            .thenCompose(nothing -> this.indexReferrer(manifest))
                            .thenApply(nothing -> manifest)
                    )
            );
    }

    @Override
    public CompletableFuture<Referrers> referrers(final Digest subject, final Optional<String> artifactType) {
        final Key root = Layout.referrersRoot(this.name, subject);
        return this.storage.list(root)
            .thenCompose(this::readDescriptors)
            .thenApply(
                descriptors -> {
                    final List<ReferrerDescriptor> filtered = artifactType
                        .map(type -> filterByArtifactType(descriptors, type))
                        .orElse(descriptors);
                    EcsLogger.info("com.auto1.pantera.docker")
                        .message("Referrers listing served (count=" + filtered.size() + ")")
                        .eventCategory("web")
                        .eventAction("referrers_serve")
                        .eventOutcome("success")
                        .field("repository.name", this.name)
                        .field("container.image.hash.all", subject.string())
                        .field("log.source", "application")
                        .log();
                    return new Referrers(filtered);
                }
            );
    }

    @Override
    public CompletableFuture<Optional<Manifest>> get(final ManifestReference ref) {
        EcsLogger.debug("com.auto1.pantera.docker")
            .message("AstoManifests.get() called")
            .eventCategory("web")
            .eventAction("manifest_get")
            .field("container.image.hash.all", ref.digest())
            .field("log.source", "application")
            .log();
        return this.readLink(ref).thenCompose(
            digestOpt -> digestOpt.map(
                digest -> {
                    EcsLogger.debug("com.auto1.pantera.docker")
                        .message("Found link for manifest reference")
                        .eventCategory("web")
                        .eventAction("manifest_get")
                        .field("container.image.hash.all", ref.digest())
                        .field("package.checksum", digest.string())
                        .field("log.source", "application")
                        .log();
                    return this.blobs.blob(digest)
                        .thenCompose(
                            blobOpt -> blobOpt
                                .map(
                                    blob -> blob.content()
                                        .thenCompose(Content::asBytesFuture)
                                        .thenApply(bytes -> {
                                            EcsLogger.info("com.auto1.pantera.docker")
                                                .message("Creating Manifest from bytes")
                                                .eventCategory("web")
                                                .eventAction("manifest_get")
                                                .eventOutcome("success")
                                                .field("package.checksum", digest.string())
                                                .field("package.size", bytes.length)
                                                .field("log.source", "application")
                                                .log();
                                            return Optional.of(new Manifest(blob.digest(), bytes));
                                        })
                                )
                                .orElseGet(() -> {
                                    EcsLogger.warn("com.auto1.pantera.docker")
                                        .message("Blob not found for digest")
                                        .eventCategory("web")
                                        .eventAction("manifest_get")
                                        .eventOutcome("failure")
                                        .field("package.checksum", digest.string())
                                        .field("log.source", "application")
                                        .log();
                                    return CompletableFuture.completedFuture(Optional.empty());
                                })
                        );
                }
            ).orElseGet(() -> {
                EcsLogger.warn("com.auto1.pantera.docker")
                    .message("No link found for manifest reference")
                    .eventCategory("web")
                    .eventAction("manifest_get")
                    .eventOutcome("failure")
                    .field("container.image.hash.all", ref.digest())
                    .field("log.source", "application")
                    .log();
                return CompletableFuture.completedFuture(Optional.empty());
            })
        );
    }

    @Override
    public CompletableFuture<Tags> tags(Pagination pagination) {
        final Key root = Layout.tags(this.name);
        return this.storage.list(root).thenApply(
            keys -> new AstoTags(this.name, root, keys, pagination)
        );
    }

    /**
     * Validates manifest by checking all referenced blobs exist.
     *
     * @param manifest Manifest.
     * @return Validation completion.
     */
    private CompletionStage<Void> validate(final Manifest manifest) {
        // Check if this is a manifest list (multi-platform)
        boolean isManifestList = manifest.isManifestList();

        final Stream<Digest> digests;
        if (isManifestList) {
            // Manifest lists don't have config or layers, skip validation
            digests = Stream.empty();
        } else {
            // Regular manifests have config and layers
            try {
                digests = Stream.concat(
                    Stream.of(manifest.config()),
                    manifest.layers().stream()
                        .filter(layer -> layer.urls().isEmpty())
                        .map(ManifestLayer::digest)
                );
            } catch (final JsonException ex) {
                throw new InvalidManifestException(
                    String.format("Failed to parse manifest: %s", ex.getMessage()),
                    ex
                );
            }
        }
        return CompletableFuture.allOf(
            Stream.concat(
                digests.map(
                    digest -> this.blobs.blob(digest)
                        .thenCompose(
                            opt -> {
                                if (opt.isEmpty()) {
                                    throw new InvalidManifestException("Blob does not exist: " + digest);
                                }
                                return CompletableFuture.allOf();
                            }
                        ).toCompletableFuture()
                ),
                Stream.of(
                    CompletableFuture.runAsync(
                        () -> {
                            if(Strings.isNullOrEmpty(manifest.mediaType())){
                                throw new InvalidManifestException("Required field `mediaType` is empty");
                            }
                        }
                    )
                )
            ).toArray(CompletableFuture[]::new)
        );
    }

    /**
     * Adds links to manifest blob by reference and by digest.
     *
     * @param ref Manifest reference.
     * @param digest Blob digest.
     * @return Signal that links are added.
     */
    private CompletableFuture<Void> addManifestLinks(final ManifestReference ref, final Digest digest) {
        return CompletableFuture.allOf(
            this.addLink(ManifestReference.from(digest), digest),
            this.addLink(ref, digest)
        );
    }

    /**
     * Puts link to blob to manifest reference path.
     *
     * @param ref Manifest reference.
     * @param digest Blob digest.
     * @return Link key.
     */
    private CompletableFuture<Void> addLink(final ManifestReference ref, final Digest digest) {
        return this.storage.save(
            Layout.manifest(this.name, ref),
            new Content.From(digest.string().getBytes(StandardCharsets.US_ASCII))
        ).toCompletableFuture();
    }

    /**
     * Reads link to blob by manifest reference.
     *
     * @param ref Manifest reference.
     * @return Blob digest, empty if no link found.
     */
    private CompletableFuture<Optional<Digest>> readLink(final ManifestReference ref) {
        final Key key = Layout.manifest(this.name, ref);
        return this.storage.exists(key).thenCompose(
            exists -> {
                if (exists) {
                    return this.storage.value(key)
                        .thenCompose(Content::asStringFuture)
                        .thenApply(val -> Optional.of(new Digest.FromString(val)));
                }
                return CompletableFuture.completedFuture(Optional.empty());
            }
        );
    }

    /**
     * Indexes {@code manifest} against its OCI 1.1 {@code subject}, if
     * present, so {@code GET .../referrers/<subject-digest>} can find it.
     * A no-op for ordinary manifests that carry no {@code subject}.
     *
     * <p>Not gated on {@link com.auto1.pantera.docker.misc.ImageTag#valid} —
     * referrer artifacts (signatures, SBOMs) are pushed by digest, never by
     * tag, unlike the DB-index gate in {@code PushManifestSlice}.
     *
     * @param manifest Manifest just written to blob storage.
     * @return Signal that indexing completed (or that there was nothing to index).
     */
    private CompletableFuture<Void> indexReferrer(final Manifest manifest) {
        return manifest.subject()
            .map(subject -> this.writeReferrerEntry(subject, manifest))
            .orElseGet(() -> CompletableFuture.completedFuture(null));
    }

    /**
     * Writes the referrer descriptor entry and logs the state transition.
     *
     * @param subject Subject digest the manifest refers to.
     * @param manifest Referring manifest.
     * @return Signal that the entry is written.
     */
    private CompletableFuture<Void> writeReferrerEntry(final Digest subject, final Manifest manifest) {
        return this.storage.save(
            Layout.referrer(this.name, subject, manifest.digest()),
            new Content.From(ReferrerDescriptor.of(manifest).toBytes())
        ).toCompletableFuture().thenRun(
            () -> EcsLogger.info("com.auto1.pantera.docker")
                .message("Referrers index entry written")
                .eventCategory("web")
                .eventAction("referrers_index_write")
                .eventOutcome("success")
                .field("repository.name", this.name)
                .field("container.image.hash.all", subject.string())
                .field("package.checksum", manifest.digest().string())
                .field("log.source", "application")
                .log()
        );
    }

    /**
     * Reads every referrer descriptor found under a listed set of keys.
     *
     * @param keys Referrer descriptor keys (from {@link Layout#referrersRoot}).
     * @return Parsed descriptors, in no particular order.
     */
    private CompletableFuture<List<ReferrerDescriptor>> readDescriptors(final Collection<Key> keys) {
        final List<CompletableFuture<ReferrerDescriptor>> futures = keys.stream()
            .map(
                key -> this.storage.value(key)
                    .thenCompose(Content::asBytesFuture)
                    .thenApply(ReferrerDescriptor::fromBytes)
                    .toCompletableFuture()
            )
            .collect(Collectors.toList());
        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
            .thenApply(ignored -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }

    /**
     * Filters descriptors down to those matching the requested artifact type.
     *
     * @param descriptors Unfiltered descriptors.
     * @param artifactType Requested {@code ?artifactType=} value.
     * @return Matching descriptors.
     */
    private static List<ReferrerDescriptor> filterByArtifactType(
        final List<ReferrerDescriptor> descriptors, final String artifactType
    ) {
        return descriptors.stream()
            .filter(descriptor -> descriptor.artifactType().filter(artifactType::equals).isPresent())
            .collect(Collectors.toList());
    }
}
