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
import com.auto1.pantera.docker.error.DockerReferenceNotFoundException;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.manifest.ManifestLayer;
import com.auto1.pantera.docker.misc.ImageTag;
import com.auto1.pantera.docker.misc.Pagination;
import com.auto1.pantera.http.log.EcsLogger;
import com.google.common.base.Strings;

import javax.json.JsonException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
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
                            .thenApply(nothing -> manifest)
                    )
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

    @Override
    public CompletableFuture<Collection<String>> delete(final ManifestReference ref) {
        final Digest.FromString digest = new Digest.FromString(ref.digest());
        final CompletableFuture<Collection<String>> res;
        if (digest.valid()) {
            res = this.deleteDigest(digest);
        } else {
            res = this.deleteTag(ref);
        }
        return res;
    }

    /**
     * Deletes a tag (OCI distribution semantics): removes only that tag's
     * link. The manifest stays pullable by digest, and other tags that
     * reference the same digest are untouched.
     *
     * @param ref Tag reference.
     * @return The deleted tag; fails when the tag does not exist.
     */
    private CompletableFuture<Collection<String>> deleteTag(final ManifestReference ref) {
        return this.readLink(ref).thenCompose(
            digestOpt -> digestOpt.map(
                digest -> this.storage.delete(Layout.manifest(this.name, ref))
                    .<Collection<String>>thenApply(
                        nothing -> {
                            this.logManifestDelete(ref, digest, 1);
                            return List.of(ref.digest());
                        }
                    )
            ).orElseGet(() -> this.notFound(ref))
        );
    }

    /**
     * Deletes a manifest by digest: removes the by-digest link and untags
     * every tag of this image whose link points at {@code digest}, so the
     * manifest is no longer reachable by any reference. The digest counts as
     * present when either its by-digest link or at least one tag link exists.
     *
     * @param digest Manifest digest.
     * @return Tags removed (possibly empty); fails when nothing references
     *         the digest.
     */
    private CompletableFuture<Collection<String>> deleteDigest(final Digest digest) {
        final ManifestReference byDigest = ManifestReference.from(digest);
        final Key digestKey = Layout.manifest(this.name, byDigest);
        return this.tagsPointingAt(digest).thenCompose(
            tags -> this.storage.exists(digestKey).thenCompose(
                exists -> {
                    if (!exists && tags.isEmpty()) {
                        return this.notFound(byDigest);
                    }
                    final List<CompletableFuture<Void>> removals = new ArrayList<>(tags.size() + 1);
                    if (exists) {
                        removals.add(this.storage.delete(digestKey));
                    }
                    for (final String tag : tags) {
                        removals.add(
                            this.storage.delete(
                                Layout.manifest(this.name, ManifestReference.fromTag(tag))
                            )
                        );
                    }
                    return CompletableFuture.allOf(removals.toArray(new CompletableFuture<?>[0]))
                        .thenApply(
                            nothing -> {
                                this.logManifestDelete(byDigest, digest, tags.size());
                                return tags;
                            }
                        );
                }
            )
        );
    }

    /**
     * Tags of this image whose link points at {@code digest}.
     *
     * @param digest Manifest digest.
     * @return Matching tag names, sorted.
     */
    private CompletableFuture<Collection<String>> tagsPointingAt(final Digest digest) {
        final Key root = Layout.tags(this.name);
        return this.storage.list(root).thenCompose(
            keys -> {
                final List<CompletableFuture<Optional<String>>> checks =
                    new Children(root, keys).names().stream()
                        .filter(ImageTag::valid)
                        .map(
                            tag -> this.readLink(ManifestReference.fromTag(tag)).thenApply(
                                link -> link
                                    .filter(found -> found.string().equals(digest.string()))
                                    .map(found -> tag)
                            )
                        )
                        .toList();
                return CompletableFuture.allOf(checks.toArray(new CompletableFuture<?>[0]))
                    .<Collection<String>>thenApply(
                        // Every check is complete here: join() does not block.
                        nothing -> checks.stream()
                            .map(CompletableFuture::join)
                            .flatMap(Optional::stream)
                            .toList()
                    );
            }
        );
    }

    /**
     * Failed future for a reference that resolves to nothing.
     *
     * @param ref Reference requested for deletion.
     * @return Future failed with {@link DockerReferenceNotFoundException}.
     */
    private CompletableFuture<Collection<String>> notFound(final ManifestReference ref) {
        EcsLogger.debug("com.auto1.pantera.docker")
            .message("Manifest delete requested for a reference that does not exist")
            .eventCategory("web")
            .eventAction("manifest_delete")
            .eventOutcome("failure")
            .field("repository.name", this.name)
            .field("container.image.hash.all", ref.digest())
            .field("log.source", "application")
            .log();
        return CompletableFuture.failedFuture(
            new DockerReferenceNotFoundException(
                String.format("manifest not found: %s", ref.digest())
            )
        );
    }

    /**
     * Logs the manifest-delete state transition.
     *
     * @param ref Reference that was deleted.
     * @param digest Digest the reference resolved to.
     * @param untagged Number of tags removed.
     */
    private void logManifestDelete(
        final ManifestReference ref, final Digest digest, final int untagged
    ) {
        EcsLogger.info("com.auto1.pantera.docker")
            .message(String.format("Manifest reference deleted (%d tag(s) removed)", untagged))
            .eventCategory("web")
            .eventAction("manifest_delete")
            .eventOutcome("success")
            .field("repository.name", this.name)
            .field("container.image.hash.all", ref.digest())
            .field("package.checksum", digest.string())
            .field("log.source", "application")
            .log();
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
}
