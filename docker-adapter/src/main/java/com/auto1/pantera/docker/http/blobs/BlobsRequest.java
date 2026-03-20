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
import com.auto1.pantera.docker.http.PathPatterns;
import com.auto1.pantera.docker.misc.ImageRepositoryName;
import com.auto1.pantera.docker.misc.RqByRegex;
import com.auto1.pantera.http.rq.RequestLine;

public record BlobsRequest(String name, Digest digest) {

    public static BlobsRequest from(RequestLine line) {
        RqByRegex regex = new RqByRegex(line, PathPatterns.BLOBS);
        return new BlobsRequest(
            ImageRepositoryName.validate(regex.path().group("name")),
            new Digest.FromString(regex.path().group("digest"))
        );
    }

}
