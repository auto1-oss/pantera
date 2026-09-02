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

import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.PostgreSQLTestConfig;
import com.auto1.pantera.http.ResponseException;
import com.auto1.pantera.http.auth.AuthzSlice;
import org.hamcrest.MatcherAssert;
import org.hamcrest.core.IsEqual;
import com.auto1.pantera.http.Headers;
import com.auto1.pantera.http.rq.RequestLine;
import com.auto1.pantera.http.rq.RqMethod;
import com.auto1.pantera.importer.api.ChecksumPolicy;
import com.auto1.pantera.importer.api.DigestType;
import com.auto1.pantera.importer.api.ImportHeaders;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration tests for {@link ImportSessionStore}.
 */
final class ImportSessionStoreTest {

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startContainer() {
        postgres = PostgreSQLTestConfig.createContainer();
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void completesSessionLifecycle() throws Exception {
        final DataSource dataSource = datasource();
        try {
            final ImportSessionStore store = new ImportSessionStore(dataSource);
            final ImportRequest request = ImportRequest.parse(
                new RequestLine(RqMethod.PUT, "/.import/db-repo/pkg/name.bin"),
                new Headers()
                    .add(ImportHeaders.REPO_TYPE, "file")
                    .add(ImportHeaders.IDEMPOTENCY_KEY, "session-1")
                    .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.METADATA.name())
            );
            final ImportSession session = store.start(request);
            Assertions.assertEquals(ImportSessionStatus.IN_PROGRESS, session.status());
            store.markCompleted(session, 42L, new EnumMap<>(DigestType.class));
            final ImportSession completed = store.start(request);
            Assertions.assertEquals(ImportSessionStatus.COMPLETED, completed.status());
        } finally {
            close(dataSource);
        }
    }

    @Test
    void recordsQuarantine() throws Exception {
        final DataSource dataSource = datasource();
        try {
            final ImportSessionStore store = new ImportSessionStore(dataSource);
            final ImportRequest request = ImportRequest.parse(
                new RequestLine(RqMethod.PUT, "/.import/db-repo/pkg/bad.bin"),
                new Headers()
                    .add(ImportHeaders.REPO_TYPE, "file")
                    .add(ImportHeaders.IDEMPOTENCY_KEY, "session-2")
                    .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.METADATA.name())
            );
            final ImportSession session = store.start(request);
            store.markQuarantined(
                session,
                128L,
                Map.of(DigestType.SHA1, "deadbeef"),
                "checksum mismatch",
                ".import/quarantine/session-2"
            );
            final ImportSession quarantined = store.start(request);
            Assertions.assertEquals(ImportSessionStatus.QUARANTINED, quarantined.status());
        } finally {
            close(dataSource);
        }
    }

    @Test
    void idempotencyKeyCannotBeReplayedAgainstAnotherTarget() throws Exception {
        // SECURITY (2.2.9): before the fix the session was looked up by the
        // idempotency key alone, so re-sending a COMPLETED key with a
        // different repository/path returned the foreign session's terminal
        // state and the importer answered ALREADY_PRESENT — silently
        // short-circuiting an import into repo B on the strength of repo A's key.
        final DataSource dataSource = datasource();
        try {
            final ImportSessionStore store = new ImportSessionStore(dataSource);
            final ImportRequest first = ImportRequest.parse(
                new RequestLine(RqMethod.PUT, "/.import/repo-a/pkg/one.bin"),
                new Headers()
                    .add(ImportHeaders.REPO_TYPE, "file")
                    .add(ImportHeaders.IDEMPOTENCY_KEY, "shared-key")
                    .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.METADATA.name())
                    .add(AuthzSlice.LOGIN_HDR, "alice")
            );
            store.markCompleted(store.start(first), 1L, new EnumMap<>(DigestType.class));
            final ImportRequest other = ImportRequest.parse(
                new RequestLine(RqMethod.PUT, "/.import/repo-b/pkg/two.bin"),
                new Headers()
                    .add(ImportHeaders.REPO_TYPE, "file")
                    .add(ImportHeaders.IDEMPOTENCY_KEY, "shared-key")
                    .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.METADATA.name())
                    .add(AuthzSlice.LOGIN_HDR, "alice")
            );
            final ResponseException rejected = Assertions.assertThrows(
                ResponseException.class, () -> store.start(other)
            );
            MatcherAssert.assertThat(
                "an idempotency key already bound to repo-a/one.bin must be refused (409) for repo-b/two.bin",
                rejected.response().status().code(), new IsEqual<>(409)
            );
        } finally {
            close(dataSource);
        }
    }

    @Test
    void idempotencyKeyIsBoundToTheAuthenticatedCaller() throws Exception {
        // SECURITY (2.2.9): the key must also be bound to the principal that
        // created it — another writer on the same repository must not be able
        // to resume, short-circuit or inherit a session by guessing its key.
        final DataSource dataSource = datasource();
        try {
            final ImportSessionStore store = new ImportSessionStore(dataSource);
            final Headers base = new Headers()
                .add(ImportHeaders.REPO_TYPE, "file")
                .add(ImportHeaders.IDEMPOTENCY_KEY, "caller-key")
                .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.METADATA.name());
            final ImportRequest alice = ImportRequest.parse(
                new RequestLine(RqMethod.PUT, "/.import/repo-a/pkg/three.bin"),
                base.copy().add(AuthzSlice.LOGIN_HDR, "alice")
            );
            store.start(alice);
            final ImportRequest bob = ImportRequest.parse(
                new RequestLine(RqMethod.PUT, "/.import/repo-a/pkg/three.bin"),
                base.copy().add(AuthzSlice.LOGIN_HDR, "bob")
            );
            final ResponseException rejected = Assertions.assertThrows(
                ResponseException.class, () -> store.start(bob)
            );
            MatcherAssert.assertThat(
                "a different caller reusing alice's idempotency key must be refused (409)",
                rejected.response().status().code(), new IsEqual<>(409)
            );
        } finally {
            close(dataSource);
        }
    }

    private static DataSource datasource() {
        final String yaml = String.join(
            "\n",
            "artifacts_database:",
            String.format("  postgres_host: %s", postgres.getHost()),
            String.format("  postgres_port: %d", postgres.getMappedPort(5432)),
            String.format("  postgres_database: %s", postgres.getDatabaseName()),
            String.format("  postgres_user: %s", postgres.getUsername()),
            String.format("  postgres_password: %s", postgres.getPassword())
        );
        try {
            final ArtifactDbFactory factory = new ArtifactDbFactory(
                com.amihaiemil.eoyaml.Yaml.createYamlInput(yaml).readYamlMapping(),
                postgres.getDatabaseName()
            );
            return factory.initialize();
        } catch (final IOException err) {
            throw new IllegalStateException("Failed to read configuration", err);
        }
    }

    private static void close(final DataSource dataSource) {
        if (dataSource instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (final Exception ignored) {
                // ignore
            }
        }
    }
}
