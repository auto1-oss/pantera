/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.perms.DockerRegistryPermission;
import com.auto1.pantera.docker.perms.RegistryCategory;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.concurrent.CompletableFuture;

/**
 * Base entity in Docker HTTP API.
 * See <a href="https://docs.docker.com/registry/spec/api/#base">Base</a>.
 */
public final class BaseSlice extends DockerActionSlice {

    public BaseSlice(Docker docker) {
        super(docker);
    }

    @Override
    public DockerRegistryPermission permission(final RequestLine line) {
        return new DockerRegistryPermission(docker.registryName(), RegistryCategory.BASE.mask());
    }

    @Override
    public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
        // CRITICAL FIX: Consume request body to prevent Vert.x resource leak
        return body.asBytesFuture().thenApply(ignored ->
            ResponseBuilder.ok()
                .header("Docker-Distribution-API-Version", "registry/2.0")
                .build()
        );
    }
}
