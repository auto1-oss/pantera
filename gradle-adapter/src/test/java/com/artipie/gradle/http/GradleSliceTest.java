/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.gradle.http;

import com.artipie.asto.Content;
import com.artipie.asto.Key;
import com.artipie.asto.Storage;
import com.artipie.asto.memory.InMemoryStorage;
import com.artipie.asto.test.ContentIs;
import com.artipie.http.Headers;
import com.artipie.http.hm.RsHasStatus;
import com.artipie.http.hm.SliceHasResponse;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.http.RsStatus;
import com.artipie.http.auth.AuthUser;
import com.artipie.security.policy.PolicyByUsername;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * Test for {@link GradleSlice}.
 *
 * @since 1.0
 */
class GradleSliceTest {

    private Storage storage;

    @BeforeEach
    void setUp() {
        this.storage = new InMemoryStorage();
    }

    @Test
    void getsExistingArtifact() {
        final Key key = new Key.From("com/example/mylib/1.0/mylib-1.0.jar");
        this.storage.save(key, new Content.From("jar content".getBytes(StandardCharsets.UTF_8))).join();
        
        MatcherAssert.assertThat(
            new GradleSlice(
                this.storage,
                new PolicyByUsername("alice"),
                (username, password) -> Optional.of(new AuthUser(username, "test")),
                "gradle-test",
                Optional.empty()
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.GET, "/com/example/mylib/1.0/mylib-1.0.jar"),
                Headers.from("Authorization", "Basic YWxpY2U6MTIz"),
                Content.EMPTY
            )
        );
    }

    @Test
    void returnsNotFoundForMissingArtifact() {
        MatcherAssert.assertThat(
            new GradleSlice(
                this.storage,
                new PolicyByUsername("alice"),
                (username, password) -> Optional.of(new AuthUser(username, "test")),
                "gradle-test",
                Optional.empty()
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.NOT_FOUND),
                new RequestLine(RqMethod.GET, "/com/example/missing/1.0/missing-1.0.jar"),
                Headers.from("Authorization", "Basic YWxpY2U6MTIz"),
                Content.EMPTY
            )
        );
    }

    @Test
    void headRequestForExistingArtifact() {
        final Key key = new Key.From("com/example/mylib/1.0/mylib-1.0.jar");
        this.storage.save(key, new Content.From("jar content".getBytes(StandardCharsets.UTF_8))).join();
        
        MatcherAssert.assertThat(
            new GradleSlice(
                this.storage,
                new PolicyByUsername("alice"),
                (username, password) -> Optional.of(new AuthUser(username, "test")),
                "gradle-test",
                Optional.empty()
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.OK),
                new RequestLine(RqMethod.HEAD, "/com/example/mylib/1.0/mylib-1.0.jar"),
                Headers.from("Authorization", "Basic YWxpY2U6MTIz"),
                Content.EMPTY
            )
        );
    }

    @Test
    void uploadsArtifact() {
        MatcherAssert.assertThat(
            new GradleSlice(
                this.storage,
                new PolicyByUsername("alice"),
                (username, password) -> Optional.of(new AuthUser(username, "test")),
                "gradle-test",
                Optional.empty()
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine(RqMethod.PUT, "/com/example/mylib/1.0/mylib-1.0.jar"),
                Headers.from("Authorization", "Basic YWxpY2U6MTIz"),
                new Content.From("jar content".getBytes(StandardCharsets.UTF_8))
            )
        );
    }

    @Test
    void stripsMetadataPropertiesFromFilename() {
        // Test that semicolon-separated metadata properties are stripped from the filename
        // to avoid exceeding filesystem filename length limits (typically 255 bytes)
        final byte[] data = "gradle artifact content".getBytes(StandardCharsets.UTF_8);
        final String pathWithMetadata =
            "/com/example/mylib/1.0.0-395-202511111100/" +
            "mylib-1.0.0-395-202511111100.jar;" +
            "vcs.revision=6177d00b21602d4a23f004ce5bd1dc56e5154ed4;" +
            "build.timestamp=1762855225704;" +
            "build.name=gradle-build+::+mylib-build-deploy+::+master;" +
            "build.number=395;" +
            "vcs.branch=master;" +
            "vcs.url=git@github.com:example/mylib.git";

        MatcherAssert.assertThat(
            "Wrong response status, CREATED is expected",
            new GradleSlice(
                this.storage,
                new PolicyByUsername("alice"),
                (username, password) -> Optional.of(new AuthUser(username, "test")),
                "gradle-test",
                Optional.empty()
            ),
            new SliceHasResponse(
                new RsHasStatus(RsStatus.CREATED),
                new RequestLine(RqMethod.PUT, pathWithMetadata),
                Headers.from("Authorization", "Basic YWxpY2U6MTIz"),
                new Content.From(data)
            )
        );

        // Verify the file was saved WITHOUT the metadata properties
        final Key expectedKey = new Key.From(
            "com/example/mylib/1.0.0-395-202511111100/" +
            "mylib-1.0.0-395-202511111100.jar"
        );
        MatcherAssert.assertThat(
            "Uploaded data should be saved without metadata properties",
            this.storage.value(expectedKey).join(),
            new ContentIs(data)
        );
    }
}
