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
package com.auto1.pantera.audit;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.hamcrest.core.IsEqual;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;

/**
 * Unit tests for {@link JdbcAuditService}. Uses a hand-rolled stub
 * {@link DataSource} that captures the prepared statement parameters
 * — no TestContainers, so this stays a real {@code *Test.java} unit
 * test under the project's no-DB-in-unit-tests rule.
 *
 * @since 2.2.0
 */
final class JdbcAuditServiceTest {

    @Test
    @DisplayName("record() writes every AuditEvent field into the prepared statement")
    void recordWritesAllFields() throws Exception {
        final CapturingDataSource source = new CapturingDataSource();
        final JdbcAuditService service = new JdbcAuditService(source);
        final Instant when = Instant.parse("2026-05-14T10:00:00Z");
        final Map<String, Object> details = new HashMap<>();
        details.put("repository.type", "maven");
        details.put("package.version", "1.0.0");
        final AuditEvent event = new AuditEvent(
            when, "alice@example.com", "COOLDOWN_UNBLOCK", "maven-central",
            details, true, "203.0.113.1"
        );

        service.record(event).get();

        final CapturedCall call = source.last();
        assertThat(
            "reason: timestamp arg matches event",
            call.timestamp, new IsEqual<>(Timestamp.from(when))
        );
        assertThat(
            "reason: actor arg matches event",
            call.actor, new IsEqual<>("alice@example.com")
        );
        assertThat(
            "reason: action arg matches event",
            call.action, new IsEqual<>("COOLDOWN_UNBLOCK")
        );
        assertThat(
            "reason: target arg matches event",
            call.target, new IsEqual<>("maven-central")
        );
        assertThat(
            "reason: success arg matches event",
            call.success, new IsEqual<>(true)
        );
        assertThat(
            "reason: IP arg matches event",
            call.ipAddress, new IsEqual<>("203.0.113.1")
        );
        assertThat(
            "reason: details renders as JSON containing the key/value pairs",
            call.detailsJson.contains("\"repository.type\":\"maven\"")
                && call.detailsJson.contains("\"package.version\":\"1.0.0\""),
            new IsEqual<>(true)
        );
    }

    @Test
    @DisplayName("record() with empty details renders {} JSON")
    void emptyDetailsRendersEmptyObject() throws Exception {
        final CapturingDataSource source = new CapturingDataSource();
        final JdbcAuditService service = new JdbcAuditService(source);

        service.record(AuditEvent.success("alice", "X", "y")).get();

        assertThat(
            source.last().detailsJson, new IsEqual<>("{}")
        );
    }

    @Test
    @DisplayName("record() escapes special characters in details JSON")
    void recordEscapesSpecialCharacters() throws Exception {
        final CapturingDataSource source = new CapturingDataSource();
        final JdbcAuditService service = new JdbcAuditService(source);
        final Map<String, Object> details = new HashMap<>();
        details.put("error", "line\nbreak \"quoted\" \\back");

        service.record(new AuditEvent(
            Instant.now(), "alice", "X", "y", details, false, null
        )).get();

        final String json = source.last().detailsJson;
        assertThat(
            "reason: newline escaped",
            json.contains("\\n"), new IsEqual<>(true)
        );
        assertThat(
            "reason: quote escaped",
            json.contains("\\\""), new IsEqual<>(true)
        );
        assertThat(
            "reason: backslash escaped",
            json.contains("\\\\"), new IsEqual<>(true)
        );
        assertThat(
            "reason: raw newline NOT present in JSON",
            json.contains("\nbreak"), new IsEqual<>(false)
        );
    }

    @Test
    @DisplayName("record() failure surfaces via the returned future but does not throw")
    void recordFailureSurfacedViaFuture() throws Exception {
        final FailingDataSource source = new FailingDataSource();
        final JdbcAuditService service = new JdbcAuditService(source);
        boolean failed;
        try {
            service.record(AuditEvent.success("a", "X", "y")).get();
            failed = false;
        } catch (final CompletionException | java.util.concurrent.ExecutionException ex) {
            failed = true;
        }
        assertThat(
            "reason: future failed (SQL exception surfaces)",
            failed, new IsEqual<>(true)
        );
    }

