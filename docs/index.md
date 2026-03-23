# Pantera Artifact Registry Documentation

**Version 2.0.0** | GPL-3.0 | JDK 21+ | Maven 3.4+

Pantera is a universal binary artifact registry supporting 15+ package formats including Maven, Docker, npm, PyPI, Composer, Helm, Go, Gem, NuGet, Debian, RPM, Conda, Conan, and Hex. It can operate as a local hosted registry, a caching proxy to upstream sources, or a group that merges multiple repositories into a single endpoint.

---

## Choose Your Guide

### For Platform Administrators

[Admin Guide](admin-guide/index.md) -- Installation, configuration, security, monitoring, scaling, and operations.

Covers deployment options (Docker, bare-metal), PostgreSQL and Valkey setup, authentication (basic, token, Okta OIDC), S3 storage configuration, high-availability clustering, logging, and troubleshooting.

### For Developers and Users

[User Guide](user-guide/index.md) -- Getting started, client configuration, pushing and pulling artifacts across 15 formats.

Covers repository types (local, proxy, group), per-format client setup (Maven `settings.xml`, Docker CLI, npm `.npmrc`, pip, Composer, Helm, etc.), and common workflows.

### For Contributors

[Developer Guide](developer-guide/index.md) -- Architecture, codebase, extending Pantera, testing, and contributing.

Covers the Vert.x HTTP layer, Slice pipeline, adapter modules, database schema, cache architecture, thread model, build system, and how to add new features.

---

## Reference Documentation

- [Configuration Reference](reference/configuration-reference.md) -- All YAML config keys, storage options, and repository settings.
- [REST API Reference](reference/rest-api-reference.md) -- All API endpoints with request/response formats and curl examples.
- [Environment Variables](admin-guide/environment-variables.md) -- Runtime configuration via environment variables.
- [URL Routing Patterns](reference/url-routing.md) -- URL pattern support per repository type.
- [npm CLI Compatibility](reference/npm-cli-compatibility.md) -- npm command support matrix across local, proxy, and group modes.

## Additional Resources

- [Changelog](reference/changelog.md)
- [Release Notes](reference/release-notes/)
