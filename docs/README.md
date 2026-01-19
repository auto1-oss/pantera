# Artipie Documentation

Documentation for Artipie - Enterprise Binary Artifact Management.

## Quick Start

| Guide | Description |
|-------|-------------|
| [User Guide](USER_GUIDE.md) | Installation, configuration, and usage |
| [Developer Guide](DEVELOPER_GUIDE.md) | Architecture, contributing, and extending |

## User Documentation

### Getting Started

- [User Guide](USER_GUIDE.md) - Complete user documentation
  - Installation (Docker, Docker Compose, JAR)
  - Configuration (main config, repositories, storage)
  - Repository types (Maven, NPM, Docker, PyPI, etc.)
  - Authentication and Authorization
  - Monitoring and Logging

### Configuration Guides

| Document | Description |
|----------|-------------|
| [API Routing](API_ROUTING.md) | URL patterns and routing configuration |
| [Okta OIDC Integration](OKTA_OIDC_INTEGRATION.md) | Okta authentication with MFA |
| [Disk Cache Cleanup](DISK_CACHE_CLEANUP_CONFIG.md) | S3 disk cache configuration |

### Operations and Performance

| Document | Description |
|----------|-------------|
| [JVM Optimization](ARTIPIE_JVM_OPTIMIZATION.md) | JVM tuning for production |
| [S3 Storage Configuration](s3-optimizations/README.md) | S3 storage setup and tuning |
| [Logging Configuration](LOGGING_CONFIGURATION.md) | Log4j2 and ECS JSON setup |
| [ECS JSON Reference](ECS_JSON_QUICK_REFERENCE.md) | Structured logging format |

### Package Manager Guides

| Document | Description |
|----------|-------------|
| [NPM CLI Compatibility](NPM_CLI_COMPATIBILITY.md) | NPM command reference |

## Security

| Document | Description |
|----------|-------------|
| [Cooldown System](cooldown-fallback/README.md) | Supply chain attack prevention |

## Developer Documentation

### Architecture

- [Developer Guide](DEVELOPER_GUIDE.md) - Complete developer documentation
  - Development environment setup
  - Architecture overview (Slice pattern, Storage abstraction)
  - Adding new features
  - Testing guidelines
  - Code style and standards

### Storage

- [S3 Storage Configuration](s3-optimizations/README.md) - S3 configuration and tuning
  - Multipart uploads
  - Parallel downloads
  - Encryption
  - Disk caching

### API

- [Global Import API](global-import-api.md) - Bulk import API specification

## Configuration Examples

Example configuration files:

| File | Description |
|------|-------------|
| [S3 Storage Config](s3-optimizations/S3_STORAGE_CONFIG_EXAMPLE.yml) | S3 storage with performance settings |
| [S3 High-Scale Config](s3-optimizations/S3_HIGH_SCALE_CONFIG.yml) | High-scale S3 configuration |

## Document Map

```
docs/
├── README.md                      # This file
├── USER_GUIDE.md                  # User documentation
├── DEVELOPER_GUIDE.md             # Developer documentation
│
├── Configuration/
│   ├── API_ROUTING.md
│   ├── DISK_CACHE_CLEANUP_CONFIG.md
│   └── OKTA_OIDC_INTEGRATION.md
│
├── Performance/
│   ├── ARTIPIE_JVM_OPTIMIZATION.md
│   └── S3_PERFORMANCE_TUNING.md
│
├── Logging/
│   ├── LOGGING_CONFIGURATION.md
│   └── ECS_JSON_QUICK_REFERENCE.md
│
├── Package Managers/
│   └── NPM_CLI_COMPATIBILITY.md
│
├── s3-optimizations/
│   ├── README.md                  # S3 storage configuration
│   ├── S3_STORAGE_CONFIG_EXAMPLE.yml
│   └── S3_HIGH_SCALE_CONFIG.yml
│
├── cooldown-fallback/
│   └── README.md                  # Cooldown system documentation
│
└── global-import-api.md           # Import API specification
```

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

## Version

| Component | Version |
|-----------|---------|
| Artipie | 1.20.12 |
| Documentation | January 2026 |
