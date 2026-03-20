/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http.upload;

import com.auto1.pantera.docker.Digest;
import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.docker.http.DigestHeader;
import com.auto1.pantera.docker.http.DockerActionSlice;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.headers.ContentLength;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.headers.Location;

import java.util.concurrent.CompletableFuture;

public abstract class UploadSlice extends DockerActionSlice {


    public UploadSlice(Docker docker) {
        super(docker);
    }

    protected CompletableFuture<Response> acceptedResponse(String name, String uuid, long offset) {
        return ResponseBuilder.accepted()
            .header(new Location(String.format("/v2/%s/blobs/uploads/%s", name, uuid)))
            .header(new Header("Range", String.format("0-%d", offset)))
            .header(new ContentLength("0"))
            .header(new Header("Docker-Upload-UUID", uuid))
            .completedFuture();
    }

    protected CompletableFuture<Response> createdResponse(String name, Digest digest) {
        return ResponseBuilder.created()
            .header(new Location(String.format("/v2/%s/blobs/%s", name, digest.string())))
            .header(new ContentLength("0"))
            .header(new DigestHeader(digest))
            .completedFuture();
    }
}
