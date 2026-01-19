# Artipie Documentation

Welcome to the Artipie documentation. This directory contains comprehensive guides for users, administrators, and developers.

---

## Quick Links

| Guide | Description | Audience |
|-------|-------------|----------|
| [User Guide](USER_GUIDE.md) | Complete guide to installing, configuring, and using Artipie | Users, Administrators |
| [Developer Guide](DEVELOPER_GUIDE.md) | Architecture, contributing, and extending Artipie | Developers, Contributors |

---

## Documentation Index

### User Documentation

#### Getting Started
- [User Guide](USER_GUIDE.md) - Complete user documentation
  - Installation (Docker, Docker Compose, JAR)
  - Configuration (main config, repositories, storage)
  - Repository types (Maven, NPM, Docker, PyPI, etc.)
  - Authentication & Authorization
  - Monitoring & Logging

#### Configuration Guides
- [API Routing](API_ROUTING.md) - URL patterns and routing configuration
- [Disk Cache Cleanup](DISK_CACHE_CLEANUP_CONFIG.md) - S3 disk cache configuration
- [Okta OIDC Integration](OKTA_OIDC_INTEGRATION.md) - Okta authentication setup

#### Performance & Operations
- [S3 Performance Tuning](S3_PERFORMANCE_TUNING.md) - S3 storage optimization
- [JVM Optimization](ARTIPIE_JVM_OPTIMIZATION.md) - JVM tuning for production
- [Logging Configuration](LOGGING_CONFIGURATION.md) - Log4j2 and ECS logging setup
- [ECS JSON Quick Reference](ECS_JSON_QUICK_REFERENCE.md) - Structured logging format

#### Package Manager Guides
- [NPM CLI Compatibility](NPM_CLI_COMPATIBILITY.md) - Complete NPM command reference

---

### Developer Documentation

#### Getting Started
- [Developer Guide](DEVELOPER_GUIDE.md) - Complete developer documentation
  - Development environment setup
  - Architecture overview
  - Core concepts (Slice pattern, Storage, etc.)
  - Adding new features
  - Testing guidelines
  - Code style & standards

#### Architecture
- [Performance Issues Analysis](PERFORMANCE_ISSUES_ANALYSIS.md) - Known issues and solutions
- [HTTP Leak Patterns Review](HTTP_LEAK_PATTERNS_REVIEW.md) - HTTP client patterns

#### S3 Storage
- [S3 Optimizations](s3-optimizations/README.md) - S3 memory and performance fixes
  - [Memory Optimizations](s3-optimizations/S3_MEMORY_OPTIMIZATIONS.md)
  - [Fixes Summary](s3-optimizations/S3_FIXES_SUMMARY.md)
  - [Scale Analysis](s3-optimizations/SCALE_ANALYSIS.md)
  - [Retry Fix](s3-optimizations/RETRY_FIX.md)
  - [Build and Deploy](s3-optimizations/BUILD_AND_DEPLOY.md)
- [S3 Runbook](s3-runbook.md) - S3 operations runbook

#### Cooldown System
- [Cooldown System](cooldown-fallback/README.md) - Supply chain attack prevention
  - [Final Implementation](cooldown-fallback/FINAL_IMPLEMENTATION.md)
  - [Edge Case Handling](cooldown-fallback/EDGE_CASE_HANDLING.md)
  - [Package Manager Behavior](cooldown-fallback/PACKAGE_MANAGER_CLIENT_BEHAVIOR.md)

#### NPM Proxy
- [NPM Proxy Analysis](npm-proxy-complete-analysis.md) - NPM proxy architecture
- [NPM Deduplication Analysis](npm-proxy-deduplication-analysis.md) - Request deduplication

#### API
- [Global Import API](global-import-api.md) - Bulk import API

---

### Roadster (Rust) Documentation

Roadster is the next-generation Artipie rewrite in Rust.

| Document | Description |
|----------|-------------|
| [Roadster README](../roadster/docs/README.md) | Getting started with Roadster |
| [Quick Start](../roadster/QUICKSTART.md) | Build commands and project structure |
| [Architecture Guide](../roadster/AGENTS-ROADSTER.md) | Roadster architecture for developers |
| [Coding Standards](../roadster/CODING_STANDARDS.md) | Rust coding guidelines |
| [Contributing](../roadster/CONTRIBUTING.md) | How to contribute to Roadster |
| [Compatibility](../roadster/COMPATIBILITY.md) | Migration from Artipie |
| [Trait Patterns](../roadster/TRAIT_PATTERNS.md) | Rust trait design patterns |

