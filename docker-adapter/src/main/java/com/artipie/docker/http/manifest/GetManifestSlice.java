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
import com.artipie.http.headers.Login;
import com.artipie.http.rq.RequestLine;
import org.slf4j.MDC;

import java.security.Permission;
import java.util.concurrent.CompletableFuture;

public class GetManifestSlice extends DockerActionSlice {

    public GetManifestSlice(Docker docker) {
        super(docker);
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        ManifestRequest request = ManifestRequest.from(line);
        // Capture the authenticated login before crossing the async boundary.
        // AuthzSlice adds artipie_login to headers; body.asBytesFuture() may complete
        // on a different thread (Vert.x event loop) where MDC.user.name is not set.
        // Re-setting MDC inside the thenCompose ensures CacheManifests.get() sees
        // the correct owner when it calls MDC.get("user.name").
        final String login = new Login(headers).getValue();
        // Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenCompose(ignored -> {
            MDC.put("user.name", login);
            return this.docker.repo(request.name())
                .manifests()
                .get(request.reference())
                .thenApply(
                    manifest -> manifest.map(
                        found -> {
                            Response response = ResponseBuilder.ok()
                                .header(ContentType.mime(found.mediaType()))
                                .header(new DigestHeader(found.digest()))
                                .body(found.content())
                                .build();

                            // Log response headers at DEBUG level for diagnostics
                            com.artipie.http.log.EcsLogger.debug("com.artipie.docker")
                                .message(String.format("GET manifest response: digest=%s", found.digest()))
                                .eventCategory("repository")
                                .eventAction("manifest_get")
                                .field("container.image.name", request.name())
                                .field("container.image.tag", request.reference().digest())
                                .field("file.type", found.mediaType())
                                .field("package.checksum", found.digest())
                                .field("http.response.mime_type", found.mediaType())
                                .log();

                            return response;
                        }
                    ).orElseGet(
                        () -> ResponseBuilder.notFound()
                            .jsonBody(new ManifestError(request.reference()).json())
                            .build()
                    )
                )
                .exceptionally(err -> {
                    com.artipie.http.log.EcsLogger.warn("com.artipie.docker")
                        .message("Manifest GET failed with exception, returning 404")
                        .eventCategory("repository")
                        .eventAction("manifest_get")
                        .eventOutcome("failure")
                        .field("container.image.name", request.name())
                        .error(err)
                        .log();
                    return ResponseBuilder.notFound()
                        .jsonBody(new ManifestError(request.reference()).json())
                        .build();
                });
        });
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), ManifestRequest.from(line).name(), DockerActions.PULL.mask()
        );
    }
}