    @Test
    @DisplayName("AuditService.noop() always returns a completed future")
    void noopServiceReturnsCompletedFuture() throws Exception {
        final AuditService noop = AuditService.noop();
        noop.record(AuditEvent.success("a", "X", "y")).get();
    }

    @Test
    @DisplayName("Registry returns no-op when no service installed")
    void registryDefaultsToNoop() throws Exception {
        final AuditServiceRegistry reg = AuditServiceRegistry.instance();
        reg.clear();
        try {
            assertThat(reg.isSharedServiceSet(), new IsEqual<>(false));
            // Must not NPE — returns no-op
            reg.sharedService().record(
                AuditEvent.success("a", "X", "y")
            ).get();
        } finally {
            reg.clear();
        }
    }

    @Test
    @DisplayName("Registry stores and retrieves an installed service")
    void registryStoresInstalledService() {
        final AuditServiceRegistry reg = AuditServiceRegistry.instance();
        reg.clear();
        try {
            final AtomicReference<AuditEvent> seen = new AtomicReference<>();
            final AuditService capturing = event -> {
                seen.set(event);
                return java.util.concurrent.CompletableFuture
                    .completedFuture(null);
            };
            reg.setSharedService(capturing);
            assertThat(reg.isSharedServiceSet(), new IsEqual<>(true));
            reg.sharedService().record(
                AuditEvent.success("a", "X", "y")
            );
            assertThat(
                "reason: registered service was invoked",
                seen.get() != null, new IsEqual<>(true)
            );
        } finally {
            reg.clear();
        }
    }

    // ===== stubs =====

    /**
     * Records the parameters passed to a single
     * {@link PreparedStatement#executeUpdate} call.
     */
    private record CapturedCall(
        Timestamp timestamp, String actor, String action, String target,
        String detailsJson, boolean success, String ipAddress
    ) {
    }

    /**
     * Capturing DataSource — only the methods used by JdbcAuditService are
     * implemented; everything else throws.
     */
    private static final class CapturingDataSource extends NotImplementedDataSource {
        private final List<CapturedCall> captured = new ArrayList<>();

        CapturedCall last() {
            return this.captured.get(this.captured.size() - 1);
        }

        @Override
        public Connection getConnection() {
            return new CapturingConnection(this.captured);
        }
    }

    /**
     * DataSource whose {@code getConnection} always throws.
     */
    private static final class FailingDataSource extends NotImplementedDataSource {
        @Override
        public Connection getConnection() throws SQLException {
            throw new SQLException("simulated failure");
        }
    }

    /**
     * Connection that returns a capturing PreparedStatement.
     */
    private static final class CapturingConnection extends NotImplementedConnection {
        private final List<CapturedCall> captured;

        CapturingConnection(final List<CapturedCall> captured) {
            this.captured = captured;
        }

        @Override
        public PreparedStatement prepareStatement(final String sql) {
            return new CapturingPreparedStatement(this.captured);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    /**
     * PreparedStatement that captures the args set on it then completes the
     * executeUpdate call.
     */
    private static final class CapturingPreparedStatement
        extends NotImplementedPreparedStatement {
        private final List<CapturedCall> captured;
        private final Object[] args = new Object[9];

        CapturingPreparedStatement(final List<CapturedCall> captured) {
            this.captured = captured;
        }

        @Override
        public void setTimestamp(final int index, final Timestamp value) {
            this.args[index] = value;
        }

        @Override
        public void setNull(final int index, final int sqlType) {
            this.args[index] = null;
        }

        @Override
        public void setString(final int index, final String value) {
            this.args[index] = value;
        }

        @Override
        public void setBoolean(final int index, final boolean value) {
            this.args[index] = value;
        }

        @Override
        public int executeUpdate() {
            this.captured.add(new CapturedCall(
                (Timestamp) this.args[1],
                (String) this.args[2],
                (String) this.args[3],
                (String) this.args[5],
                (String) this.args[6],
                (Boolean) this.args[7],
                (String) this.args[8]
            ));
            return 1;
        }

        @Override
        public void close() {
            // no-op
        }
    }

    /**
     * Base class — every method throws {@link UnsupportedOperationException}.
     * Subclasses override only the few methods exercised by the test.
     */
    private static class NotImplementedDataSource implements DataSource {
        @Override public Connection getConnection() throws SQLException {
            throw new UnsupportedOperationException();
        }
        @Override public Connection getConnection(final String u, final String p) {
            throw new UnsupportedOperationException();
        }
        @Override public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException();
        }
        @Override public void setLogWriter(final PrintWriter writer) {
            throw new UnsupportedOperationException();
        }
        @Override public void setLoginTimeout(final int seconds) {
            throw new UnsupportedOperationException();
        }
        @Override public int getLoginTimeout() {
            throw new UnsupportedOperationException();
        }
        @Override public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }
        @Override public <T> T unwrap(final Class<T> iface) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean isWrapperFor(final Class<?> iface) {
            return false;
        }
    }

