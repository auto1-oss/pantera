/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * OCI Distribution Spec v1.1 Referrers API endpoint.
 * Returns list of manifests that reference the given digest via the subject field.
 *
 * <p>Per spec, a registry that supports the referrers API MUST return 200 OK
 * (never 404). When no referrers exist, an empty OCI Image Index is returned.
 *
 * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md">OCI Distribution Spec</a>
 */
public final class ReferrersSlice extends DockerActionSlice {

    /**
     * OCI Image Index media type.
     */
    private static final String OCI_INDEX_MEDIA_TYPE =
        "application/vnd.oci.image.index.v1+json";

    /**
     * Empty referrers response body (valid OCI Image Index with no manifests).
     */
    private static final byte[] EMPTY_INDEX = String.join("",
        "{",
        "\"schemaVersion\":2,",
        "\"mediaType\":\"", OCI_INDEX_MEDIA_TYPE, "\",",
        "\"manifests\":[]",
        "}"
    ).getBytes(StandardCharsets.UTF_8);

    public ReferrersSlice(final Docker docker) {
        super(docker);
    }

    @Override
    public DockerRegistryPermission permission(final RequestLine line) {
        return new DockerRegistryPermission(
            docker.registryName(), RegistryCategory.CATALOG.mask()
        );
    }

    @Override
    public CompletableFuture<Response> response(
        final RequestLine line, final Headers headers, final Content body
    ) {
        return body.asBytesFuture().thenApply(ignored ->
            ResponseBuilder.ok()
                .header("Content-Type", OCI_INDEX_MEDIA_TYPE)
                .body(EMPTY_INDEX)
                .build()
        );
    }
}
