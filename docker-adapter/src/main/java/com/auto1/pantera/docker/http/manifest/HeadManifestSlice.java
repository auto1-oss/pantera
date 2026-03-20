/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http.manifest;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.error.ManifestError;
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.http.DockerActionSlice;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsLogger;
import com.auto1.pantera.http.rq.RequestLine;
import org.slf4j.MDC;

import java.nio.ByteBuffer;
import java.security.Permission;
import java.util.concurrent.CompletableFuture;

import io.reactivex.Flowable;

public class HeadManifestSlice extends DockerActionSlice {

    public HeadManifestSlice(Docker docker) {
        super(docker);
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        ManifestRequest request = ManifestRequest.from(line);

        EcsLogger.debug("com.auto1.pantera.docker")
            .message("HEAD manifest request")
            .eventCategory("repository")
            .eventAction("manifest_head")
            .field("container.image.name", request.name())
            .field("container.image.tag", request.reference().digest())
            .log();

        // Capture the authenticated login before crossing the async boundary.
        // Mirrors the same fix in GetManifestSlice: body.asBytesFuture() may complete
        // on a different thread where MDC.user.name is not set.
        final String login = new Login(headers).getValue();
        return body.asBytesFuture().thenCompose(ignored -> {
            MDC.put("user.name", login);
            return this.docker.repo(request.name()).manifests()
                .get(request.reference())
                .thenApply(
                    manifest -> manifest.map(
                        found -> {
                            long size = found.size();
                            Content head = new Content.From(size, Flowable.<ByteBuffer>empty());

                            EcsLogger.debug("com.auto1.pantera.docker")
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
                            EcsLogger.warn("com.auto1.pantera.docker")
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
                );
        });
    }

    @Override
    public Permission permission(RequestLine line) {
        return new DockerRepositoryPermission(
            docker.registryName(), ManifestRequest.from(line).name(), DockerActions.PULL.mask()
        );
    }
}
