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
package com.auto1.pantera.conda.http;

import com.auto1.pantera.asto.Content;
import com.auto1.pantera.asto.Key;
import com.auto1.pantera.asto.Storage;
import com.auto1.pantera.asto.memory.InMemoryStorage;
import com.auto1.pantera.asto.test.TestResource;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.Response;
import com.auto1.pantera.http.RsStatus;
import com.auto1.pantera.http.auth.AuthUser;
import com.auto1.pantera.http.auth.TokenAuthentication;
import com.auto1.pantera.http.auth.Tokens;
import com.auto1.pantera.http.headers.Authorization;
import com.auto1.pantera.http.headers.ContentType;
import com.auto1.pantera.http.headers.Header;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.security.perms.Action;
import com.auto1.pantera.security.perms.AdapterBasicPermission;
import com.auto1.pantera.security.policy.Policy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import javax.json.Json;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.Test;

/**
 * Client flows against {@link CondaSlice} with repository-relative paths
 * (what the adapter sees once Pantera trims the repository name): package
 * downloads, token-in-URL downloads, HEAD probes and the anaconda-client
 * stage / form-POST upload.
 *
 * @since 2.2.9
 */
final class CondaSliceClientFlowTest {

    /**
     * Repository name.
     */
    private static final String REPO = "my-conda";

    /**
     * Valid token of user alice.
     */
    private static final String TOKEN = "alice-token";

    /**
     * Valid token of the read-only user bob.
     */
    private static final String BOB = "bob-token";

    /**
     * Package in the test resources.
     */
    private static final String PKG = "anaconda-navigator-1.8.4-py35_0.tar.bz2";

    /**
     * Multipart boundary.
     */
    private static final String BOUNDARY = "simple boundary";

    @Test
    void downloadsPackageByRelativePath() {
        final Storage storage = CondaSliceClientFlowTest.stored();
        final Response rsp = CondaSliceClientFlowTest.slice(storage, Policy.FREE).response(
            new RequestLine(RqMethod.GET, "/linux-64/pkg-1.0-0.tar.bz2"),
            Headers.from(new Authorization.Basic("alice", "pw")), Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            rsp.body().asString(), new IsEqual<>("package bytes")
        );
    }

    @Test
    void downloadsPackageWithTokenInUrl() {
        final Storage storage = CondaSliceClientFlowTest.stored();
        final Response rsp = new CondaUrlTokenSlice(
            CondaSliceClientFlowTest.slice(storage, Policy.FREE), false
        ).response(
            new RequestLine(
                RqMethod.GET,
                String.format("/t/%s/linux-64/pkg-1.0-0.tar.bz2", CondaSliceClientFlowTest.TOKEN)
            ),
            Headers.EMPTY, Content.EMPTY
        ).join();
        MatcherAssert.assertThat(
            rsp.body().asString(), new IsEqual<>("package bytes")
        );
    }

