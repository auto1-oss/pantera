/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.maven.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Slice;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Head repository metadata.
 */
final class RepoHead {

    /**
     * Client slice.
     */
    private final Slice client;

    /**
     * New repository artifact's heads.
     * @param client Client slice
     */
    RepoHead(final Slice client) {
        this.client = client;
    }

    /**
     * Artifact head.
     * @param path Path for artifact
     * @return Artifact headers
     */
    CompletionStage<Optional<Headers>> head(final String path) {
        return this.client.response(
            new RequestLine(RqMethod.HEAD, path), Headers.EMPTY, Content.EMPTY
        ).thenApply(resp -> resp.status() == RsStatus.OK ? Optional.of(resp.headers()) : Optional.empty());
    }
}
