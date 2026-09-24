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
package com.auto1.pantera.index.reindex;

import com.auto1.pantera.backfill.ArtifactRecord;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Index table operations the rebuild needs. Every bulk operation is
 * bounded by a caller-supplied batch size so no statement holds a large
 * number of row locks.
 *
 * @since 2.2.9
 */
public interface ReindexStore {

    /**
     * Take the cluster-wide rebuild lock, if free.
     * @return Held lock, empty when another node holds it
     * @throws SQLException On database error
     */
    Optional<Lease> lock() throws SQLException;

    /**
     * Distinct repository names present in the index.
     * @return Repository names
     * @throws SQLException On database error
     */
    List<String> indexedRepos() throws SQLException;

    /**
     * Delete up to {@code limit} rows of a repository.
     * @param repo Repository name
     * @param limit Maximum rows to delete
     * @return Rows deleted
     * @throws SQLException On database error
     */
    long pruneBatch(String repo, int limit) throws SQLException;

    /**
     * Open a reconcile session for one repository. Rows that already exist
     * when the session opens, were created before {@code startedMillis} and
     * are not upserted through the session are what
     * {@link Session#removeUnseen(int)} deletes.
     * @param repo Repository name
     * @param startedMillis Rebuild start of this repository, epoch millis
     * @return Session, to be closed
     * @throws SQLException On database error
     */
    Session open(String repo, long startedMillis) throws SQLException;

    /**
     * Held cluster lock.
     * @since 2.2.9
     */
    @FunctionalInterface
    interface Lease extends AutoCloseable {
        @Override
        void close();
    }

    /**
     * Reconcile session of one repository.
     * @since 2.2.9
     */
    interface Session extends AutoCloseable {

        /**
         * Upsert a batch and mark its keys as present in storage. The batch
         * is sorted by (name, version) — the DbConsumer lock order.
         * @param batch Records of the session's repository
         * @return Rows inserted or changed
         * @throws SQLException On database error
         */
        long upsert(List<ArtifactRecord> batch) throws SQLException;

        /**
         * Delete up to {@code limit} rows of the repository that the session
         * did not upsert.
         * @param limit Maximum rows to delete
         * @return Rows deleted
         * @throws SQLException On database error
         */
        long removeUnseen(int limit) throws SQLException;

        @Override
        void close();
    }
}
