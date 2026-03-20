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
package com.auto1.pantera.docker.http.upload;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.rq.RequestLine;

import java.security.Permission;
import java.util.concurrent.CompletableFuture;

public class PostUploadSlice extends UploadSlice {

    public PostUploadSlice(Docker docker) {
        super(docker);
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), UploadRequest.from(line).name(), DockerActions.PUSH.mask()
        );
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        UploadRequest request = UploadRequest.from(line);
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            if (request.mount().isPresent() && request.from().isPresent()) {
                return mount(request.mount().get(), request.from().get(), request.name());
            }
            return startUpload(request.name());
        });
    }

    /**
     * Mounts specified blob from source repository to target repository.
     *
     * @param digest Blob digest.
     * @param source Source repository name.
     * @param target Target repository name.
     * @return HTTP response.
     */
    private CompletableFuture<Response> mount(
        Digest digest, String source, String target
    ) {
        final int slash = target.indexOf('/');
        if (slash > 0) {
            final String expected = target.substring(0, slash + 1);
            if (!source.startsWith(expected)) {
                return this.startUpload(target);
            }
        }
        try {
            return this.docker.repo(source)
                .layers()
                .get(digest)
                .thenCompose(
                    opt -> opt.map(
                        src -> this.docker.repo(target)
                            .layers()
                            .mount(src)
                            .thenCompose(v -> createdResponse(target, digest))
                    ).orElseGet(
                        () -> this.startUpload(target)
                    )
                );
        } catch (final IllegalArgumentException ex) {
            // Source repository belongs to a different prefix; fall back to regular upload.
            return this.startUpload(target);
        }
    }

    /**
     * Starts new upload in specified repository.
     *
     * @param name Repository name.
     * @return HTTP response.
     */
    private CompletableFuture<Response> startUpload(String name) {
        return this.docker.repo(name)
            .uploads()
            .start()
            .thenCompose(upload -> acceptedResponse(name, upload.uuid(), 0));
    }
}
