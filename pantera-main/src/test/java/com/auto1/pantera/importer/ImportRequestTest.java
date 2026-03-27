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
package com.auto1.pantera.importer;

import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.ResponseException;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.importer.api.ChecksumPolicy;
import com.auto1.pantera.importer.api.ImportHeaders;
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