    @Test
    void headOfMissingFileIsNotFound() {
        MatcherAssert.assertThat(
            CondaSliceClientFlowTest.slice(new InMemoryStorage(), Policy.FREE).response(
                new RequestLine(RqMethod.HEAD, "/noarch/repodata.json.zst"),
                Headers.from(new Authorization.Basic("alice", "pw")), Content.EMPTY
            ).join().status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void headOfMissingPackageIsNotFound() {
        MatcherAssert.assertThat(
            CondaSliceClientFlowTest.slice(new InMemoryStorage(), Policy.FREE).response(
                new RequestLine(RqMethod.HEAD, "/linux-64/absent-1.0-0.tar.bz2"),
                Headers.from(new Authorization.Basic("alice", "pw")), Content.EMPTY
            ).join().status(),
            new IsEqual<>(RsStatus.NOT_FOUND)
        );
    }

    @Test
    void headOfStoredPackageIsOkWithoutBody() {
        final Response rsp = CondaSliceClientFlowTest.slice(
            CondaSliceClientFlowTest.stored(), Policy.FREE
        ).response(
            new RequestLine(RqMethod.HEAD, "/linux-64/pkg-1.0-0.tar.bz2"),
            Headers.from(new Authorization.Basic("alice", "pw")), Content.EMPTY
        ).join();
        MatcherAssert.assertThat("status", rsp.status(), new IsEqual<>(RsStatus.OK));
        MatcherAssert.assertThat("no body", rsp.body().asBytes().length, new IsEqual<>(0));
    }

    @Test
    void headRequiresCredentials() {
        MatcherAssert.assertThat(
            CondaSliceClientFlowTest.slice(
                CondaSliceClientFlowTest.stored(), Policy.FREE
            ).response(
                new RequestLine(RqMethod.HEAD, "/linux-64/pkg-1.0-0.tar.bz2"),
                Headers.from(new Authorization.Basic("alice", "wrong")), Content.EMPTY
            ).join().status(),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
    }

    @Test
    void anacondaClientUploadsThroughStagedPostUrl() throws IOException {
        final Storage storage = new InMemoryStorage();
        final CondaUrlTokenSlice slice = new CondaUrlTokenSlice(
            CondaSliceClientFlowTest.slice(storage, Policy.FREE), false
        );
        final String path = CondaSliceClientFlowTest.stage(slice, CondaSliceClientFlowTest.TOKEN);
        final Response rsp = CondaSliceClientFlowTest.formPost(slice, path);
        MatcherAssert.assertThat(
            "form POST to post_url without credentials is accepted",
            rsp.status(), new IsEqual<>(RsStatus.CREATED)
        );
        MatcherAssert.assertThat(
            "package stored under its subdir",
            storage.exists(new Key.From("linux-64", CondaSliceClientFlowTest.PKG)).join(),
            new IsEqual<>(true)
        );
    }

    @Test
    void uploadTicketIsSingleUse() throws IOException {
        final CondaUrlTokenSlice slice = new CondaUrlTokenSlice(
            CondaSliceClientFlowTest.slice(new InMemoryStorage(), Policy.FREE), false
        );
        final String path = CondaSliceClientFlowTest.stage(slice, CondaSliceClientFlowTest.TOKEN);
        CondaSliceClientFlowTest.formPost(slice, path);
        MatcherAssert.assertThat(
            CondaSliceClientFlowTest.formPost(slice, path).status(),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
    }

    @Test
    void uploadTicketIsBoundToItsPackage() throws IOException {
        final CondaUrlTokenSlice slice = new CondaUrlTokenSlice(
            CondaSliceClientFlowTest.slice(new InMemoryStorage(), Policy.FREE), false
        );
        final String path = CondaSliceClientFlowTest.stage(slice, CondaSliceClientFlowTest.TOKEN)
            .replace("linux-64", "noarch");
        MatcherAssert.assertThat(
            CondaSliceClientFlowTest.formPost(slice, path).status(),
            new IsEqual<>(RsStatus.UNAUTHORIZED)
        );
    }

    @Test
    void readOnlyUserCannotUploadWithTicket() throws IOException {
        final Storage storage = new InMemoryStorage();
        final CondaUrlTokenSlice slice = new CondaUrlTokenSlice(
            CondaSliceClientFlowTest.slice(storage, new ReadOnlyForBob()), false
        );
        final String path = CondaSliceClientFlowTest.stage(slice, CondaSliceClientFlowTest.BOB);
        MatcherAssert.assertThat(
            "read-only user's ticket upload is forbidden",
            CondaSliceClientFlowTest.formPost(slice, path).status(),
            new IsEqual<>(RsStatus.FORBIDDEN)
        );
        MatcherAssert.assertThat(
            "nothing stored",
            storage.list(Key.ROOT).join().isEmpty(), new IsEqual<>(true)
        );
    }

    /**
     * Run the authenticated stage step.
     * @param slice Slice
     * @param token User token
     * @return Repository-relative path of the returned post_url
     */
    private static String stage(final CondaUrlTokenSlice slice, final String token) {
        final Response rsp = slice.response(
            new RequestLine(
                RqMethod.POST,
                String.format(
                    "/stage/alice/anaconda-navigator/1.8.4/linux-64/%s",
                    CondaSliceClientFlowTest.PKG
                )
            ),
            Headers.from(new Header(Authorization.NAME, String.format("token %s", token))),
            Content.EMPTY
        ).join();
        final String url = Json.createReader(new StringReader(rsp.body().asString()))
            .readObject().getString("post_url");
        return URI.create(url).getPath().substring("/my-conda".length());
    }

    /**
     * The S3-style form POST anaconda-client sends: no credentials.
     * @param slice Slice
     * @param path Path
     * @return Response
     * @throws IOException On error
     */
    private static Response formPost(final CondaUrlTokenSlice slice, final String path)
        throws IOException {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(
            String.join(
                "\r\n",
                String.format("--%s", CondaSliceClientFlowTest.BOUNDARY),
                "Content-Disposition: form-data; name=\"file\"",
                "",
                ""
            ).getBytes(StandardCharsets.US_ASCII)
        );
        body.write(new TestResource(CondaSliceClientFlowTest.PKG).asBytes());
        body.write(
            String.format("\r\n--%s--", CondaSliceClientFlowTest.BOUNDARY)
                .getBytes(StandardCharsets.US_ASCII)
        );
        return slice.response(
            new RequestLine(RqMethod.POST, path),
            Headers.from(
                ContentType.mime(
                    String.format(
                        "multipart/form-data; boundary=\"%s\"", CondaSliceClientFlowTest.BOUNDARY
                    )
                )
            ),
            new Content.From(body.toByteArray())
        ).join();
    }

    /**
     * Storage with one package.
     * @return Storage
     */
    private static Storage stored() {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new Key.From("linux-64", "pkg-1.0-0.tar.bz2"),
            new Content.From("package bytes".getBytes(StandardCharsets.UTF_8))
        ).join();
        return storage;
    }

    /**
     * Conda slice with Basic user alice:pw and tokens of alice and bob.
     * @param storage Storage
     * @param policy Policy
     * @return Slice
     */
    private static CondaSlice slice(final Storage storage, final Policy<?> policy) {
        return new CondaSlice(
            storage, policy,
            (name, pass) -> "alice".equals(name) && "pw".equals(pass)
                ? Optional.of(new AuthUser("alice", "test")) : Optional.empty(),
            new FakeTokens(),
            "http://localhost/my-conda",
            CondaSliceClientFlowTest.REPO,
            Optional.empty()
        );
    }

    /**
     * Policy granting everything to alice and only READ to bob.
     * @since 2.2.9
     */
    private static final class ReadOnlyForBob implements Policy<PermissionCollection> {
        @Override
        public PermissionCollection getPermissions(final AuthUser user) {
            final Permissions perms = new Permissions();
            perms.add(
                new AdapterBasicPermission(CondaSliceClientFlowTest.REPO, Action.Standard.READ)
            );
            if (!"bob".equals(user.name())) {
                perms.add(
                    new AdapterBasicPermission(
                        CondaSliceClientFlowTest.REPO, Action.Standard.WRITE
                    )
                );
            }
            return perms;
        }
    }

    /**
     * Tokens knowing alice's and bob's tokens.
     * @since 2.2.9
     */
    private static final class FakeTokens implements Tokens {

        @Override
        public TokenAuthentication auth() {
            return token -> {
                final Optional<AuthUser> user;
                if (CondaSliceClientFlowTest.TOKEN.equals(token)) {
                    user = Optional.of(new AuthUser("alice", "test"));
                } else if (CondaSliceClientFlowTest.BOB.equals(token)) {
                    user = Optional.of(new AuthUser("bob", "test"));
                } else {
                    user = Optional.empty();
                }
                return CompletableFuture.completedFuture(user);
            };
        }

        @Override
        public String generate(final AuthUser user) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
