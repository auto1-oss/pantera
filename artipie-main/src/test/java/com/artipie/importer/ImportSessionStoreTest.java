/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.artipie.importer;

import com.amihaiemil.eoyaml.Yaml;
import com.artipie.db.ArtifactDbFactory;
import com.artipie.db.SharedPostgreSQLContainer;
import com.artipie.http.Headers;
import com.artipie.http.rq.RequestLine;
import com.artipie.http.rq.RqMethod;
import com.artipie.importer.api.ChecksumPolicy;
import com.artipie.importer.api.DigestType;
import com.artipie.importer.api.ImportHeaders;
import java.util.EnumMap;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Integration tests for {@link ImportSessionStore}.
 */
final class ImportSessionStoreTest {

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
                            .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.METADATA.name()));
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
                            .add(ImportHeaders.CHECKSUM_POLICY, ChecksumPolicy.METADATA.name()));
            final ImportSession session = store.start(request);
            store.markQuarantined(
                    session,
                    128L,
                    Map.of(DigestType.SHA1, "deadbeef"),
                    "checksum mismatch",
                    ".import/quarantine/session-2");
            final ImportSession quarantined = store.start(request);
            Assertions.assertEquals(ImportSessionStatus.QUARANTINED, quarantined.status());
        } finally {
            close(dataSource);
        }
    }

    private static DataSource datasource() {
        PostgreSQLContainer<?> postgres = SharedPostgreSQLContainer.getInstance();
        return new ArtifactDbFactory(
                Yaml.createYamlMappingBuilder().add(
                        "artifacts_database",
                        Yaml.createYamlMappingBuilder()
                                .add(ArtifactDbFactory.YAML_HOST, postgres.getHost())
                                .add(ArtifactDbFactory.YAML_PORT, String.valueOf(postgres.getFirstMappedPort()))
                                .add(ArtifactDbFactory.YAML_DATABASE, postgres.getDatabaseName())
                                .add(ArtifactDbFactory.YAML_USER, postgres.getUsername())
                                .add(ArtifactDbFactory.YAML_PASSWORD, postgres.getPassword())
                                .build())
                        .build(),
                postgres.getDatabaseName()).initialize();
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