    /**
     * Base class for stub connections — every method throws.
     */
    private static class NotImplementedConnection implements Connection {
        @Override public java.sql.Statement createStatement() {
            throw new UnsupportedOperationException();
        }
        @Override public PreparedStatement prepareStatement(final String sql) {
            throw new UnsupportedOperationException();
        }
        @Override public java.sql.CallableStatement prepareCall(final String sql) {
            throw new UnsupportedOperationException();
        }
        @Override public String nativeSQL(final String sql) {
            throw new UnsupportedOperationException();
        }
        @Override public void setAutoCommit(final boolean autoCommit) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean getAutoCommit() {
            return true;
        }
        @Override public void commit() {
            throw new UnsupportedOperationException();
        }
        @Override public void rollback() {
            throw new UnsupportedOperationException();
        }
        @Override public void close() {
            throw new UnsupportedOperationException();
        }
        @Override public boolean isClosed() {
            return false;
        }
        @Override public DatabaseMetaData getMetaData() {
            throw new UnsupportedOperationException();
        }
        @Override public void setReadOnly(final boolean readOnly) { }
        @Override public boolean isReadOnly() {
            return false;
        }
        @Override public void setCatalog(final String catalog) { }
        @Override public String getCatalog() {
            return null;
        }
        @Override public void setTransactionIsolation(final int level) { }
        @Override public int getTransactionIsolation() {
            return TRANSACTION_NONE;
        }
        @Override public java.sql.SQLWarning getWarnings() {
            return null;
        }
        @Override public void clearWarnings() { }
        @Override public java.sql.Statement createStatement(final int t, final int c) {
            throw new UnsupportedOperationException();
        }
        @Override public PreparedStatement prepareStatement(
            final String sql, final int t, final int c) {
            throw new UnsupportedOperationException();
        }
        @Override public java.sql.CallableStatement prepareCall(
            final String sql, final int t, final int c) {
            throw new UnsupportedOperationException();
        }
        @Override public Map<String, Class<?>> getTypeMap() {
            return null;
        }
        @Override public void setTypeMap(final Map<String, Class<?>> map) { }
        @Override public void setHoldability(final int holdability) { }
        @Override public int getHoldability() {
            return 0;
        }
        @Override public java.sql.Savepoint setSavepoint() {
            throw new UnsupportedOperationException();
        }
        @Override public java.sql.Savepoint setSavepoint(final String name) {
            throw new UnsupportedOperationException();
        }
        @Override public void rollback(final java.sql.Savepoint savepoint) { }
        @Override public void releaseSavepoint(final java.sql.Savepoint savepoint) { }
        @Override public java.sql.Statement createStatement(
            final int t, final int c, final int h) {
            throw new UnsupportedOperationException();
        }
        @Override public PreparedStatement prepareStatement(
            final String sql, final int t, final int c, final int h) {
            throw new UnsupportedOperationException();
        }
        @Override public java.sql.CallableStatement prepareCall(
            final String sql, final int t, final int c, final int h) {
            throw new UnsupportedOperationException();
        }
        @Override public PreparedStatement prepareStatement(
            final String sql, final int autoKeys) {
            throw new UnsupportedOperationException();
        }
        @Override public PreparedStatement prepareStatement(
            final String sql, final int[] keys) {
            throw new UnsupportedOperationException();
        }
        @Override public PreparedStatement prepareStatement(
            final String sql, final String[] keys) {
            throw new UnsupportedOperationException();
        }
        @Override public java.sql.Clob createClob() {
            throw new UnsupportedOperationException();
        }
        @Override public java.sql.Blob createBlob() {
            throw new UnsupportedOperationException();
        }
        @Override public java.sql.NClob createNClob() {
            throw new UnsupportedOperationException();
        }
        @Override public java.sql.SQLXML createSQLXML() {
            throw new UnsupportedOperationException();
        }
        @Override public boolean isValid(final int timeout) {
            return true;
        }
        @Override public void setClientInfo(
            final String name, final String value) { }
        @Override public void setClientInfo(final java.util.Properties props) { }
        @Override public String getClientInfo(final String name) {
            return null;
        }
        @Override public java.util.Properties getClientInfo() {
            return new java.util.Properties();
        }
        @Override public java.sql.Array createArrayOf(
            final String type, final Object[] elements) {
            throw new UnsupportedOperationException();
        }
        @Override public java.sql.Struct createStruct(
            final String type, final Object[] attrs) {
            throw new UnsupportedOperationException();
        }
        @Override public void setSchema(final String schema) { }
        @Override public String getSchema() {
            return null;
        }
        @Override public void abort(final java.util.concurrent.Executor exec) { }
        @Override public void setNetworkTimeout(
            final java.util.concurrent.Executor exec, final int ms) { }
        @Override public int getNetworkTimeout() {
            return 0;
        }
        @Override public <T> T unwrap(final Class<T> iface) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean isWrapperFor(final Class<?> iface) {
            return false;
        }
    }

