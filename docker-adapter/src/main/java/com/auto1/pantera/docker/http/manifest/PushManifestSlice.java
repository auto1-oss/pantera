/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http.manifest;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.Repo;
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.http.DockerActionSlice;
import com.auto1.pantera.docker.manifest.Manifest;
import com.auto1.pantera.docker.manifest.ManifestLayer;
import com.auto1.pantera.docker.misc.ImageTag;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.Location;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.scheduling.ArtifactEvent;

import java.security.Permission;
import java.util.Collection;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

public class PushManifestSlice extends DockerActionSlice {

    private final Queue<ArtifactEvent> queue;

    public PushManifestSlice(Docker docker, Queue<ArtifactEvent> queue) {
        super(docker);
        this.queue = queue;
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        ManifestRequest request = ManifestRequest.from(line);
        final ManifestReference ref = request.reference();
        return this.docker.repo(request.name())
            .manifests()
            .put(ref, new Content.From(body))
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
                    return sizeFuture.thenApply(size -> {
                        if (queue != null && ImageTag.valid(ref.digest())) {
                            queue.add(
                                new ArtifactEvent(
                                    "docker",
                                    docker.registryName(),
                                    new Login(headers).getValue(),
                                    request.name(), ref.digest(),
                                    size
                                )
                            );
                        }
                        return ResponseBuilder.created()
                            .header(new Location(String.format("/v2/%s/manifests/%s", request.name(), ref.digest())))
                            .header(new ContentLength("0"))
                            .header(new DigestHeader(manifest.digest()))
                            .build();
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
