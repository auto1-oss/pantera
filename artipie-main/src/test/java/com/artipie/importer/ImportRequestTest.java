/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer;

import com.artipie.http.Headers;
import com.artipie.http.ResponseException;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.importer.api.ChecksumPolicy;
import com.artipie.importer.api.ImportHeaders;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ImportRequest}.
 */
final class ImportRequestTest {

    @Test
    void parsesValidRequest() {
        final Headers headers = new Headers()
            .add(ImportHeaders.REPO_TYPE, "maven")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "abc123")
            .add(ImportHeaders.ARTIFACT_NAME, "example")
            .add(ImportHeaders.ARTIFACT_VERSION, "1.0.0")
            .add(ImportHeaders.ARTIFACT_OWNER, "owner")
            .add(ImportHeaders.ARTIFACT_CREATED, "1700000000000")
            .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.COMPUTE.name());
        final ImportRequest request = ImportRequest.parse(
            new RequestLine(RqMethod.PUT, "/.import/my-repo/com/acme/example-1.0.0.jar"),
            headers
        );
        Assertions.assertEquals("my-repo", request.repo());
        Assertions.assertEquals("maven", request.repoType());
        Assertions.assertEquals("com/acme/example-1.0.0.jar", request.path());
        Assertions.assertEquals("example", request.artifact().orElseThrow());
        Assertions.assertEquals("1.0.0", request.version().orElseThrow());
        Assertions.assertEquals("abc123", request.idempotency());
        Assertions.assertEquals(ChecksumPolicy.COMPUTE, request.policy());
    }

    @Test
    void rejectsMissingRepository() {
        final Headers headers = new Headers()
            .add(ImportHeaders.REPO_TYPE, "npm")
            .add(ImportHeaders.IDEMPOTENCY_KEY, "key");
        Assertions.assertThrows(
            ResponseException.class,
            () -> ImportRequest.parse(
                new RequestLine(RqMethod.PUT, "/.import/repo-only"),
                headers
            )
        );
    }
}
