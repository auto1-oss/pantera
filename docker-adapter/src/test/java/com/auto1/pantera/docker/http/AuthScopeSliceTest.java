/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.docker.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.docker.perms.DockerActions;
import com.auto1.pantera.docker.perms.DockerRepositoryPermission;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.ResponseBuilder;
import com.auto1.pantera.http.auth.AuthScheme;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.rq.RequestLine;
import org.apache.commons.lang3.NotImplementedException;
import org.hamcrest.MatcherAssert;
import org.hamcrest.Matchers;
import org.hamcrest.core.StringContains;
import org.junit.jupiter.api.Test;

import java.security.Permission;
import java.security.PermissionCollection;
import java.util.Enumeration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests for {@link AuthScopeSlice}.
 */
class AuthScopeSliceTest {

    @Test
    void testScope() {
        final RequestLine line = RequestLine.from("GET /resource.txt HTTP/1.1");
        final AtomicReference<String> perm = new AtomicReference<>();
        final AtomicReference<RequestLine> aline = new AtomicReference<>();
        new AuthScopeSlice(
            new ScopeSlice() {
                @Override
                public DockerRepositoryPermission permission(RequestLine line) {
                    aline.set(line);
                    return new DockerRepositoryPermission("registryName", "bar", DockerActions.PULL.mask());
                }

                @Override
                public CompletableFuture<Response> response(RequestLine line, Headers headers, Content body) {
                    return ResponseBuilder.ok().completedFuture();
                }
            },
            (headers, rline) -> CompletableFuture.completedFuture(
                AuthScheme.result(new AuthUser("alice", "test"), "")
            ),
            authUser -> new TestCollection(perm)
        ).response(line, Headers.EMPTY, Content.EMPTY).join();
        MatcherAssert.assertThat(
            "Request line passed to slice",
            aline.get(),
            Matchers.is(line)
        );
        MatcherAssert.assertThat(
            "Scope passed as action to permissions",
            perm.get(),
            new StringContains("DockerRepositoryPermission")
        );
    }

    /**
     * Policy implementation for this test.
     * @since 1.18
     */
    static final class TestCollection extends PermissionCollection implements java.io.Serializable {

        /**
         * Required serial.
         */
        private static final long serialVersionUID = 5843247213984092155L;

        /**
         * Reference with permission.
         */
        private final AtomicReference<String> reference;

        /**
         * Ctor.
         * @param reference Reference with permission
         */
        TestCollection(final AtomicReference<String> reference) {
            this.reference = reference;
        }

        @Override
        public void add(final Permission permission) {
            throw new NotImplementedException("Not required");
        }

        @Override
        public boolean implies(final Permission permission) {
            this.reference.set(permission.toString());
            return true;
        }

        @Override
        public Enumeration<Permission> elements() {
            return null;
        }
    }
}
