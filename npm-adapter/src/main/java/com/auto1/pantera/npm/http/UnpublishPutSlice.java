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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.PanteraException;
import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.npm.PackageNameFromUrl;
import com.auto1.pantera.npm.PerVersionLayout;
import com.auto1.pantera.npm.misc.DateTimeNowStr;
import com.auto1.pantera.npm.misc.DescSortedVersions;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.google.common.collect.Sets;

import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonPatchBuilder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.regex.Pattern;

/**
 * Slice to handle `npm unpublish package@0.0.0` command requests.
 * It unpublishes a single version of package when multiple
 * versions are published.
 *
 * <p>For packages published through the per-version layout, the removed
 * version's {@code .versions/<v>.json} file is genuinely deleted (so it
 * cannot be re-added by the next {@code generateMetaJson} call) and any
 * dist-tag pointing at it — including {@code latest} — is dropped from the
 * sidecar. Falls back to a legacy {@code meta.json} patch for packages that
 * predate the per-version layout.</p>
 */
final class UnpublishPutSlice implements Slice {
    /**
     * Pattern for `referer` header value.
     */
    public static final Pattern HEADER = Pattern.compile("unpublish.*");

    /**
     * Abstract Storage.
     */
    private final Storage asto;

    /**
     * Artifact events queue.
     */
    private final Optional<Queue<ArtifactEvent>> events;

    /**
     * Repository name.
     */
    private final String rname;

    /**
     * Ctor.
     *
     * @param storage Abstract storage
     * @param events Events queue
     * @param rname Repository name
     */
    UnpublishPutSlice(final Storage storage, final Optional<Queue<ArtifactEvent>> events,
        final String rname) {
        this.asto = storage;
        this.events = events;
        this.rname = rname;
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line,
        final Headers headers,
        final Content publisher
    ) {
        final String pkg = new PackageNameFromUrl(
            RequestLine.from(line.toString().replaceFirst("/-rev/[^\\s]+", ""))
        ).value();
        final Key packageKey = new Key.From(pkg);
        final PerVersionLayout layout = new PerVersionLayout(this.asto);
        return layout.hasVersions(packageKey).thenCompose(
            hasVersions -> {
                if (hasVersions) {
                    return this.unpublishFromLayout(layout, packageKey, pkg, publisher);
                }
                return this.unpublishLegacy(pkg, publisher);
            }
        ).toCompletableFuture();
    }

    /**
     * Single-version unpublish for packages on the per-version layout.
     *
     * @param layout Per-version layout
     * @param packageKey Package key
     * @param pkg Package name (for the ArtifactEvent)
     * @param publisher Request body
     * @return Completion stage with the response
     */
    private CompletableFuture<Response> unpublishFromLayout(
        final PerVersionLayout layout, final Key packageKey, final String pkg,
        final Content publisher
    ) {
        return new Content.From(publisher).asJsonObjectFuture()
            .thenCompose(update -> this.unpublishVersion(layout, packageKey, update))
            .thenApply(
                ver -> {
                    this.emitEvent(pkg, ver);
                    return ResponseBuilder.ok().build();
                }
            ).toCompletableFuture();
    }

    /**
     * Resolve which version the client removed (by symmetric difference
     * against the currently published versions), delete its per-version file,
     * and drop any dist-tag that pointed at it.
     *
     * @param layout Per-version layout
     * @param packageKey Package key
     * @param update Client's updated packument (with the version removed)
     * @return Completion stage with the removed version
     */
    private CompletionStage<String> unpublishVersion(
        final PerVersionLayout layout, final Key packageKey, final JsonObject update
    ) {
        return layout.listVersions(packageKey).thenCompose(
            existing -> {
                final String removed = UnpublishPutSlice.versionToRemove(existing, update);
                return layout.deleteVersion(packageKey, removed)
                    .thenCompose(ignored -> layout.removeTagsPointingAt(packageKey, removed))
                    .thenApply(ignored -> removed);
            }
        );
    }

    /**
     * Legacy fallback for packages published before the per-version layout
     * existed: patch {@code meta.json} directly.
     *
     * @param pkg Package name
     * @param publisher Request body
     * @return Completion stage with the response
     */
    private CompletableFuture<Response> unpublishLegacy(final String pkg, final Content publisher) {
        final Key key = new Key.From(pkg, "meta.json");
        return this.asto.exists(key).thenCompose(
            exists -> {
                if (exists) {
                    return new Content.From(publisher).asJsonObjectFuture()
                        .thenCompose(update -> this.updateMeta(update, key))
                        .thenApply(
                            ver -> {
                                this.emitEvent(pkg, ver);
                                return ResponseBuilder.ok().build();
                            }
                        );
                }
                // Consume request body to prevent Vert.x request leak
                return new Content.From(publisher).asBytesFuture().thenApply(ignored ->
                    ResponseBuilder.notFound().build()
                );
            }
        );
    }

    /**
     * Emit the unpublish {@link ArtifactEvent}, if an events queue is wired.
     *
     * @param pkg Package name
     * @param version Removed version
     */
    private void emitEvent(final String pkg, final String version) {
        this.events.ifPresent(
            queue -> queue.add( // ok: unbounded ConcurrentLinkedDeque (ArtifactEvent queue)
                new ArtifactEvent(UploadSlice.REPO_TYPE, this.rname, pkg, version)
            )
        );
    }

    /**
     * Compare two meta files and remove from the meta file of storage info about
     * version that does not exist in another meta file.
     * @param update Meta json file (usually this file is received from body)
     * @param meta Meta json key in storage
     * @return Removed version
     */
    private CompletionStage<String> updateMeta(
        final JsonObject update, final Key meta
    ) {
        return this.asto.value(meta)
            .thenCompose(Content::asJsonObjectFuture).thenCompose(
                source -> {
                    final JsonPatchBuilder patch = Json.createPatchBuilder();
                    final String diff = UnpublishPutSlice.versionToRemove(
                        source.getJsonObject("versions").keySet(), update
                    );
                    patch.remove(String.format("/versions/%s", diff));
                    patch.remove(String.format("/time/%s", diff));
                    if (source.getJsonObject("dist-tags").containsKey(diff)) {
                        patch.remove(String.format("/dist-tags/%s", diff));
                    }
                    // Get latest STABLE version (exclude prereleases like alpha, beta, rc)
                    final String latest = new DescSortedVersions(
                        update.getJsonObject("versions"),
                        true  // excludePrereleases = true
                    ).value().get(0);
                    patch.add("/dist-tags/latest", latest);
                    patch.add("/time/modified", new DateTimeNowStr().value());
                    return this.asto.save(
                        meta,
                        new Content.From(
                            patch.build().apply(source).toString().getBytes(StandardCharsets.UTF_8)
                        )
                    ).thenApply(nothing -> diff);
                }
            );
    }

    /**
     * Compare the currently existing version set against the client's updated
     * packument and identify which single version was removed.
     * @param existing Currently existing version identifiers
     * @param update Meta json file (usually this file is received from body)
     * @return Version to unpublish.
     */
    private static String versionToRemove(final Set<String> existing, final JsonObject update) {
        final Set<String> diff = Sets.symmetricDifference(
            existing,
            update.getJsonObject("versions").keySet()
        );
        if (diff.size() != 1) {
            throw new PanteraException(
                String.format(
                    "Failed to unpublish single version. Should be one version, but were `%s`",
                    diff.toString()
                )
            );
        }
        return diff.iterator().next();
    }
}
