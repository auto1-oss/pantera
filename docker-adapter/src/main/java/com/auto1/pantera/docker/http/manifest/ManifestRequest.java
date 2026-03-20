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
package com.auto1.pantera.docker.http.manifest;

import com.auto1.pantera.docker.ManifestReference;
import com.auto1.pantera.docker.http.PathPatterns;
import com.auto1.pantera.docker.misc.ImageRepositoryName;
import com.auto1.pantera.docker.misc.RqByRegex;
import com.auto1.pantera.http.rq.RequestLine;

/**
 * @param name The name of the image.
 * @param reference The reference may include a tag or digest.
 */
public record ManifestRequest(String name, ManifestReference reference) {

    public static ManifestRequest from(RequestLine line) {
        RqByRegex regex = new RqByRegex(line, PathPatterns.MANIFESTS);
        return new ManifestRequest(
            ImageRepositoryName.validate(regex.path().group("name")),
            ManifestReference.from(regex.path().group("reference"))
        );
    }
}