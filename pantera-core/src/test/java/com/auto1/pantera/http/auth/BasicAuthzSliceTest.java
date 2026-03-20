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
package com.auto1.pantera.http.auth;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.hm.ResponseAssert;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.slice.SliceSimple;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.perms.EmptyPermissions;
import com.auto1.pantera.security.policy.Policy;
import com.auto1.pantera.security.policy.PolicyByUsername;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Test for {@link BasicAuthzSlice}.
 */
class BasicAuthzSliceTest {

    @Test
    void proxyToOriginSliceIfAllowed() {
        final String user = "test_user";
        ResponseAssert.check(
            new BasicAuthzSlice(
                (rqline, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.ok().headers(headers).build()),
                (usr, pwd) -> Optional.of(new AuthUser(user, "test")),
                new OperationControl(
                    Policy.FREE,
                    new AdapterBasicPermission("any_repo_name", Action.ALL)
                )
            ).response(
                new RequestLine("GET", "/foo"),
                Headers.from(new Authorization.Basic(user, "pwd")),
                Content.EMPTY
            ).join(),
            RsStatus.OK, new Header(AuthzSlice.LOGIN_HDR, user));
    }

    @Test
    void returnsUnauthorizedErrorIfCredentialsAreWrong() {
        ResponseAssert.check(
            new BasicAuthzSlice(
                new SliceSimple(ResponseBuilder.ok().build()),
                (user, pswd) -> Optional.empty(),
                new OperationControl(
                    user -> EmptyPermissions.INSTANCE,
                    new AdapterBasicPermission("any", Action.NONE)
                )
            ).response(
                new RequestLine("POST", "/bar", "HTTP/1.2"),
                Headers.from(new Authorization.Basic("aaa", "bbbb")),
                Content.EMPTY
            ).join(),
            RsStatus.UNAUTHORIZED, new Header("WWW-Authenticate", "Basic realm=\"pantera\"")
        );
    }

    @Test
    void returnsForbiddenIfNotAllowed() {
        final String name = "john";
        ResponseAssert.check(
            new BasicAuthzSlice(
                new SliceSimple(ResponseBuilder.ok().build()),
                (user, pswd) -> Optional.of(new AuthUser(name)),
                new OperationControl(
                    user -> EmptyPermissions.INSTANCE,
                    new AdapterBasicPermission("any", Action.NONE)
                )
            ).response(
                new RequestLine("DELETE", "/baz", "HTTP/1.3"),
                Headers.from(new Authorization.Basic(name, "123")),
                Content.EMPTY
            ).join(),
            RsStatus.FORBIDDEN
        );
    }

    @Test
    void returnsUnauthorizedForAnonymousUser() {
        ResponseAssert.check(
            new BasicAuthzSlice(
                new SliceSimple(ResponseBuilder.ok().build()),
                (user, pswd) -> Assertions.fail("Shouldn't be called"),
                new OperationControl(
                    user -> {
                        MatcherAssert.assertThat(
                            user.name(),
                            Matchers.anyOf(Matchers.is("anonymous"), Matchers.is("*"))
                        );
                        return EmptyPermissions.INSTANCE;
                    },
                    new AdapterBasicPermission("any", Action.NONE)
                )
            ).response(
                new RequestLine("DELETE", "/baz", "HTTP/1.3"),
                Headers.from(new Header("WWW-Authenticate", "Basic realm=\"pantera\"")),
                Content.EMPTY
            ).join(),
            RsStatus.UNAUTHORIZED,
            new Header("WWW-Authenticate", "Basic realm=\"pantera\"")
        );
    }

    @Test
    void parsesHeaders() {
        final String aladdin = "Aladdin";
        final String pswd = "open sesame";
        ResponseAssert.check(
            new BasicAuthzSlice(
                (rqline, headers, body) -> CompletableFuture.completedFuture(
                    ResponseBuilder.ok().headers(headers).build()),
                new Authentication.Single(aladdin, pswd),
                new OperationControl(
                    new PolicyByUsername(aladdin),
                    new AdapterBasicPermission("any", Action.ALL)
                )
            ).response(
                new RequestLine("PUT", "/my-endpoint"),
                Headers.from(new Authorization.Basic(aladdin, pswd)),
                Content.EMPTY
            ).join(),
            RsStatus.OK, new Header(AuthzSlice.LOGIN_HDR, "Aladdin")
        );
    }
}
