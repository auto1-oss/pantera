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
package com.auto1.pantera.docker.error;

import com.auto1.pantera.docker.ManifestReference;

import java.util.Optional;

/**
 * The requested manifest exists but its stored media type is not among the
 * media types the client declared acceptable via the {@code Accept} header
 * (WS4-docker.7 -- manifest content negotiation). Mapped directly to
 * {@code 406 Not Acceptable} by {@code GetManifestSlice}/{@code
 * HeadManifestSlice}, which build the response themselves rather than
 * throwing -- this type is never thrown, so it never reaches {@code
 * ErrorHandlingSlice}'s generic {@link DockerError} funnel (that funnel
 * maps every thrown {@link DockerError} to {@code 400 Bad Request}, which
 * would be the wrong status here).
 *
 * <p>{@code MANIFEST_UNACCEPTABLE} is a Pantera extension code -- the base
 * OCI distribution-spec error-code list has no entry for "media type not
 * acceptable"; conformant clients key off the HTTP status, not this code.
 */
public final class ManifestNotAcceptableError implements DockerError {

    /**
     * Manifest reference the client requested.
     */
    private final ManifestReference ref;

    /**
     * Media type the manifest is actually stored/served as.
     */
    private final String storedMediaType;

    /**
     * Ctor.
     *
     * @param ref Manifest reference.
     * @param storedMediaType Media type the manifest is actually stored/served as.
     */
    public ManifestNotAcceptableError(final ManifestReference ref, final String storedMediaType) {
        this.ref = ref;
        this.storedMediaType = storedMediaType;
    }

    @Override
    public String code() {
        return "MANIFEST_UNACCEPTABLE";
    }

    @Override
    public String message() {
        return "manifest media type not acceptable";
    }

    @Override
    public Optional<String> detail() {
        return Optional.of(
            String.format(
                "reference '%s' is only available as '%s'", this.ref.digest(), this.storedMediaType
            )
        );
    }
}
