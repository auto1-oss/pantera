/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.http.blobs;

import com.artipie.asto.Content;
import com.artipie.docker.Docker;
import com.artipie.docker.error.BlobUnknownError;
import com.artipie.docker.http.DigestHeader;
import com.artipie.docker.http.DockerActionSlice;
import com.artipie.docker.perms.DockerActions;
import com.artipie.docker.perms.DockerRepositoryPermission;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.headers.ContentType;
import com.artipie.http.rq.RequestLine;

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
        );
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), BlobsRequest.from(line).name(), DockerActions.PULL.mask()
        );
    }
}
