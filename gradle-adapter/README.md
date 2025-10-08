# Gradle Adapter

Gradle repository adapter for Artipie.

## Features

- **Gradle Repository**: Full support for hosting Gradle artifacts
- **Gradle Proxy**: Proxy remote Gradle/Maven repositories with caching
- **Cooldown Service**: Integrated cooldown mechanism to prevent excessive upstream requests
- **Artifact Events**: Track artifact downloads and uploads
- **Multiple Formats**: Support for JAR, POM, Gradle Module Metadata (.module), and checksums

## Usage

### Gradle Repository

```yaml
repo:
  type: gradle
  storage:
    type: fs
    path: /var/artipie/data
```

### Gradle Proxy Repository

```yaml
repo:
  type: gradle-proxy
  storage:
    type: fs
    path: /var/artipie/data
  remotes:
    - url: https://repo1.maven.org/maven2
    - url: https://plugins.gradle.org/m2
```

### Gradle Group Repository

```yaml
repo:
  type: gradle-group
  members:
    - gradle-proxy
    - gradle-local
```

## Gradle Configuration

### Using Gradle Kotlin DSL (build.gradle.kts)

```kotlin
repositories {
    maven {
        url = uri("http://localhost:8080/gradle-repo")
        credentials {
            username = "alice"
            password = "secret"
        }
    }
}
```

### Using Gradle Groovy DSL (build.gradle)

```groovy
repositories {
    maven {
        url 'http://localhost:8080/gradle-repo'
        credentials {
            username 'alice'
            password 'secret'
        }
    }
}
```

## Publishing to Gradle Repository

### Kotlin DSL

```kotlin
plugins {
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven {
            url = uri("http://localhost:8080/gradle-repo")
            credentials {
                username = "alice"
                password = "secret"
            }
        }
    }
}
```

### Groovy DSL

```groovy
plugins {
    id 'maven-publish'
}

publishing {
    publications {
        maven(MavenPublication) {
            from components.java
        }
    }
    repositories {
        maven {
            url 'http://localhost:8080/gradle-repo'
            credentials {
                username 'alice'
                password 'secret'
            }
        }
    }
}
```

## Cooldown Service

The Gradle adapter integrates with Artipie's cooldown service to:
- Prevent repeated downloads of the same artifact version
- Parse dependencies from POM and Gradle module metadata files
- Track release dates from Last-Modified headers
- Evaluate cooldown policies before proxying requests

## Supported File Types

- `.jar` - Java Archive
- `.aar` - Android Archive
- `.pom` - Maven POM files
- `.module` - Gradle Module Metadata
- `.sha1`, `.sha256`, `.sha512`, `.md5` - Checksums
- `.asc` - GPG signatures

## Architecture

The adapter consists of:
- `Gradle` - Core interface for Gradle repository operations
- `AstoGradle` - ASTO storage implementation
- `GradleSlice` - HTTP slice for local Gradle repository
- `GradleProxySlice` - HTTP slice for proxying remote repositories
- `GradleCooldownInspector` - Cooldown service integration
- `CachedProxySlice` - Caching layer for proxy requests

## Testing

Run tests with:
```bash
mvn test
```

Run integration tests:
```bash
mvn verify -Dtest.integration=true
```
