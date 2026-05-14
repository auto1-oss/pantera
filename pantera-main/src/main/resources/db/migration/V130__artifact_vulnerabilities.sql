-- T-S08: Optional OSV-Scanner CVE integration.
--
-- Captures vulnerability scan results produced by VulnerabilityScanner
-- after every successful cache write on repos with
-- scan_for_vulnerabilities = true. The table is one row per discovered
-- CVE per (repo, key, version) tuple, plus an "empty" sentinel row
-- (cve_id = NULL) recording scans that completed cleanly so the API can
-- distinguish "scan completed; no findings" from "scan never ran".
--
-- Scan failures are stored on the artifact_scan_status table with an
-- attempt counter and a backoff next-retry timestamp; after 5 failures
-- the scan_failed flag is set permanently.
--
-- Reasoning for two tables rather than one:
--   * Findings (artifact_vulnerabilities) are the API surface — small,
--     queried by every GET /api/v1/artifacts/{key}/vulnerabilities.
--   * Status (artifact_scan_status) is the operational surface — read
--     by the scanner worker to decide what to retry; never exposed to
--     clients. Splitting keeps the read path lean.

CREATE TABLE IF NOT EXISTS artifact_vulnerabilities (
    id              BIGSERIAL PRIMARY KEY,
    repo_name       TEXT      NOT NULL,
    artifact_key    TEXT      NOT NULL,
    -- OSV record identifier (e.g. CVE-2021-44228, GHSA-jfh8-c2jp-5v3q).
    -- NULL marks an "empty" scan record: scan completed, nothing found.
    cve_id          TEXT,
    severity        TEXT,
    summary         TEXT,
    -- Free-form JSON capturing the OSV.dev response slice. Schema:
    -- { "fixed_in": "2.16.0", "introduced_in": "2.0", "references": [...] }
    osv_payload     JSONB,
    discovered_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT artifact_vulnerabilities_unique
        UNIQUE (repo_name, artifact_key, cve_id)
);

CREATE INDEX IF NOT EXISTS idx_artifact_vulnerabilities_repo_key
    ON artifact_vulnerabilities (repo_name, artifact_key);
CREATE INDEX IF NOT EXISTS idx_artifact_vulnerabilities_cve
    ON artifact_vulnerabilities (cve_id);
CREATE INDEX IF NOT EXISTS idx_artifact_vulnerabilities_severity
    ON artifact_vulnerabilities (severity);

CREATE TABLE IF NOT EXISTS artifact_scan_status (
    repo_name        TEXT       NOT NULL,
    artifact_key     TEXT       NOT NULL,
    -- last scan attempt timestamp (success or failure).
    last_attempt_at  TIMESTAMPTZ,
    -- ISO-8601 timestamp of the next retry; the worker polls this column.
    next_retry_at    TIMESTAMPTZ,
    -- number of consecutive failures; resets to 0 on success.
    attempt_count    INTEGER    NOT NULL DEFAULT 0,
    -- terminal-failure flag: true after the 5th consecutive failure.
    scan_failed      BOOLEAN    NOT NULL DEFAULT FALSE,
    -- last exception message (truncated to 1 KB) for operator triage.
    last_error       TEXT,
    PRIMARY KEY (repo_name, artifact_key)
);

CREATE INDEX IF NOT EXISTS idx_artifact_scan_status_retry
    ON artifact_scan_status (next_retry_at)
    WHERE scan_failed = FALSE AND next_retry_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_artifact_scan_status_failed
    ON artifact_scan_status (repo_name, artifact_key)
    WHERE scan_failed = TRUE;

COMMENT ON TABLE artifact_vulnerabilities IS
    'T-S08: CVE findings discovered by VulnerabilityScanner via OSV.dev. '
    || 'One row per CVE per (repo, artifact_key). NULL cve_id marks a clean scan.';

COMMENT ON TABLE artifact_scan_status IS
    'T-S08: scan attempt tracking with exponential backoff. Workers poll '
    || 'next_retry_at WHERE scan_failed = FALSE; the 5th failure flips the flag.';
