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
package com.auto1.pantera.docker.http.blobs;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.error.BlobUnknownError;
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.http.DockerActionSlice;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.rq.RequestLine;


import java.security.Permission;
import java.util.concurrent.CompletableFuture;

public class GetBlobsSlice extends DockerActionSlice {

    public GetBlobsSlice(Docker docker) {
        super(docker);
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        BlobsRequest request = BlobsRequest.from(line);
        if (!request.wellFormed()) {
            return body.discard().thenApply(ignored -> request.invalidDigest());
        }
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored ->
            this.docker.repo(request.name())
                .layers().get(request.digest())
                .thenCompose(
                    found -> found.map(
                        blob -> blob.content()
                            .thenCompose(
                                content -> content.size()
                                    .map(CompletableFuture::completedFuture)
                                    .orElseGet(blob::size)
                                    .thenApply(
                                        size -> ResponseBuilder.ok()
                                            .header(new DigestHeader(request.digest()))
                                            .header(ContentType.mime("application/octet-stream"))
                                            .body(new Content.From(size, content))
                                            .build()
                                    )
                            )
                    ).orElseGet(
                        () -> ResponseBuilder.notFound()
                            .jsonBody(new BlobUnknownError(request.digest()).json())
                            .completedFuture()
                    )
                )
                // A failed fetch is not a blob that does not exist: 5xx
                // with its cause (and the circuit-open marker), never the
                // 404 BLOB_UNKNOWN a client and a group take for "absent".
                .exceptionally(err -> new com.auto1.pantera.http.cache.UpstreamMiss(
                        "com.auto1.pantera.docker", this.docker.registryName(),
                        line.uri().getPath(), headers
                    ).failed(err, "Blob GET failed"))
        );
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), BlobsRequest.from(line).name(), DockerActions.PULL.mask()
        );
    }
}
