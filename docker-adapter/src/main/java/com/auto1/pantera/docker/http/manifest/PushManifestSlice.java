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
package com.auto1.pantera.docker.http.manifest;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.error.DeniedError;
import com.auto1.pantera.docker.http.DockerActionSlice;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.manifest.ManifestLayer;
import com.auto1.pantera.docker.misc.ImageTag;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.AuthzSlice;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ArtifactEvent;
import com.auto1.pantera.security.policy.Policy;

import java.security.Permission;
import java.util.Collection;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

public class PushManifestSlice extends DockerActionSlice {

    private final Queue<ArtifactEvent> queue;

    /** Synchronous artifact-index writer for read-after-write consistency. */
    private final com.auto1.pantera.index.SyncArtifactIndexer syncIndex;

    /**
     * Access policy, consulted for {@link DockerActions#OVERWRITE} when a
     * push would move an existing tag to a different manifest.
     */
    private final Policy<?> policy;

    public PushManifestSlice(Docker docker, Queue<ArtifactEvent> queue) {
        this(docker, queue, com.auto1.pantera.index.SyncArtifactIndexer.NOOP);
    }

    public PushManifestSlice(Docker docker, Queue<ArtifactEvent> queue,
        com.auto1.pantera.index.SyncArtifactIndexer syncIndex) {
        this(docker, queue, syncIndex, Policy.FREE);
    }

    /**
     * @param docker Docker repository
     * @param queue Artifact events queue, nullable
     * @param syncIndex Synchronous artifact-index writer
     * @param policy Access policy for the tag-overwrite check
     */
    public PushManifestSlice(Docker docker, Queue<ArtifactEvent> queue,
        com.auto1.pantera.index.SyncArtifactIndexer syncIndex, Policy<?> policy) {
        super(docker);
        this.queue = queue;
        this.syncIndex = syncIndex;
        this.policy = policy;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        final ManifestRequest request = ManifestRequest.from(line);
        final ManifestReference ref = request.reference();
        // Manifests are small JSON documents; buffering the body lets the
        // overwrite check compare digests before anything is written, and
        // guarantees the request body is consumed on every path.
        return body.asBytesFuture().thenCompose(bytes -> {
            if (!ImageTag.valid(ref.digest())) {
                return this.push(request, headers, bytes);
            }
            return this.docker.repo(request.name()).manifests().get(ref)
                .thenCompose(existing -> {
                    if (existing.isPresent()
                        && !existing.get().digest().string()
                            .equals(new Digest.Sha256(bytes).string())
                        && !this.mayOverwrite(headers, request.name())) {
                        return CompletableFuture.completedFuture(
                            this.overwriteDenied(request, headers)
                        );
                    }
                    return this.push(request, headers, bytes);
                });
        });
    }

    /**
     * Whether the authenticated user holds {@link DockerActions#OVERWRITE}
     * on the image. The identity is the LAST {@code pantera_login} value:
     * the one the authorization slice appended after authenticating, so a
     * client-supplied header of the same name cannot stand in for it.
     *
     * @param headers Request headers
     * @param image Image name as seen by {@link #permission(RequestLine)}
     * @return True when an existing tag may be moved
     */
    private boolean mayOverwrite(final Headers headers, final String image) {
        final List<String> logins = headers.values(AuthzSlice.LOGIN_HDR);
        final AuthUser user = logins.isEmpty() ? AuthUser.ANONYMOUS
            : new AuthUser(logins.get(logins.size() - 1), "docker");
        return this.policy.getPermissions(user).implies(
            new DockerRepositoryPermission(
                this.docker.registryName(), image, DockerActions.OVERWRITE.mask()
            )
        );
    }

