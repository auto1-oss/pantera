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
package com.auto1.pantera.nuget.http.index;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.nuget.AstoRepository;
import com.auto1.pantera.nuget.http.NuGet;
import com.auto1.pantera.nuget.http.TestAuthentication;
import com.auto1.pantera.security.policy.PolicyByUsername;
import java.net.URI;
import java.util.Optional;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The service index is part of a private repository: it must validate the
 * credentials like every other NuGet resource instead of answering any
 * Basic header with 200.
 *
 * @since 2.2.9
 */
final class NuGetServiceIndexAuthTest {

    /**
     * Tested NuGet slice.
     */
    private NuGet nuget;

    @BeforeEach
    void init() throws Exception {
        this.nuget = new NuGet(
            URI.create("http://localhost:4321/repo").toURL(),
            new AstoRepository(new InMemoryStorage()),
            new PolicyByUsername(TestAuthentication.USERNAME),
            new TestAuthentication(),
            "repo",
            Optional.empty()
        );
    }

    @Test
    void rejectsUnknownCredentials() {
        MatcherAssert.assertThat(
            this.nuget.response(
                new RequestLine(RqMethod.GET, "/index.json"),
                Headers.from(new Authorization.Basic("nosuch", "bogus")),
                Content.EMPTY
            ).join(),
            new RsHasStatus(RsStatus.UNAUTHORIZED)
        );
    }

    @Test
    void servesIndexToValidCredentials() {
        MatcherAssert.assertThat(
            this.nuget.response(
                new RequestLine(RqMethod.GET, "/index.json"),
                TestAuthentication.HEADERS,
                Content.EMPTY
            ).join(),
            new RsHasStatus(RsStatus.OK)
        );
    }
}