#### Phase Summaries
- [Phase 0 Summary](../roadster/PHASE0_SUMMARY.md) - Core infrastructure
- [Phase 1 Summary](../roadster/PHASE1_SUMMARY.md) - Configuration & telemetry
- [Phase 2 Summary](../roadster/PHASE2-SUMMARY.md) - Storage abstraction
- [Phase 4 Summary](../roadster/PHASE4_SUMMARY.md) - Authentication & authorization

#### Architecture Decision Records
- [ADR-0001: Rust Rewrite](../roadster/docs/adr/0001-rust-rewrite.md)
- [ADR-0002: Slice Pattern](../roadster/docs/adr/0002-slice-pattern.md)
- [ADR-0003: Storage Abstraction](../roadster/docs/adr/0003-storage-abstraction.md)

#### Migration
- [Migration Guide](../roadster/docs/migration/MIGRATION_GUIDE.md)
- [Blue-Green Deployment](../roadster/docs/migration/BLUE_GREEN_DEPLOYMENT.md)

---

## Document Map

```
docs/
├── README.md                           # This file
├── USER_GUIDE.md                       # User documentation
├── DEVELOPER_GUIDE.md                  # Developer documentation
│
├── Configuration
│   ├── API_ROUTING.md
│   ├── DISK_CACHE_CLEANUP_CONFIG.md
│   └── OKTA_OIDC_INTEGRATION.md
│
├── Performance
│   ├── S3_PERFORMANCE_TUNING.md
│   ├── ARTIPIE_JVM_OPTIMIZATION.md
│   ├── PERFORMANCE_ISSUES_ANALYSIS.md
│   └── HTTP_LEAK_PATTERNS_REVIEW.md
│
├── Logging
│   ├── LOGGING_CONFIGURATION.md
│   └── ECS_JSON_QUICK_REFERENCE.md
│
├── Package Managers
│   ├── NPM_CLI_COMPATIBILITY.md
│   ├── npm-proxy-complete-analysis.md
│   └── npm-proxy-deduplication-analysis.md
│
├── s3-optimizations/
│   ├── README.md
│   ├── S3_MEMORY_OPTIMIZATIONS.md
│   ├── S3_FIXES_SUMMARY.md
│   ├── SCALE_ANALYSIS.md
│   ├── RETRY_FIX.md
│   ├── BUILD_AND_DEPLOY.md
│   ├── S3_STORAGE_CONFIG_EXAMPLE.yml
│   └── S3_HIGH_SCALE_CONFIG.yml
│
├── cooldown-fallback/
│   ├── README.md
│   ├── FINAL_IMPLEMENTATION.md
│   ├── EDGE_CASE_HANDLING.md
│   ├── ALL_VERSIONS_BLOCKED_ERROR_HANDLING.md
│   ├── PACKAGE_MANAGER_CLIENT_BEHAVIOR.md
│   └── ... (more docs)
│
├── s3-runbook.md
└── global-import-api.md
```

---

## Contributing to Documentation

When contributing to Artipie documentation:

1. **User-facing docs** go in [USER_GUIDE.md](USER_GUIDE.md)
2. **Developer docs** go in [DEVELOPER_GUIDE.md](DEVELOPER_GUIDE.md)
3. **Feature-specific docs** go in dedicated files
4. **Update this README** when adding new documents

### Style Guidelines

- Use clear, concise language
- Include code examples where helpful
- Keep commands copy-pasteable
- Add tables for reference information
- Use diagrams for architecture

---

## Version Information

| Component | Version |
|-----------|---------|
| Artipie (Java) | 1.20.11 |
| Roadster (Rust) | 0.1.0 |
| Documentation | January 2026 |

---

## Support

- **GitHub Issues**: https://github.com/artipie/artipie/issues
- **Discussions**: https://github.com/artipie/artipie/discussions
- **Telegram**: [@artipie](https://t.me/artipie)
