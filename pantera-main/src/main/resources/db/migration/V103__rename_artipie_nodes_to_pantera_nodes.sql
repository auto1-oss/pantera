-- Rename the artipie_nodes table and its indexes to pantera_nodes.

ALTER TABLE IF EXISTS artipie_nodes RENAME TO pantera_nodes;

ALTER INDEX IF EXISTS idx_nodes_status RENAME TO idx_pantera_nodes_status;
ALTER INDEX IF EXISTS idx_nodes_heartbeat RENAME TO idx_pantera_nodes_heartbeat;
