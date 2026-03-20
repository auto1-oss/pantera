/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.docker.Docker;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.auth.BasicAuthScheme;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.scheduling.ArtifactEvent;

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
