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
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.audit.AuditContext;
import com.auto1.pantera.audit.AuditLogger;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.manifest.Referrers;
import com.auto1.pantera.docker.perms.DockerRegistryPermission;
import com.auto1.pantera.docker.perms.RegistryCategory;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.Login;
import com.auto1.pantera.http.log.EcsMdc;
import com.auto1.pantera.http.log.RequestContextHeaders;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqParams;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * OCI Distribution Spec v1.1 Referrers API endpoint.
 * Returns list of manifests that reference the given digest via the subject field.
 *
 * <p>Per spec, a registry that supports the referrers API MUST return 200 OK
 * (never 404). When no referrers exist, an empty OCI Image Index is returned.
 *
 * <p>Hosted-registry only: {@code docker} (local) repositories index referrers
 * on push (see {@code AstoManifests}); {@code docker-proxy} composites answer
 * with an always-empty listing (proxy-through of upstream referrers is out of
 * scope for 2.3.0 — see WS4-docker.2 §3 non-goals).
 *
 * @see <a href="https://github.com/opencontainers/distribution-spec/blob/main/spec.md">OCI Distribution Spec</a>
 */
public final class ReferrersSlice extends DockerActionSlice {

    /**
     * Repository type recorded on the audit trail — this slice is wired
     * only for {@code docker} repositories.
     */
    private static final String REPO_TYPE = "docker";

    /**
     * Header naming the query filters honored while assembling the listing.
     */
    private static final String FILTERS_APPLIED_HEADER = "OCI-Filters-Applied";

    /**
     * Value of {@link #FILTERS_APPLIED_HEADER} when {@code ?artifactType=} was applied.
     */
    private static final String ARTIFACT_TYPE_FILTER = "artifactType";

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
        final ReferrersRequest request = ReferrersRequest.from(line);
        final Optional<String> artifactType = new RqParams(line.uri()).value(ARTIFACT_TYPE_FILTER);
        // Captured before the async hop into Manifests.referrers() — MDC does
        // not survive worker-thread continuations (CLAUDE.md audit rules).
        RequestContextHeaders.bindToMdc(headers);
        final AuditContext ctx = new AuditContext(
            MDC.get(EcsMdc.TRACE_ID), MDC.get(EcsMdc.CLIENT_IP)
        );
        final String owner = new Login(headers).getValue();
        return body.asBytesFuture().thenCompose(
            ignored -> this.docker.repo(request.name())
                .manifests()
                .referrers(request.subject(), artifactType)
        ).thenApply(
            referrers -> {
                AuditLogger.resolution(
                    ctx, REPO_TYPE, this.docker.registryName(), request.name(), owner, Collections.emptyList()
                );
                return this.build(referrers, artifactType);
            }
        );
    }

    /**
     * Assembles the OCI Image Index response, adding
     * {@value #FILTERS_APPLIED_HEADER} when a filter narrowed the listing.
     *
     * @param referrers Referrers listing to serve.
     * @param artifactType Applied {@code ?artifactType=} filter, if any.
     * @return Response.
     */
    private Response build(final Referrers referrers, final Optional<String> artifactType) {
        final ResponseBuilder builder = ResponseBuilder.ok()
            .header("Content-Type", Referrers.MEDIA_TYPE)
            .body(referrers.json());
        artifactType.ifPresent(ignored -> builder.header(FILTERS_APPLIED_HEADER, ARTIFACT_TYPE_FILTER));
        return builder.build();
    }
}
