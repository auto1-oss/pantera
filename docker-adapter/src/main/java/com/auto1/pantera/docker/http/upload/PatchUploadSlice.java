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
import com.auto1.pantera.http.slice.ContentWithSize;

import java.security.Permission;
import java.util.concurrent.CompletableFuture;

public class PatchUploadSlice extends UploadSlice {

    public PatchUploadSlice(Docker docker) {
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
        return this.docker.repo(request.name())
            .uploads()
            .get(request.uuid())
            .thenCompose(
                found -> found.map(
                    upload -> upload
                        .append(new ContentWithSize(body, headers))
                        .thenCompose(offset -> acceptedResponse(request.name(), request.uuid(), offset))
                ).orElseGet(
                    () -> ResponseBuilder.notFound()
                        .jsonBody(new UploadUnknownError(request.uuid()).json())
                        .completedFuture()
                )
            );
    }
}
