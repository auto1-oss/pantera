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
package com.auto1.pantera.conan.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.auth.Authentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.hm.IsJson;
import com.auto1.pantera.http.hm.RsHasBody;
import com.auto1.pantera.http.hm.RsHasStatus;
import com.auto1.pantera.http.hm.SliceHasResponse;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.http.RsStatus;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

import javax.json.Json;

/**
 * Test for {@link UsersEntity}.
 */
public class UsersEntityTest {

    @Test
    public void userAuthTest() {
        final String login = ConanSliceITCase.SRV_USERNAME;
        final String password = ConanSliceITCase.SRV_PASSWORD;
        MatcherAssert.assertThat(
            "Slice response must match",
            new UsersEntity.UserAuth(
                new Authentication.Single(
                    ConanSliceITCase.SRV_USERNAME, ConanSliceITCase.SRV_PASSWORD
                ),
                new ConanSlice.FakeAuthTokens(ConanSliceITCase.TOKEN, ConanSliceITCase.SRV_USERNAME)
            ),
            new SliceHasResponse(
                Matchers.allOf(
                    new RsHasStatus(RsStatus.OK),
                    new RsHasBody(String.format("%s", ConanSliceITCase.TOKEN).getBytes())
                ),
                new RequestLine(RqMethod.GET, "/v1/users/authenticate"),
                Headers.from(new Authorization.Basic(login, password)),
                Content.EMPTY
            )
        );
    }

    @Test
    public void credsCheckTest() {
        MatcherAssert.assertThat(
            "Response must match",
            new UsersEntity.CredsCheck().response(
                new RequestLine(RqMethod.GET, "/v1/users/check_credentials"),
                Headers.from("Host", "localhost"), Content.EMPTY
            ).join(),
            Matchers.allOf(
                new RsHasBody(
                    new IsJson(new IsEqual<>(Json.createObjectBuilder().build()))
                ),
                new RsHasStatus(RsStatus.OK)
            )
        );
    }
}
