/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.http;

import com.artipie.asto.Concatenation;
import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.asto.Remaining;
import com.artipie.http.auth.AuthUser;
import com.artipie.http.auth.Authentication;
import com.artipie.http.headers.Authorization;
import com.artipie.http.headers.ContentType;
import com.artipie.http.headers.Header;
import com.artipie.http.hm.RsHasBody;
import com.artipie.http.hm.RsHasHeaders;
import com.artipie.http.hm.RsHasStatus;
import com.artipie.http.hm.SliceHasResponse;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.slice.KeyFromPath;
import com.artipie.scheduling.ArtifactEvent;
import com.artipie.security.policy.Policy;
import com.artipie.security.policy.PolicyByUsername;
import hu.akarnokd.rxjava2.interop.SingleInterop;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.AllOf;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Test for {@link GoSlice}.
 */
class GoSliceTest {

    /**
     * Test user.
     */
    private static final Pair<String, String> USER = new ImmutablePair<>("Alladin", "openSesame");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsInfo(final boolean anonymous) throws Exception {
        final String path = "news.info/some/day/@v/v0.1.info";
        final String body = "{\"Version\":\"0.1\",\"Time\":\"2020-01-24T00:54:14Z\"}";
        MatcherAssert.assertThat(
            this.slice(GoSliceTest.storage(path, body), anonymous),
            new SliceHasResponse(
                anonymous
                    ? unauthorized()
                    : success(body, ContentType.json()),
                GoSliceTest.line(path), this.headers(anonymous), Content.EMPTY
            )
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsMod(final boolean anonymous) throws Exception {
        final String path = "example.com/mod/one/@v/v1.mod";
        final String body = "bla-bla";
        MatcherAssert.assertThat(
            this.slice(GoSliceTest.storage(path, body), anonymous),
            new SliceHasResponse(
                anonymous
                    ? unauthorized()
                    : success(body, ContentType.text()),
                GoSliceTest.line(path), this.headers(anonymous), Content.EMPTY
            )
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsZip(final boolean anonymous) throws Exception {
        final String path = "modules.zip/foo/bar/@v/v1.0.9.zip";
        final String body = "smth";
        MatcherAssert.assertThat(
            this.slice(GoSliceTest.storage(path, body), anonymous),
            new SliceHasResponse(
                anonymous
                    ? unauthorized()
                    : success(body, ContentType.mime("application/zip")),
                GoSliceTest.line(path), this.headers(anonymous), Content.EMPTY
            )
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsList(final boolean anonymous) throws Exception {
        final String path = "example.com/list/bar/@v/list";
        final String body = "v1.2.3";
        MatcherAssert.assertThat(
            this.slice(GoSliceTest.storage(path, body), anonymous),
            new SliceHasResponse(
                anonymous
                    ? unauthorized()
                    : success(body, ContentType.text()),
                GoSliceTest.line(path), this.headers(anonymous), Content.EMPTY
            )
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void fallbacks(final boolean anonymous) throws Exception {
        final String path = "example.com/abc/def";
        final String body = "v1.8.3";
        MatcherAssert.assertThat(
            this.slice(GoSliceTest.storage(path, body), anonymous),
            new SliceHasResponse(
                anonymous ? unauthorized() : new RsHasStatus(RsStatus.NOT_FOUND),
                GoSliceTest.line(path), this.headers(anonymous), Content.EMPTY
            )
        );
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void returnsLatest(final boolean anonymous) throws Exception {
        final String body = "{\"Version\":\"1.1\",\"Time\":\"2020-01-24T00:54:14Z\"}";
        MatcherAssert.assertThat(
            this.slice(GoSliceTest.storage("example.com/latest/bar/@v/v1.1.info", body), anonymous),
            new SliceHasResponse(
                anonymous
                    ? unauthorized()
                    : success(body, ContentType.json()),
                GoSliceTest.line("example.com/latest/bar/@latest"),
                this.headers(anonymous), Content.EMPTY
            )
        );
    }

    @Test
    void uploadsZipStoresContentAndRecordsMetadata() throws Exception {
        final Storage storage = new InMemoryStorage();
        final Queue<ArtifactEvent> events = new ConcurrentLinkedQueue<>();
        final GoSlice slice = new GoSlice(
            storage,
            new PolicyByUsername(USER.getKey()),
            new Authentication.Single(USER.getKey(), USER.getValue()),
            "go-repo",
            Optional.of(events)
        );
        final byte[] data = "zip-content".getBytes(StandardCharsets.UTF_8);
        final Response response = slice.response(
            new RequestLine("PUT", "example.com/hello/@v/v1.2.3.zip"),
            Headers.from(
                new Authorization.Basic(USER.getKey(), USER.getValue())
            ),
            new Content.From(data)
        ).toCompletableFuture().get();
        MatcherAssert.assertThat(response, new RsHasStatus(RsStatus.CREATED));
        final Key key = new KeyFromPath("example.com/hello/@v/v1.2.3.zip");
        final byte[] stored = new Concatenation(
            storage.value(key).toCompletableFuture().join()
        ).single()
            .map(Remaining::new)
            .map(Remaining::bytes)
            .to(SingleInterop.get())
            .toCompletableFuture()
            .join();
        MatcherAssert.assertThat(
            new String(stored, StandardCharsets.UTF_8),
            IsEqual.equalTo("zip-content")
        );
        final ArtifactEvent event = events.poll();
        org.junit.jupiter.api.Assertions.assertNotNull(event, "Artifact event should be recorded");
        org.junit.jupiter.api.Assertions.assertEquals("go", event.repoType());
        org.junit.jupiter.api.Assertions.assertEquals("go-repo", event.repoName());
        org.junit.jupiter.api.Assertions.assertEquals("example.com/hello", event.artifactName());
        org.junit.jupiter.api.Assertions.assertEquals("1.2.3", event.artifactVersion());
        org.junit.jupiter.api.Assertions.assertEquals(data.length, event.size());
        org.junit.jupiter.api.Assertions.assertEquals(USER.getKey(), event.owner());
        final Key list = new Key.From("example.com/hello/@v/list");
        org.junit.jupiter.api.Assertions.assertTrue(
            storage.exists(list).toCompletableFuture().join(),
            "List file should exist"
        );
        final String versions = new String(
            new Concatenation(storage.value(list).toCompletableFuture().join()).single()
                .map(Remaining::new)
                .map(Remaining::bytes)
                .to(SingleInterop.get())
                .toCompletableFuture()
                .join(),
            StandardCharsets.UTF_8
        );
        org.junit.jupiter.api.Assertions.assertTrue(
            versions.contains("v1.2.3"),
            "List file should contain uploaded version"
        );
    }

    /**
     * Constructs {@link GoSlice}.
     * @param storage Storage
     * @param anonymous Is authorisation required?
     * @return Instance of {@link GoSlice}
     */
    private GoSlice slice(final Storage storage, final boolean anonymous) {
        if (anonymous) {
            return new GoSlice(storage, Policy.FREE, (name, pswd) -> Optional.of(AuthUser.ANONYMOUS), "test");
        }
        return new GoSlice(storage,
            new PolicyByUsername(USER.getKey()),
            new Authentication.Single(USER.getKey(), USER.getValue()),
            "test"
        );
    }

    private Headers headers(final boolean anonymous) {
        return anonymous ? Headers.EMPTY : Headers.from(
            new Authorization.Basic(GoSliceTest.USER.getKey(), GoSliceTest.USER.getValue())
        );
    }

    /**
     * Composes matchers.
     * @param body Body
     * @param header Content-type
     * @return List of matchers
     */
    private static AllOf<Response> success(String body, Header header) {
        return new AllOf<>(
            new RsHasStatus(RsStatus.OK),
            new RsHasBody(body.getBytes()),
            new RsHasHeaders(header)
        );
    }

    private static AllOf<Response> unauthorized() {
        return new AllOf<>(
            new RsHasStatus(RsStatus.UNAUTHORIZED),
            new RsHasHeaders(new Header("WWW-Authenticate", "Basic realm=\"artipie\""))
        );
    }

    /**
     * Request line.
     * @param path Path
     * @return Proper request line
     */
    private static RequestLine line(final String path) {
        return new RequestLine("GET", path);
    }

    /**
     * Composes storage.
     * @param path Where to store
     * @param body Body to store
     * @return Storage
     * @throws ExecutionException On error
     * @throws InterruptedException On error
     */
    private static Storage storage(final String path, final String body)
        throws ExecutionException, InterruptedException {
        final Storage storage = new InMemoryStorage();
        storage.save(
            new KeyFromPath(path),
            new Content.From(body.getBytes())
        ).get();
        return storage;
    }

}