    /**
     * Base class for stub prepared statements — every method throws.
     */
    private abstract static class NotImplementedPreparedStatement
        implements PreparedStatement {
        @Override public java.sql.ResultSet executeQuery() {
            throw new UnsupportedOperationException();
        }
        @Override public void setNull(final int p, final int t) { }
        @Override public void setBoolean(final int p, final boolean v) { }
        @Override public void setByte(final int p, final byte v) { }
        @Override public void setShort(final int p, final short v) { }
        @Override public void setInt(final int p, final int v) { }
        @Override public void setLong(final int p, final long v) { }
        @Override public void setFloat(final int p, final float v) { }
        @Override public void setDouble(final int p, final double v) { }
        @Override public void setBigDecimal(final int p, final java.math.BigDecimal v) { }
        @Override public void setString(final int p, final String v) { }
        @Override public void setBytes(final int p, final byte[] v) { }
        @Override public void setDate(final int p, final java.sql.Date v) { }
        @Override public void setTime(final int p, final java.sql.Time v) { }
        @Override public void setTimestamp(final int p, final Timestamp v) { }
        @Override public void setAsciiStream(
            final int p, final java.io.InputStream v, final int len) { }
        @Override public void setBinaryStream(
            final int p, final java.io.InputStream v, final int len) { }
        @Override public void setUnicodeStream(
            final int p, final java.io.InputStream v, final int len) { }
        @Override public void clearParameters() { }
        @Override public void setObject(final int p, final Object v, final int t) { }
        @Override public void setObject(final int p, final Object v) { }
        @Override public boolean execute() {
            return false;
        }
        @Override public void addBatch() { }
        @Override public void setCharacterStream(
            final int p, final java.io.Reader v, final int len) { }
        @Override public void setRef(final int p, final java.sql.Ref v) { }
        @Override public void setBlob(final int p, final java.sql.Blob v) { }
        @Override public void setClob(final int p, final java.sql.Clob v) { }
        @Override public void setArray(final int p, final java.sql.Array v) { }
        @Override public java.sql.ResultSetMetaData getMetaData() {
            return null;
        }
        @Override public void setDate(
            final int p, final java.sql.Date v, final java.util.Calendar cal) { }
        @Override public void setTime(
            final int p, final java.sql.Time v, final java.util.Calendar cal) { }
        @Override public void setTimestamp(
            final int p, final Timestamp v, final java.util.Calendar cal) { }
        @Override public void setNull(
            final int p, final int t, final String typeName) { }
        @Override public void setURL(final int p, final java.net.URL v) { }
        @Override public java.sql.ParameterMetaData getParameterMetaData() {
            return null;
        }
        @Override public void setRowId(final int p, final java.sql.RowId v) { }
        @Override public void setNString(final int p, final String v) { }
        @Override public void setNCharacterStream(
            final int p, final java.io.Reader v, final long len) { }
        @Override public void setNClob(final int p, final java.sql.NClob v) { }
        @Override public void setClob(
            final int p, final java.io.Reader v, final long len) { }
        @Override public void setBlob(
            final int p, final java.io.InputStream v, final long len) { }
        @Override public void setNClob(
            final int p, final java.io.Reader v, final long len) { }
        @Override public void setSQLXML(final int p, final java.sql.SQLXML v) { }
        @Override public void setObject(
            final int p, final Object v, final int t, final int len) { }
        @Override public void setAsciiStream(
            final int p, final java.io.InputStream v, final long len) { }
        @Override public void setBinaryStream(
            final int p, final java.io.InputStream v, final long len) { }
        @Override public void setCharacterStream(
            final int p, final java.io.Reader v, final long len) { }
        @Override public void setAsciiStream(final int p, final java.io.InputStream v) { }
        @Override public void setBinaryStream(final int p, final java.io.InputStream v) { }
        @Override public void setCharacterStream(final int p, final java.io.Reader v) { }
        @Override public void setNCharacterStream(final int p, final java.io.Reader v) { }
        @Override public void setClob(final int p, final java.io.Reader v) { }
        @Override public void setBlob(final int p, final java.io.InputStream v) { }
        @Override public void setNClob(final int p, final java.io.Reader v) { }
        @Override public java.sql.ResultSet executeQuery(final String sql) {
            return null;
        }
        @Override public int executeUpdate(final String sql) {
            return 0;
        }
        @Override public void close() { }
        @Override public int getMaxFieldSize() {
            return 0;
        }
        @Override public void setMaxFieldSize(final int max) { }
        @Override public int getMaxRows() {
            return 0;
        }
        @Override public void setMaxRows(final int max) { }
        @Override public void setEscapeProcessing(final boolean enable) { }
        @Override public int getQueryTimeout() {
            return 0;
        }
        @Override public void setQueryTimeout(final int seconds) { }
        @Override public void cancel() { }
        @Override public java.sql.SQLWarning getWarnings() {
            return null;
        }
        @Override public void clearWarnings() { }
        @Override public void setCursorName(final String name) { }
        @Override public boolean execute(final String sql) {
            return false;
        }
        @Override public java.sql.ResultSet getResultSet() {
            return null;
        }
        @Override public int getUpdateCount() {
            return 0;
        }
        @Override public boolean getMoreResults() {
            return false;
        }
        @Override public void setFetchDirection(final int dir) { }
        @Override public int getFetchDirection() {
            return 0;
        }
        @Override public void setFetchSize(final int rows) { }
        @Override public int getFetchSize() {
            return 0;
        }
        @Override public int getResultSetConcurrency() {
            return 0;
        }
        @Override public int getResultSetType() {
            return 0;
        }
        @Override public void addBatch(final String sql) { }
        @Override public void clearBatch() { }
        @Override public int[] executeBatch() {
            return new int[0];
        }
        @Override public Connection getConnection() {
            return null;
        }
        @Override public boolean getMoreResults(final int current) {
            return false;
        }
        @Override public java.sql.ResultSet getGeneratedKeys() {
            return null;
        }
        @Override public int executeUpdate(final String sql, final int autoKeys) {
            return 0;
        }
        @Override public int executeUpdate(final String sql, final int[] keys) {
            return 0;
        }
        @Override public int executeUpdate(final String sql, final String[] keys) {
            return 0;
        }
        @Override public boolean execute(final String sql, final int autoKeys) {
            return false;
        }
        @Override public boolean execute(final String sql, final int[] keys) {
            return false;
        }
        @Override public boolean execute(final String sql, final String[] keys) {
            return false;
        }
        @Override public int getResultSetHoldability() {
            return 0;
        }
        @Override public boolean isClosed() {
            return false;
        }
        @Override public void setPoolable(final boolean poolable) { }
        @Override public boolean isPoolable() {
            return false;
        }
        @Override public void closeOnCompletion() { }
        @Override public boolean isCloseOnCompletion() {
            return false;
        }
        @Override public <T> T unwrap(final Class<T> iface) {
            return null;
        }
        @Override public boolean isWrapperFor(final Class<?> iface) {
            return false;
        }
    }
}
