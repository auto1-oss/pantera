/*
 * The MIT License (MIT) Copyright (c) 2020-2023 artipie.com
 * https://github.com/artipie/artipie/blob/master/LICENSE.txt
 */
package com.auto1.pantera.scheduling;

import com.auto1.pantera.ArtipieException;
import com.auto1.pantera.http.log.EcsLogger;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Creates the Quartz JDBC job store schema (QRTZ_* tables) in PostgreSQL.
 * <p>
 * Uses {@code CREATE TABLE IF NOT EXISTS} so it is safe to call on every
 * startup. The DDL matches the official Quartz 2.3.x {@code tables_postgres.sql}
 * shipped inside the {@code quartz-2.3.2.jar}.
 *
 * @since 1.20.13
 */
public final class QuartzSchema {

    /**
     * Data source to create the schema in.
     */
    private final DataSource dataSource;

    /**
     * Ctor.
     * @param dataSource Data source for the target PostgreSQL database
     */
    public QuartzSchema(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Create all QRTZ_* tables and indexes if they do not already exist.
     * @throws ArtipieException If DDL execution fails
     */
    public void create() {
        try (Connection conn = this.dataSource.getConnection();
            Statement stmt = conn.createStatement()) {
            QuartzSchema.createTables(stmt);
            QuartzSchema.createIndexes(stmt);
            EcsLogger.info("com.auto1.pantera.scheduling")
                .message("Quartz JDBC schema created or verified")
                .eventCategory("scheduling")
                .eventAction("schema_create")
                .eventOutcome("success")
                .log();
        } catch (final SQLException error) {
            throw new ArtipieException(
                "Failed to create Quartz JDBC schema", error
            );
        }
    }

    /**
     * Execute all CREATE TABLE IF NOT EXISTS statements.
     * Order matters because of foreign key references.
     * @param stmt JDBC statement
     * @throws SQLException On SQL error
     */
    @SuppressWarnings("PMD.ExcessiveMethodLength")
    private static void createTables(final Statement stmt) throws SQLException {
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_JOB_DETAILS (",
                "  SCHED_NAME        VARCHAR(120) NOT NULL,",
                "  JOB_NAME          VARCHAR(200) NOT NULL,",
                "  JOB_GROUP         VARCHAR(200) NOT NULL,",
                "  DESCRIPTION       VARCHAR(250) NULL,",
                "  JOB_CLASS_NAME    VARCHAR(250) NOT NULL,",
                "  IS_DURABLE        BOOL         NOT NULL,",
                "  IS_NONCONCURRENT  BOOL         NOT NULL,",
                "  IS_UPDATE_DATA    BOOL         NOT NULL,",
                "  REQUESTS_RECOVERY BOOL         NOT NULL,",
                "  JOB_DATA          BYTEA        NULL,",
                "  PRIMARY KEY (SCHED_NAME, JOB_NAME, JOB_GROUP)",
                ")"
            )
        );
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_TRIGGERS (",
                "  SCHED_NAME     VARCHAR(120) NOT NULL,",
                "  TRIGGER_NAME   VARCHAR(200) NOT NULL,",
                "  TRIGGER_GROUP  VARCHAR(200) NOT NULL,",
                "  JOB_NAME       VARCHAR(200) NOT NULL,",
                "  JOB_GROUP      VARCHAR(200) NOT NULL,",
                "  DESCRIPTION    VARCHAR(250) NULL,",
                "  NEXT_FIRE_TIME BIGINT       NULL,",
                "  PREV_FIRE_TIME BIGINT       NULL,",
                "  PRIORITY       INTEGER      NULL,",
                "  TRIGGER_STATE  VARCHAR(16)  NOT NULL,",
                "  TRIGGER_TYPE   VARCHAR(8)   NOT NULL,",
                "  START_TIME     BIGINT       NOT NULL,",
                "  END_TIME       BIGINT       NULL,",
                "  CALENDAR_NAME  VARCHAR(200) NULL,",
                "  MISFIRE_INSTR  SMALLINT     NULL,",
                "  JOB_DATA       BYTEA        NULL,",
                "  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),",
                "  FOREIGN KEY (SCHED_NAME, JOB_NAME, JOB_GROUP)",
                "    REFERENCES QRTZ_JOB_DETAILS (SCHED_NAME, JOB_NAME, JOB_GROUP)",
                ")"
            )
        );
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_SIMPLE_TRIGGERS (",
                "  SCHED_NAME      VARCHAR(120) NOT NULL,",
                "  TRIGGER_NAME    VARCHAR(200) NOT NULL,",
                "  TRIGGER_GROUP   VARCHAR(200) NOT NULL,",
                "  REPEAT_COUNT    BIGINT       NOT NULL,",
                "  REPEAT_INTERVAL BIGINT       NOT NULL,",
                "  TIMES_TRIGGERED BIGINT       NOT NULL,",
                "  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),",
                "  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)",
                "    REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)",
                ")"
            )
        );
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_CRON_TRIGGERS (",
                "  SCHED_NAME      VARCHAR(120) NOT NULL,",
                "  TRIGGER_NAME    VARCHAR(200) NOT NULL,",
                "  TRIGGER_GROUP   VARCHAR(200) NOT NULL,",
                "  CRON_EXPRESSION VARCHAR(120) NOT NULL,",
                "  TIME_ZONE_ID    VARCHAR(80),",
                "  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),",
                "  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)",
                "    REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)",
                ")"
            )
        );
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_SIMPROP_TRIGGERS (",
                "  SCHED_NAME    VARCHAR(120)   NOT NULL,",
                "  TRIGGER_NAME  VARCHAR(200)   NOT NULL,",
                "  TRIGGER_GROUP VARCHAR(200)   NOT NULL,",
                "  STR_PROP_1    VARCHAR(512)   NULL,",
                "  STR_PROP_2    VARCHAR(512)   NULL,",
                "  STR_PROP_3    VARCHAR(512)   NULL,",
                "  INT_PROP_1    INT            NULL,",
                "  INT_PROP_2    INT            NULL,",
                "  LONG_PROP_1   BIGINT         NULL,",
                "  LONG_PROP_2   BIGINT         NULL,",
                "  DEC_PROP_1    NUMERIC(13, 4) NULL,",
                "  DEC_PROP_2    NUMERIC(13, 4) NULL,",
                "  BOOL_PROP_1   BOOL           NULL,",
                "  BOOL_PROP_2   BOOL           NULL,",
                "  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),",
                "  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)",
                "    REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)",
                ")"
            )
        );
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_BLOB_TRIGGERS (",
                "  SCHED_NAME    VARCHAR(120) NOT NULL,",
                "  TRIGGER_NAME  VARCHAR(200) NOT NULL,",
                "  TRIGGER_GROUP VARCHAR(200) NOT NULL,",
                "  BLOB_DATA     BYTEA        NULL,",
                "  PRIMARY KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP),",
                "  FOREIGN KEY (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)",
                "    REFERENCES QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)",
                ")"
            )
        );
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_CALENDARS (",
                "  SCHED_NAME    VARCHAR(120) NOT NULL,",
                "  CALENDAR_NAME VARCHAR(200) NOT NULL,",
                "  CALENDAR      BYTEA        NOT NULL,",
                "  PRIMARY KEY (SCHED_NAME, CALENDAR_NAME)",
                ")"
            )
        );
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_PAUSED_TRIGGER_GRPS (",
                "  SCHED_NAME    VARCHAR(120) NOT NULL,",
                "  TRIGGER_GROUP VARCHAR(200) NOT NULL,",
                "  PRIMARY KEY (SCHED_NAME, TRIGGER_GROUP)",
                ")"
            )
        );
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_FIRED_TRIGGERS (",
                "  SCHED_NAME        VARCHAR(120) NOT NULL,",
                "  ENTRY_ID          VARCHAR(95)  NOT NULL,",
                "  TRIGGER_NAME      VARCHAR(200) NOT NULL,",
                "  TRIGGER_GROUP     VARCHAR(200) NOT NULL,",
                "  INSTANCE_NAME     VARCHAR(200) NOT NULL,",
                "  FIRED_TIME        BIGINT       NOT NULL,",
                "  SCHED_TIME        BIGINT       NOT NULL,",
                "  PRIORITY          INTEGER      NOT NULL,",
                "  STATE             VARCHAR(16)  NOT NULL,",
                "  JOB_NAME          VARCHAR(200) NULL,",
                "  JOB_GROUP         VARCHAR(200) NULL,",
                "  IS_NONCONCURRENT  BOOL         NULL,",
                "  REQUESTS_RECOVERY BOOL         NULL,",
                "  PRIMARY KEY (SCHED_NAME, ENTRY_ID)",
                ")"
            )
        );
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_SCHEDULER_STATE (",
                "  SCHED_NAME        VARCHAR(120) NOT NULL,",
                "  INSTANCE_NAME     VARCHAR(200) NOT NULL,",
                "  LAST_CHECKIN_TIME BIGINT       NOT NULL,",
                "  CHECKIN_INTERVAL  BIGINT       NOT NULL,",
                "  PRIMARY KEY (SCHED_NAME, INSTANCE_NAME)",
                ")"
            )
        );
        stmt.executeUpdate(
            String.join(
                "\n",
                "CREATE TABLE IF NOT EXISTS QRTZ_LOCKS (",
                "  SCHED_NAME VARCHAR(120) NOT NULL,",
                "  LOCK_NAME  VARCHAR(40)  NOT NULL,",
                "  PRIMARY KEY (SCHED_NAME, LOCK_NAME)",
                ")"
            )
        );
    }

    /**
     * Create performance indexes. Uses CREATE INDEX IF NOT EXISTS so
     * the call is idempotent.
     * @param stmt JDBC statement
     * @throws SQLException On SQL error
     */
    @SuppressWarnings("PMD.ExcessiveMethodLength")
    private static void createIndexes(final Statement stmt) throws SQLException {
        // Indexes on QRTZ_JOB_DETAILS
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_J_REQ_RECOVERY ON QRTZ_JOB_DETAILS (SCHED_NAME, REQUESTS_RECOVERY)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_J_GRP ON QRTZ_JOB_DETAILS (SCHED_NAME, JOB_GROUP)"
        );
        // Indexes on QRTZ_TRIGGERS
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_J ON QRTZ_TRIGGERS (SCHED_NAME, JOB_NAME, JOB_GROUP)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_JG ON QRTZ_TRIGGERS (SCHED_NAME, JOB_GROUP)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_C ON QRTZ_TRIGGERS (SCHED_NAME, CALENDAR_NAME)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_G ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_GROUP)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_STATE ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_STATE)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_N_STATE ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP, TRIGGER_STATE)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_N_G_STATE ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_GROUP, TRIGGER_STATE)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_NEXT_FIRE_TIME ON QRTZ_TRIGGERS (SCHED_NAME, NEXT_FIRE_TIME)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_NFT_ST ON QRTZ_TRIGGERS (SCHED_NAME, TRIGGER_STATE, NEXT_FIRE_TIME)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_NFT_MISFIRE ON QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_NFT_ST_MISFIRE ON QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME, TRIGGER_STATE)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_T_NFT_ST_MISFIRE_GRP ON QRTZ_TRIGGERS (SCHED_NAME, MISFIRE_INSTR, NEXT_FIRE_TIME, TRIGGER_GROUP, TRIGGER_STATE)"
        );
        // Indexes on QRTZ_FIRED_TRIGGERS
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_TRIG_INST_NAME ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, INSTANCE_NAME)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_INST_JOB_REQ_RCVRY ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, INSTANCE_NAME, REQUESTS_RECOVERY)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_J_G ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, JOB_NAME, JOB_GROUP)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_JG ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, JOB_GROUP)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_T_G ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, TRIGGER_NAME, TRIGGER_GROUP)"
        );
        stmt.executeUpdate(
            "CREATE INDEX IF NOT EXISTS IDX_QRTZ_FT_TG ON QRTZ_FIRED_TRIGGERS (SCHED_NAME, TRIGGER_GROUP)"
        );
    }
}
