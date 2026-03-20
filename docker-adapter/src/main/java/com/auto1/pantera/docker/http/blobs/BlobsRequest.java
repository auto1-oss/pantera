/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
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
