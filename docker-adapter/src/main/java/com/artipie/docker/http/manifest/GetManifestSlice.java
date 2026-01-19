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
import com.artipie.http.rq.RequestLine;

import java.security.Permission;
import java.util.concurrent.CompletableFuture;

public class GetManifestSlice extends DockerActionSlice {

    public GetManifestSlice(Docker docker) {
        super(docker);
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        ManifestRequest request = ManifestRequest.from(line);
        try {
            java.nio.file.Files.writeString(
                java.nio.file.Paths.get("/var/artipie/get-manifest-debug.log"),
                "GET request for: " + request.reference() + ", method=" + line.method() + "\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (Exception e) {}

        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        // GET requests should have empty body, but we must consume it to complete the request
        return body.asBytesFuture().thenCompose(ignored ->
            this.docker.repo(request.name())
                .manifests()
                .get(request.reference())
                .thenApply(
                    manifest -> manifest.map(
                        found -> {
                            try {
                                java.nio.file.Files.writeString(
                                    java.nio.file.Paths.get("/var/artipie/get-manifest-debug.log"),
                                    "Manifest found, size=" + found.size() + ", mediaType=" + found.mediaType() + "\n",
                                    java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.APPEND
                                );
                            } catch (Exception e) {}

                            Response response = ResponseBuilder.ok()
                                .header(ContentType.mime(found.mediaType()))
                                .header(new DigestHeader(found.digest()))
                                .body(found.content())
                                .build();

                            // Log response headers at DEBUG level for diagnostics
                            com.artipie.http.log.EcsLogger.debug("com.artipie.docker")
                                .message("GET manifest response headers")
                                .eventCategory("repository")
                                .eventAction("manifest_get")
                                .field("container.image.name", request.name())
                                .field("container.image.tag", request.reference().digest())
                                .field("file.type", found.mediaType())
                                .field("package.checksum", found.digest())
                                .field("http.response.headers.Content-Type", found.mediaType())
                                .field("http.response.headers.Docker-Content-Digest", found.digest())
                                .log();

                            return response;
                        }
                    ).orElseGet(
                        () -> ResponseBuilder.notFound()
                            .jsonBody(new ManifestError(request.reference()).json())
                            .build()
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
