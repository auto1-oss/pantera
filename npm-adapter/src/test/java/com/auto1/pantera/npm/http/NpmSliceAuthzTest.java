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
package com.auto1.pantera.npm.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;

import java.net.URL;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.hamcrest.core.IsNot;
import org.junit.jupiter.api.Test;

/**
 * Authorization corrections in {@link NpmSlice}: dist-tag removal and
 * version unpublish require {@link Action.Standard#DELETE}, not {@link
 * Action.Standard#WRITE}; profile set requires {@link Action.Standard#WRITE},
 * not {@link Action.Standard#READ}. A role granting write but not delete
 * must not be able to remove versions or dist-tags -- that is the point of
 * this correction, not an oversight.
 */
final class NpmSliceAuthzTest {

    /**
     * Repository name under test.
     */
    private static final String REPO = "npm-local";

    /**
     * Bearer token the auth double accepts.
     */
    private static final String TOKEN = "authz-test-token";

    @Test
    void writeGrantDoesNotAllowRemovingADistTag() throws Exception {
        MatcherAssert.assertThat(
            "dist-tag rm requires DELETE, which this principal lacks",
            this.statusFor(
                NpmSliceAuthzTest.readWrite(), RqMethod.DELETE,
                "/-/package/pkg/dist-tags/latest"
            ),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
    }

    @Test
    void writeGrantDoesNotAllowUnpublishingAVersion() throws Exception {
        MatcherAssert.assertThat(
            "unpublish <version> requires DELETE, which this principal lacks",
            this.statusFor(
                NpmSliceAuthzTest.readWrite(), RqMethod.PUT, "/pkg",
                Headers.from("referer", "unpublish")
            ),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
    }

    @Test
    void readGrantDoesNotAllowSettingTheProfile() throws Exception {
        MatcherAssert.assertThat(
            "profile set requires WRITE, which this principal lacks",
            this.statusFor(
                NpmSliceAuthzTest.readOnly(), RqMethod.PUT, "/-/npm/v1/user"
            ),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
    }

    @Test
    void readGrantStillAllowsReadingTheProfile() throws Exception {
        MatcherAssert.assertThat(
            "control: profile get must remain a READ operation",
            this.statusFor(
                NpmSliceAuthzTest.readOnly(), RqMethod.GET, "/-/npm/v1/user"
            ),
            new IsNot<>(new IsEqual<>(RsStatus.FORBIDDEN))
        );
    }

    /**
     * Drive one request through a freshly built slice.
     * @param policy Policy granting the principal's permissions
     * @param method Request method
     * @param path Request path
     * @return Response status
     * @throws Exception If the base URL is malformed
     */
    private RsStatus statusFor(
        final Policy<PermissionCollection> policy, final RqMethod method, final String path
    ) throws Exception {
        return this.statusFor(policy, method, path, Headers.EMPTY);
    }

    /**
     * Drive one request through a freshly built slice, with additional
     * headers merged in on top of the bearer {@code Authorization} header --
     * needed for routes (such as unpublish) that are matched by a header
     * value rather than by path alone.
     * @param policy Policy granting the principal's permissions
     * @param method Request method
     * @param path Request path
     * @param extra Extra headers to send alongside the bearer token
     * @return Response status
     * @throws Exception If the base URL is malformed
     */
    private RsStatus statusFor(
        final Policy<PermissionCollection> policy, final RqMethod method, final String path,
        final Headers extra
    ) throws Exception {
        return new NpmSlice(
            new URL("http://localhost:8080"), new InMemoryStorage(), policy,
            NpmSliceAuthzTest.auth(), NpmSliceAuthzTest.REPO, Optional.empty()
        ).response(
            new RequestLine(method, path),
            Headers.from(new Authorization.Bearer(NpmSliceAuthzTest.TOKEN)).addAll(extra),
            Content.EMPTY
        ).join().status();
    }

    /**
     * Policy granting read and write, but not delete.
     * @return Policy
     */
    private static Policy<PermissionCollection> readWrite() {
        return user -> {
            final Permissions perms = new Permissions();
            perms.add(new AdapterBasicPermission(
                NpmSliceAuthzTest.REPO, Action.Standard.READ
            ));
            perms.add(new AdapterBasicPermission(
                NpmSliceAuthzTest.REPO, Action.Standard.WRITE
            ));
            return perms;
        };
    }

    /**
     * Policy granting read only.
     * @return Policy
     */
    private static Policy<PermissionCollection> readOnly() {
        return user -> {
            final Permissions perms = new Permissions();
            perms.add(new AdapterBasicPermission(
                NpmSliceAuthzTest.REPO, Action.Standard.READ
            ));
            return perms;
        };
    }

    /**
     * Token authentication double accepting exactly one token.
     * @return Token authentication
     */
    private static TokenAuthentication auth() {
        return token -> CompletableFuture.completedFuture(
            NpmSliceAuthzTest.TOKEN.equals(token)
                ? Optional.of(new AuthUser("authz-tester", "test"))
                : Optional.empty()
        );
    }
}