    /**
     * 403 DENIED for a push that would move an existing tag without the
     * {@code overwrite} action.
     *
     * @param request Manifest request
     * @param headers Request headers
     * @return Response
     */
    private Response overwriteDenied(final ManifestRequest request, final Headers headers) {
        EcsLogger.warn("com.auto1.pantera.docker")
            .message("Docker tag overwrite denied: user lacks the 'overwrite' action")
            .eventCategory("authentication")
            .eventAction("docker_tag_overwrite")
            .eventOutcome("failure")
            .field("event.reason", "overwrite_not_permitted")
            .field("repository.name", this.docker.registryName())
            .field("user.name", new Login(headers).getValue())
            .field("container.image.name", request.name())
            .field("container.image.tag", request.reference().digest())
            .field("http.response.status_code", 403)
            .field("log.source", "application")
            .log();
        return ResponseBuilder.forbidden().jsonBody(new DeniedError().json()).build();
    }

    /**
     * Store the manifest and answer 201.
     *
     * @param request Manifest request
     * @param headers Request headers
     * @param bytes Manifest bytes
     * @return Response future
     */
    private CompletableFuture<Response> push(
        final ManifestRequest request, final Headers headers, final byte[] bytes
    ) {
        final ManifestReference ref = request.reference();
        return this.docker.repo(request.name())
            .manifests()
            .put(ref, new Content.From(bytes))
            .thenCompose(
                manifest -> {
                    final CompletableFuture<Long> sizeFuture;
                    if (queue != null && ImageTag.valid(ref.digest()) && manifest.isManifestList()) {
                        sizeFuture = resolveManifestListSize(
                            this.docker.repo(request.name()), manifest
                        );
                    } else if (queue != null && ImageTag.valid(ref.digest())) {
                        sizeFuture = CompletableFuture.completedFuture(
                            manifest.layers().stream().mapToLong(ManifestLayer::size).sum()
                        );
                    } else {
                        sizeFuture = CompletableFuture.completedFuture(0L);
                    }
                    return sizeFuture.thenCompose(size -> {
                        final CompletableFuture<Void> indexed;
                        if (ImageTag.valid(ref.digest())) {
                            final ArtifactEvent event = new ArtifactEvent(
                                "docker",
                                docker.registryName(),
                                new Login(headers).getValue(),
                                request.name(), ref.digest(),
                                size, System.currentTimeMillis(), null,
                                // Layout keys are relative to the adapter's
                                // SubStorage (RegistryRoot.V2), so the prefix
                                // must be restored to make the key resolvable
                                // from the repository root.
                                new com.auto1.pantera.asto.Key.From(
                                    com.auto1.pantera.docker.asto.RegistryRoot.V2,
                                    com.auto1.pantera.docker.asto.Layout.manifest(
                                        this.docker.resolveName(request.name()), ref
                                    )
                                ).string()
                            );
                            if (queue != null) {
                                queue.add(event);
                            }
                            indexed = this.syncIndex.recordSync(event);
                        } else {
                            indexed = CompletableFuture.completedFuture(null);
                        }
                        return indexed.thenApply(ignored ->
                            ResponseBuilder.created()
                                .header(new Location(String.format("/v2/%s/manifests/%s", request.name(), ref.digest())))
                                .header(new ContentLength("0"))
                                .header(new DigestHeader(manifest.digest()))
                                .build()
                        );
                    });
                }
            );
    }

    /**
     * Resolve total size of a manifest list by fetching child manifests
     * from storage and summing their layer sizes.
     *
     * @param repo Repository containing the child manifests
     * @param manifestList The manifest list
     * @return Future with total size in bytes
     */
    private static CompletableFuture<Long> resolveManifestListSize(
        final Repo repo, final Manifest manifestList
    ) {
        final Collection<Digest> children = manifestList.manifestListChildren();
        if (children.isEmpty()) {
            return CompletableFuture.completedFuture(0L);
        }
        CompletableFuture<Long> result = CompletableFuture.completedFuture(0L);
        for (final Digest child : children) {
            result = result.thenCompose(
                running -> repo.manifests()
                    .get(ManifestReference.from(child))
                    .thenApply(opt -> {
                        if (opt.isPresent() && !opt.get().isManifestList()) {
                            return running + opt.get().layers().stream()
                                .mapToLong(ManifestLayer::size).sum();
                        }
                        return running;
                    })
                    .exceptionally(ex -> running)
            );
        }
        return result;
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), ManifestRequest.from(line).name(), DockerActions.PUSH.mask()
        );
    }
}
