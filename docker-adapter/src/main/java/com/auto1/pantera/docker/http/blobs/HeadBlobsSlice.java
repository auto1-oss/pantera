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

import com.auto1.pantera.http.log.EcsLogger;

import java.security.Permission;
import java.util.concurrent.CompletableFuture;
import java.nio.ByteBuffer;

import io.reactivex.Flowable;

public class HeadBlobsSlice extends DockerActionSlice {
    public HeadBlobsSlice(Docker docker) {
        super(docker);
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        BlobsRequest request = BlobsRequest.from(line);
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // HEAD requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored ->
            this.docker.repo(request.name()).layers()
                .get(request.digest())
                .thenCompose(
                    found -> found.map(
                        blob -> blob.size().thenApply(
                            size -> {
                                Content head = new Content.From(size, Flowable.<ByteBuffer>empty());
                                return ResponseBuilder.ok()
                                    .header(new DigestHeader(blob.digest()))
                                    .header(ContentType.mime("application/octet-stream"))
                                    .body(head)
                                    .build();
                            }
                        )
                    ).orElseGet(
                        () -> ResponseBuilder.notFound()
                            .jsonBody(new BlobUnknownError(request.digest()).json())
                            .completedFuture()
                    )
                )
                .exceptionally(err -> {
                    EcsLogger.warn("com.auto1.pantera.docker")
                        .message("Blob HEAD failed with exception, returning 404")
                        .eventCategory("web")
                        .eventAction("blob_head")
                        .eventOutcome("failure")
                        .field("package.checksum", request.digest().string())
                        .error(err)
                        .log();
                    return ResponseBuilder.notFound()
                        .jsonBody(new BlobUnknownError(request.digest()).json())
                        .build();
                })
        );
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), BlobsRequest.from(line).name(), DockerActions.PULL.mask()
        );
    }
}
