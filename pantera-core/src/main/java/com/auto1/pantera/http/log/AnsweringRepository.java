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
package com.auto1.pantera.http.log;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import java.util.ArrayList;
import java.util.Optional;

/**
 * The repository that answered a request, carried on the response in the
 * internal headers {@link #NAME_HEADER} and {@link #TYPE_HEADER}.
 *
 * <p>The access log is written by {@code EcsLoggingSlice}, above the routing
 * that picks the repository, so it does not know which repository a request
 * was for: every access record lacked {@code repository.name}. The
 * outermost wrapper of each repository ({@code ReasonSlice}) stamps the
 * answer; for a group the group's stamp replaces that of the member that
 * supplied the artifact, so the record names the repository the client
 * asked. {@code EcsLoggingSlice} reads the headers and strips them before
 * the response leaves Pantera.</p>
 *
 * @since 2.2.14
 */
public final class AnsweringRepository {

    /**
     * Internal response header: name of the repository.
     */
    public static final String NAME_HEADER = "X-Pantera-Ctx-Repo-Name";

    /**
     * Internal response header: type of the repository.
     */
    public static final String TYPE_HEADER = "X-Pantera-Ctx-Repo-Type";

    /**
     * The response.
     */
    private final Response response;

    /**
     * Ctor.
     * @param response Response
     */
    public AnsweringRepository(final Response response) {
        this.response = response;
    }

    /**
     * Name of the repository that answered.
     * @return Name, if the answer came from a repository
     */
    public Optional<String> name() {
        return this.first(AnsweringRepository.NAME_HEADER);
    }

    /**
     * Type of the repository that answered.
     * @return Type such as {@code maven-proxy}, if known
     */
    public Optional<String> type() {
        return this.first(AnsweringRepository.TYPE_HEADER);
    }

    /**
     * The response naming the given repository, in place of any it named.
     * @param name Repository name; null or blank names none
     * @param type Repository type; null or blank is left out
     * @return Response
     */
    public Response stamped(final String name, final String type) {
        final Headers headers = new Headers(
            new ArrayList<>(
                this.response.headers().stream()
                    .filter(
                        hdr -> !AnsweringRepository.NAME_HEADER.equalsIgnoreCase(hdr.getKey())
                            && !AnsweringRepository.TYPE_HEADER.equalsIgnoreCase(hdr.getKey())
                    )
                    .toList()
            )
        );
        if (name != null && !name.isBlank()) {
            headers.add(AnsweringRepository.NAME_HEADER, name);
            if (type != null && !type.isBlank()) {
                headers.add(AnsweringRepository.TYPE_HEADER, type);
            }
        }
        return new Response(this.response.status(), headers, this.response.body());
    }

    /**
     * The response without the internal headers.
     * @return Response
     */
    public Response stripped() {
        return this.stamped(null, null);
    }

    private Optional<String> first(final String header) {
        return this.response.headers().values(header)
            .stream().filter(val -> !val.isBlank()).findFirst();
    }
}
