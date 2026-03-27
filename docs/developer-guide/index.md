# Developer Guide

> **Audience:** Engineers extending or contributing to Pantera

This guide covers the internal architecture of Pantera, from the Vert.x HTTP layer and Slice request pipeline down to the database schema, cache hierarchy, and cluster coordination. It is intended for developers who need to understand how the system works in order to add features, fix bugs, or contribute upstream.

---

## Contents

The full developer guide is maintained as a single comprehensive document. Each section is linked below.

1. [Introduction](../developer-guide.md#1-introduction) -- Tech stack, supported repository types.
2. [Architecture Overview](../developer-guide.md#2-architecture-overview) -- Layered architecture diagram, request flow, startup sequence.
3. [Module Map](../developer-guide.md#3-module-map) -- All Maven modules and their responsibilities.
4. [Core Concepts](../developer-guide.md#4-core-concepts) -- Slice interface, Storage abstraction, Content/Headers, authentication and authorization.
5. [Database Layer](../developer-guide.md#5-database-layer) -- PostgreSQL schema, HikariCP pools, Flyway migrations, artifact indexing.
6. [Cache Architecture](../developer-guide.md#6-cache-architecture) -- BaseCachedProxySlice pipeline, negative cache (L1 Caffeine + L2 Valkey), request deduplication, disk cache.
7. [Cluster Architecture](../developer-guide.md#7-cluster-architecture) -- Valkey pub/sub invalidation, node registry, Quartz JDBC scheduling.
8. [Thread Model](../developer-guide.md#8-thread-model) -- Vert.x event loop, virtual threads, executor pools, blocking operations.
9. [Shutdown Sequence](../developer-guide.md#9-shutdown-sequence) -- Graceful shutdown, in-flight request draining.
10. [Health Check Architecture](../developer-guide.md#10-health-check-architecture) -- Health endpoints, component checks, readiness vs. liveness.
11. [Development Setup](../developer-guide.md#11-development-setup) -- Prerequisites, IDE configuration, local PostgreSQL and Valkey.
12. [Build System](../developer-guide.md#12-build-system) -- Maven profiles, Docker image build, dependency management.
13. [Adding Features](../developer-guide.md#13-adding-features) -- Adding repository types, new API endpoints, database migrations.
14. [Testing](../developer-guide.md#14-testing) -- Unit tests, integration tests with TestContainers, performance benchmarks.
15. [Debugging](../developer-guide.md#15-debugging) -- Logging, metrics, remote debugging, common issues.

---

## Contributing

- [Contributing Guidelines](../../CONTRIBUTING.md) -- Setup, build, test, commit style, and pull request process.
- [Code Standards](../../CODE_STANDARDS.md) -- Coding conventions, async patterns, testing patterns, API design rules.
