-- V113: Cluster node registry
-- Tracks live Pantera instances for HA/clustering coordination.
-- Moved from DbNodeRegistry.createTable() into Flyway.
-- Note: V103 renamed artipie_nodes to pantera_nodes on existing installs;
-- this migration creates the table fresh if it doesn't exist.

CREATE TABLE IF NOT EXISTS pantera_nodes (
    node_id         VARCHAR(255) PRIMARY KEY,
    hostname        VARCHAR(255) NOT NULL,
    port            INT NOT NULL,
    started_at      TIMESTAMP NOT NULL,
    last_heartbeat  TIMESTAMP NOT NULL,
    status          VARCHAR(32) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_pantera_nodes_status
    ON pantera_nodes(status);
CREATE INDEX IF NOT EXISTS idx_pantera_nodes_heartbeat
    ON pantera_nodes(last_heartbeat);
