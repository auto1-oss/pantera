/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http.upload;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.error.UploadUnknownError;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.rq.RequestLine;

import java.security.Permission;
import java.util.concurrent.CompletableFuture;

public class DeleteUploadSlice extends UploadSlice {

    public DeleteUploadSlice(Docker docker) {
        super(docker);
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), UploadRequest.from(line).name(), DockerActions.PULL.mask()
        );
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        UploadRequest request = UploadRequest.from(line);
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored ->
            this.docker.repo(request.name())
                .uploads()
                .get(request.uuid())
                .thenCompose(
                    x -> x.map(
                        upload -> upload.cancel()
                            .thenApply(
                                offset -> ResponseBuilder.ok()
                                    .header("Docker-Upload-UUID", request.uuid())
                                    .build()
                            )
                    ).orElse(
                        ResponseBuilder.notFound()
                            .jsonBody(new UploadUnknownError(request.uuid()).json())
                            .completedFuture()
                    )
                )
        );
    }
}
