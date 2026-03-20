/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.docker.http;

import com.artipie.docker.Docker;
import com.artipie.http.Headers;
import com.artipie.http.auth.Authentication;
import com.artipie.http.auth.BasicAuthScheme;
import com.artipie.http.headers.Authorization;
import com.artipie.http.headers.Header;
import com.artipie.security.policy.Policy;
import com.artipie.scheduling.ArtifactEvent;

import java.util.Optional;
import java.util.Queue;

final class TestDockerAuth {

    static final String USER = "docker-user";

    static final String PASSWORD = "secret";

    private TestDockerAuth() {
    }

    static DockerSlice slice(final Docker docker) {
        return slice(docker, Optional.empty());
    }

    static DockerSlice slice(final Docker docker, final Optional<Queue<ArtifactEvent>> events) {
        return new DockerSlice(
            docker,
            Policy.FREE,
            new BasicAuthScheme(new Authentication.Single(USER, PASSWORD)),
            events
        );
    }

    static Headers headers() {
        return Headers.from(new Authorization.Basic(USER, PASSWORD));
    }

    static Headers headers(final Header... extras) {
        final Headers headers = Headers.from(new Authorization.Basic(USER, PASSWORD));
        for (final Header header : extras) {
            headers.add(header);
        }
        return headers;
    }
}
