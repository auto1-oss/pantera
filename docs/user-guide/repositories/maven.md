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

## Caching, Resumable Downloads, and Signed Artifacts

**Conditional re-resolves.** A warm artifact (local repositories, and proxy artifacts already cached from a prior fetch) is served with an `ETag` and `Last-Modified`. A client re-checking an unchanged artifact (a conditional GET with `If-None-Match`) gets back a `304 Not Modified` with no body instead of re-downloading it — most HTTP clients, including Maven's transport layer, do this automatically once they've cached a resource with validators.

**Resumable / parallel downloads.** Artifact GETs (never `maven-metadata.xml` or checksum sidecars) advertise `Accept-Ranges: bytes` and honour `Range: bytes=start-end` requests with `206 Partial Content`. Download managers and multi-connection clients can resume an interrupted transfer or fetch a large artifact over several concurrent ranges.

**Immutable releases.** When an administrator has enabled `releaseImmutable` on a local repository, redeploying an already-published, non-SNAPSHOT (release) coordinate is rejected with `409 Conflict` — the existing artifact is left untouched. SNAPSHOT redeploys are always allowed. If you need to publish a corrected build, bump the version instead of overwriting the release.

**Signed artifacts (`.asc`).** When an administrator has enabled `verifyPgp` on a repository, a `.asc`/`.sig` signature is verified against the admin-managed keyring before the artifact is trusted. On a **hosted** (`local`) repository this is a hard guarantee, enforced regardless of the order your build tool uploads files in:

- **No primary artifact is ever downloadable until a matching, verified signature has been checked in against it.** A `PUT` of the primary itself always returns `201 Created` — your build does not fail — but the artifact is *quarantined*, not yet resolvable by any client (`GET`/`HEAD` 404s, and it is excluded from `maven-metadata.xml`'s `<versions>`), until its `.asc`/`.sig` also arrives and verifies.
- Order does not matter: whichever of the primary or its signature is uploaded first, Pantera holds it and verifies the moment the other half arrives. `mvn deploy` (primary, then signature) and `gpg-plugin`/`maven-release-plugin` variants that attach the signature earlier in the reactor are both handled correctly.
- Sign your artifact as usual with the `maven-gpg-plugin` (or `gpg --detach-sign -a`) so `mvn deploy`/`gradle publish` uploads the `.asc` alongside the primary.
- Ask your administrator to register your public key via the admin PGP keyring (UI: **Administration → Maven PGP Keyring**; API: `POST /api/v1/admin/pgp-keys`) *before* you deploy — an unrecognised signer, a tampered artifact, or a missing signature is rejected.
- If verification fails (wrong key, tampered bytes, unregistered signer) or the signature never shows up at all, the artifact simply stays quarantined forever — it was never published, so there is nothing to roll back. Whichever upload completed the pairing and failed verification gets `403 Forbidden`. Re-deploy with a correct signature to publish successfully.
- On a **proxy** fetch, `verifyPgp` requires Maven-Central-tier semantics: every proxied artifact must carry a signature that verifies against a trusted key, or the proxy fetch fails. (The proxy path has no upload-order concept — Pantera fetches the primary and its signature together from upstream — so it is not affected by the quarantine mechanism above.)

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
| `409 Conflict` on deploy | Repository has `releaseImmutable` enabled and this release version already exists | Bump the version; release coordinates are immutable once published |
| `403 Forbidden` on deploy of a primary or a `.asc`/`.sig` file | Repository has `verifyPgp` enabled and the signature failed verification (wrong key, tampered artifact, or signer not registered) — whichever of the two uploads completed the pairing gets the 403 | Confirm you signed with the key registered by your administrator, and that the artifact wasn't modified after signing; re-deploy both files |
| `404 Not Found` resolving an artifact right after a successful (`201`) hosted deploy | Repository has `verifyPgp` enabled and the artifact is quarantined — its `.asc`/`.sig` has not (yet) verified | Deploy the matching signature if you haven't already; if you have, check `403 Forbidden` responses from that upload for a keyring/signature problem |
| `403 Forbidden` / rejected fetch from a proxy | Repository has `verifyPgp` enabled and the upstream artifact has no verifiable signature | Ask your administrator whether `verifyPgp` should be enabled for this upstream (Maven Central does not sign every artifact type) |
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
