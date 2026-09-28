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
package com.auto1.pantera.docker.http.blobs;

import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.error.InvalidDigestException;
import com.auto1.pantera.docker.http.PathPatterns;
import com.auto1.pantera.docker.misc.ImageRepositoryName;
import com.auto1.pantera.docker.misc.RqByRegex;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.rq.RequestLine;

import java.util.regex.Pattern;

public record BlobsRequest(String name, Digest digest) {

    /**
     * OCI digest grammar ({@code algorithm ":" encoded}).
     */
    private static final Pattern DIGEST =
        Pattern.compile("[a-z0-9]+(?:[+._-][a-z0-9]+)*:[a-zA-Z0-9=_-]+");

    /**
     * Encoded length of the registered algorithms.
     */
    private static final Pattern REGISTERED =
        Pattern.compile("sha256:[a-f0-9]{64}|sha512:[a-f0-9]{128}");

    public static BlobsRequest from(RequestLine line) {
        RqByRegex regex = new RqByRegex(line, PathPatterns.BLOBS);
        return new BlobsRequest(
            ImageRepositoryName.validate(regex.path().group("name")),
            new Digest.FromString(regex.path().group("digest"))
        );
    }

    /**
     * Whether the digest follows the OCI digest grammar, with the exact hex
     * length for {@code sha256} and {@code sha512}. A malformed digest is
     * answered with 400 DIGEST_INVALID, not 404 BLOB_UNKNOWN.
     *
     * @return True when well-formed
     */
    public boolean wellFormed() {
        final String value = this.digest.toString();
        final boolean registered = value.startsWith("sha256:") || value.startsWith("sha512:");
        return BlobsRequest.DIGEST.matcher(value).matches()
            && (!registered || BlobsRequest.REGISTERED.matcher(value).matches());
    }

    /**
     * 400 DIGEST_INVALID for a malformed digest.
     *
     * @return Response
     */
    public Response invalidDigest() {
        return ResponseBuilder.badRequest()
            .jsonBody(new InvalidDigestException("malformed digest: " + this.digest).json())
            .build();
    }
}
