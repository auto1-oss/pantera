# Maven

> **Guide:** User Guide | **Section:** Repositories / Maven

This page covers how to configure Apache Maven (and Gradle with Maven repositories) to pull dependencies from and deploy artifacts to Pantera.

---

## Prerequisites

- Apache Maven 3.x or Gradle with Maven repository support
- A Pantera account with a JWT token (see [Getting Started](../getting-started.md))
- The Pantera hostname and port (default: `pantera-host:8080`)

---

## Configure Your Client

### settings.xml

Add the following to your Maven `settings.xml` (typically `~/.m2/settings.xml`):

```xml
<settings>
  <servers>
    <server>
      <id>pantera</id>
      <username>your-username</username>
      <password>your-jwt-token-here</password>
    </server>
  </servers>
  <mirrors>
    <mirror>
      <id>pantera</id>
      <mirrorOf>*</mirrorOf>
      <url>http://pantera-host:8080/maven-group</url>
    </mirror>
  </mirrors>
</settings>
```

Replace:
- `your-username` with your Pantera username
- `your-jwt-token-here` with the JWT token obtained from the API
- `maven-group` with the name of your group repository (ask your administrator)

The `<mirrorOf>*</mirrorOf>` setting redirects all Maven repository requests through Pantera, including Maven Central.

### Gradle (settings.gradle.kts)

```kotlin
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("http://pantera-host:8080/maven-group")
            credentials {
                username = "your-username"
                password = "your-jwt-token-here"
            }
            isAllowInsecureProtocol = true // only if not using HTTPS
        }
    }
}
```

---

## Pull Dependencies

Once your `settings.xml` is configured with the mirror, all dependency resolution goes through Pantera automatically:

```bash
mvn clean install
```

Maven will resolve dependencies from the group repository, which checks your local repository first and then falls through to proxied upstream registries.

To verify connectivity:

```bash
mvn dependency:resolve -U
```

---

## Deploy Artifacts

### Step 1: Configure distributionManagement in pom.xml

Add the deployment target to your project's `pom.xml`:

```xml
<distributionManagement>
  <repository>
    <id>pantera</id>
    <url>http://pantera-host:8080/maven-local</url>
  </repository>
  <snapshotRepository>
    <id>pantera</id>
    <url>http://pantera-host:8080/maven-local</url>
  </snapshotRepository>
</distributionManagement>
```

The `<id>pantera</id>` must match the `<server><id>` in your `settings.xml`.

### Step 2: Deploy

```bash
mvn deploy
```

For a single artifact deployment without a full build:

```bash
mvn deploy:deploy-file \
  -DgroupId=com.example \
  -DartifactId=my-lib \
  -Dversion=1.0.0 \
  -Dpackaging=jar \
  -Dfile=my-lib-1.0.0.jar \
  -DrepositoryId=pantera \
  -Durl=http://pantera-host:8080/maven-local
```

---

## Using Group Repositories

Group repositories are the recommended way to configure Maven. A typical group combines:

1. A **local** repository for your internal artifacts
2. A **proxy** repository that caches Maven Central

Your mirror URL points to the group, and Pantera resolves from the right source automatically. You do not need to list multiple repositories in your `settings.xml`.

---

## Common Issues

| Symptom | Cause | Fix |
|---------|-------|-----|
| `401 Unauthorized` on dependency resolution | Expired or invalid JWT token | Generate a new token via `POST /api/v1/auth/token` |
| `401 Unauthorized` on deploy | `<server><id>` does not match `<repository><id>` | Ensure both use the same `id` value (e.g., `pantera`) |
| `Could not transfer artifact` | Network connectivity or proxy timeout | Check connectivity to Pantera; ask admin to check upstream proxy settings |
| Dependencies resolve but deploys fail | User lacks `write` permission on the target repository | Contact your administrator to grant write access |
| `Return code is: 405` on deploy | Deploying to a proxy or group repository | Deploy only to a **local** repository |
| Checksum verification failure | Corrupted cache | Ask admin to delete the cached artifact and retry |
| SNAPSHOT not updating | Maven caches SNAPSHOT metadata locally | Run with `-U` flag: `mvn install -U` |

---

<details>
<summary>Server-Side Repository Configuration (Admin Reference)</summary>

**Local repository:**

```yaml
# maven-local.yaml
repo:
  type: maven
  storage:
    type: fs
    path: /var/pantera/data
```

**Proxy repository:**

```yaml
# maven-proxy.yaml
repo:
  type: maven-proxy
  storage:
    type: fs
    path: /var/pantera/data
  remotes:
    - url: https://repo1.maven.org/maven2
```

**Group repository:**

```yaml
# maven-group.yaml
repo:
  type: maven-group
  members:
    - maven-local
    - maven-proxy
```

</details>

---

## Related Pages

- [Getting Started](../getting-started.md) -- Obtaining JWT tokens
- [Troubleshooting](../troubleshooting.md) -- Common error resolution
- [REST API Reference](../../rest-api-reference.md) -- Repository management endpoints
