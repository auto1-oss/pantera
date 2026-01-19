/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.http.manifest;

import com.artipie.asto.Content;
import com.artipie.docker.Docker;
import com.artipie.docker.error.ManifestError;
import com.artipie.docker.http.DigestHeader;
import com.artipie.docker.http.DockerActionSlice;
import com.artipie.docker.perms.DockerActions;
import com.artipie.docker.perms.DockerRepositoryPermission;
import com.artipie.http.Headers;
import com.artipie.http.Response;
import com.artipie.http.ResponseBuilder;
import com.artipie.http.headers.ContentType;
import com.artipie.http.log.EcsLogger;
import com.artipie.http.rq.RequestLine;
import java.security.Permission;
import java.util.concurrent.CompletableFuture;
import java.nio.ByteBuffer;

import io.reactivex.Flowable;

public class HeadManifestSlice extends DockerActionSlice {

    public HeadManifestSlice(Docker docker) {
        super(docker);
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        ManifestRequest request = ManifestRequest.from(line);

        EcsLogger.debug("com.artipie.docker")
            .message("HEAD manifest request")
            .eventCategory("repository")
            .eventAction("manifest_head")
            .field("container.image.name", request.name())
            .field("container.image.tag", request.reference().digest())
            .log();

        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // HEAD requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored ->
            this.docker.repo(request.name()).manifests()
                .get(request.reference())
                .thenApply(
                    manifest -> manifest.map(
                        found -> {
                            long size = found.size();
                            Content head = new Content.From(size, Flowable.<ByteBuffer>empty());

                            EcsLogger.debug("com.artipie.docker")
                                .message("Manifest found")
                                .eventCategory("repository")
                                .eventAction("manifest_head")
                                .eventOutcome("success")
                                .field("container.image.name", request.name())
                                .field("container.image.tag", request.reference().digest())
                                .field("package.size", size)
                                .field("file.type", found.mediaType())
                                .log();

                            return ResponseBuilder.ok()
                                .header(ContentType.mime(found.mediaType()))
                                .header(new DigestHeader(found.digest()))
                                .body(head)
                                .build();
                        }
                    ).orElseGet(
                        () -> {
                            EcsLogger.warn("com.artipie.docker")
                                .message("Manifest not found")
                                .eventCategory("repository")
                                .eventAction("manifest_head")
                                .eventOutcome("failure")
                                .field("container.image.name", request.name())
                                .field("container.image.tag", request.reference().digest())
                                .log();

                            return ResponseBuilder.notFound()
                                .jsonBody(new ManifestError(request.reference()).json())
                                .build();
                        }
                    )
                )
        );
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), ManifestRequest.from(line).name(), DockerActions.PULL.mask()
        );
    }
}
