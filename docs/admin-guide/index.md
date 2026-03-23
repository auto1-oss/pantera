# Pantera Admin Guide

> **Guide:** Admin Guide | **Section:** Overview

This guide covers the installation, configuration, operation, and maintenance of the Pantera Artifact Registry for system administrators and platform engineers. It assumes familiarity with Docker, Linux system administration, and enterprise infrastructure concepts.

**Pantera Version:** 2.0.0
**License:** GPL-3.0
**JDK:** 21+ (Eclipse Temurin)

---

## Table of Contents

### Getting Started

1. [Installation](installation.md) -- Docker standalone, Docker Compose production stack, and JAR file deployment.

### Core Configuration

2. [Configuration](configuration.md) -- Main pantera.yml structure, environment variable substitution, and section overview.
3. [Authentication](authentication.md) -- Auth providers: env, pantera, Keycloak, Okta (MFA), JWT-as-Password.
4. [Authorization](authorization.md) -- RBAC permission model, roles, user-role assignment, and policy management.
5. [Storage Backends](storage-backends.md) -- Filesystem, S3, S3 Express, MinIO, disk cache, and storage aliases.
6. [Environment Variables](environment-variables.md) -- Authoritative reference for all PANTERA_* environment variables.

### Operations

7. [Cooldown](cooldown.md) -- Supply chain security cooldown system: configuration, API management, and monitoring.
8. [Monitoring](monitoring.md) -- Prometheus metrics, Grafana dashboards, health checks, and alerting.
9. [Logging](logging.md) -- ECS JSON structured logging, external configuration, hot reload, and log filtering.
10. [Performance Tuning](performance-tuning.md) -- JVM settings, thread pools, S3 tuning, connection pooling, and file descriptors.

### Architecture

11. [High Availability](high-availability.md) -- Multi-node deployment with PostgreSQL, Valkey, S3, and load balancing.

### Maintenance

12. [Backup and Recovery](backup-and-recovery.md) -- Database, configuration, and artifact backup strategies with disaster recovery procedures.
13. [Upgrade Procedures](upgrade-procedures.md) -- Pre-upgrade checklist, upgrade steps, database migrations, and rollback.
14. [Troubleshooting](troubleshooting.md) -- Common issues with diagnostics and resolution steps.

---

## Quick Reference

| Resource | Location |
|----------|----------|
| Main configuration | `/etc/pantera/pantera.yml` |
| Repository definitions | `/var/pantera/repo/*.yaml` |
| RBAC policy files | `/var/pantera/security/` |
| Artifact data | `/var/pantera/data/` |
| Cache directory | `/var/pantera/cache/` |
| Logs and dumps | `/var/pantera/logs/` |
| Repository port | `8080` |
| REST API port | `8086` |
| Metrics port | `8087` |
| Management UI port | `8090` |

## Companion Documentation

| Document | Description |
|----------|-------------|
| [Configuration Reference](../configuration-reference.md) | Exhaustive reference for every configuration key |
| [REST API Reference](../rest-api-reference.md) | Complete API endpoint documentation |
| [User Guide](../user-guide.md) | End-user guide including repository type setup and client configuration |
| [Developer Guide](../developer-guide.md) | Architecture, module map, and contributor guide |
