/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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