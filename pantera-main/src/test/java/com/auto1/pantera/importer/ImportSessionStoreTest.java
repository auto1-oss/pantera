/*
 * The MIT License (MIT) Copyright (c) 2020-2023 pantera.com
 * https://github.com/pantera/pantera/blob/master/LICENSE.txt
 */
package com.auto1.pantera.importer;

import com.auto1.pantera.db.ArtifactDbFactory;
import com.auto1.pantera.db.PostgreSQLTestConfig;
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
