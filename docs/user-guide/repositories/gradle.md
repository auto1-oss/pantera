# Gradle

> **Guide:** User Guide | **Section:** Repositories / Gradle

This page covers how to configure Gradle to resolve dependencies from and publish artifacts to Pantera. Gradle uses the Maven repository format on the wire, so Pantera treats `gradle`, `gradle-proxy`, and `gradle-group` as an alias family over the same Maven resolution paths.

---

## Prerequisites

- Gradle 7.x or 8.x (Kotlin DSL or Groovy DSL)
- A Pantera account with a JWT token (see [Getting Started](../getting-started.md))
- The Pantera hostname and port (default: `pantera-host:8080`)

---

## When to Use

Use the `gradle` family of repo types when you want a logical separation between Gradle-centric repositories and plain Maven repositories in the Pantera UI and API. Functionally, the wire protocol is identical:

- A `gradle` local accepts the same PUT/GET requests as a `maven` local.
- A `gradle-proxy` caches an upstream Maven-format registry (e.g., Maven Central, Gradle Plugin Portal) the same way a `maven-proxy` does.
- A `gradle-group` fans out across `gradle` and `gradle-proxy` members with the same resolution-order semantics as `maven-group`.

This aliasing is built into the backend: groups and proxies for both families share the Maven adapter, cooldown bundle, and metadata regeneration logic. If you already have a `maven-group` you are happy with, you do not need a separate `gradle-group` — point your Gradle build at the `maven-group` URL.

Reach for a dedicated `gradle-*` repo when you want to:

- Segregate plugin portal mirrors (`gradle-proxy` pointing at `https://plugins.gradle.org/m2/`) from library mirrors (`maven-proxy` pointing at Maven Central).
- Keep internal Gradle convention plugins in a distinct `gradle` local, separate from application JARs published to a `maven` local.

---

## Configure Your Client

### settings.gradle.kts (Kotlin DSL)

```kotlin
dependencyResolutionManagement {
    repositories {
        maven {
            name = "pantera"
            url = uri("http://pantera-host:8080/gradle-group")
            credentials {
                username = "your-username"
                password = "your-jwt-token-here"
            }
            isAllowInsecureProtocol = true // only if not using HTTPS
        }
    }
}
```

### build.gradle (Groovy DSL)

```groovy
repositories {
    maven {
        name = 'pantera'
        url = 'http://pantera-host:8080/gradle-group'
        credentials {
            username = 'your-username'
            password = 'your-jwt-token-here'
        }
        allowInsecureProtocol = true // only if not using HTTPS
    }
}
```

### Publishing

Configure the `maven-publish` plugin to push to a `gradle` or `maven` local:

```kotlin
plugins {
    `maven-publish`
}

publishing {
    repositories {
        maven {
            name = "pantera"
            url = uri("http://pantera-host:8080/gradle-local")
            credentials {
                username = "your-username"
                password = "your-jwt-token-here"
            }
        }
    }
    publications {
        create<MavenPublication>("library") {
            from(components["java"])
        }
    }
}
```

Publish with:

```bash
./gradlew publish
```

---

## Minimal YAML Configuration

### Gradle Local (`gradle`)

Stores artifacts published by your Gradle builds. Behaves identically to a `maven` local on the wire.

```yaml
# gradle-local.yaml
repo:
  type: gradle
  storage:
    type: fs
    path: /var/pantera/data
```

### Gradle Proxy (`gradle-proxy`)

Caches an upstream Maven-format repository (e.g., Maven Central, Gradle Plugin Portal) on first request.

```yaml
# gradle-proxy.yaml
repo:
  type: gradle-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://plugins.gradle.org/m2/
```

### Gradle Group (`gradle-group`)

Virtual repository that fans out across members in resolution order. The first member that serves the artifact wins.

```yaml
# gradle-group.yaml
repo:
  type: gradle-group
  members:
    - gradle-local
    - gradle-proxy
```

See the [Management UI guide](../ui-guide.md#adding-members-to-a-group-repository) for how to add, reorder, and create members from the web interface.

---

## Known Limitations

- **URL routing aliasing:** Gradle shares the Maven URL space in the REST API — when addressing repositories by format in `/api/<format>/<repo>` paths, Gradle repos are reachable under `maven` routing. This is intentional (`gradle` appears in the `LIMITED_SUPPORT` set alongside `maven` and `rpm`), but means you cannot disambiguate a Gradle repo from a Maven repo via format alone in those endpoints. Use the repository name directly when in doubt.
- **Cooldown adapter reuse:** `gradle` and `gradle-proxy` use the same cooldown response factory and Maven bundle as `maven` and `maven-proxy`. Cooldown rules you configure for one cover the other semantically (same metadata shape, same checksum sidecars).
- **Metadata regeneration:** Imports into `gradle` repos run through the Maven metadata regenerator (the `case "maven", "gradle"` branch in `MetadataRegenerator`) — `maven-metadata.xml` is produced as you would expect from a Maven repo.
- **No Gradle-specific extensions:** Pantera does not currently expose Gradle-specific features such as Gradle Module Metadata variant matching beyond what standard Maven clients consume. If your build depends on `.module` files, verify they round-trip through the proxy before relying on variant resolution.

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 Unauthorized` during resolve | Missing or expired JWT token | Regenerate the token and update your credentials |
| `Could not GET '...gradle-group/...'` with `403` | User lacks `read` permission on the group or one of its members | Contact your administrator to grant access |
| `Received status code 405` on publish | Publishing to a proxy or group repository | Publish only to a **local** repository (`gradle` or `maven`) |
| Gradle Plugin Portal plugins not resolving | Proxy points at Maven Central, not the plugin portal | Create a second `gradle-proxy` pointing at `https://plugins.gradle.org/m2/` and add it to the group |
| Stale `maven-metadata.xml` after import | Metadata regeneration has not run | Ask admin to trigger regeneration or re-run the import with metadata options |

---

## Related Pages

- [Maven](maven.md) -- Full Maven guide; everything there applies to Gradle over the wire
- [Getting Started](../getting-started.md) -- Obtaining JWT tokens
- [Management UI](../ui-guide.md) -- Creating repos and managing group members visually
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
