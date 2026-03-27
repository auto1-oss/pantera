# Migrating from JFrog Artifactory to Pantera

> **Guide:** User Guide | **Section:** JFrog Migration

This page provides step-by-step instructions for migrating your build pipelines from JFrog Artifactory to Pantera. Pantera implements standard repository protocols, so every format uses the native build tool's publishing mechanism -- no proprietary plugins required.

---

## Overview

JFrog Artifactory requires proprietary client-side plugins and CLIs (the Gradle `com.jfrog.artifactory` plugin, the Maven `artifactory-maven-plugin`, the JFrog CLI, etc.) for publishing. Pantera replaces all of these with standard protocol support:

| JFrog Mechanism | Pantera Replacement |
|----------------|---------------------|
| `com.jfrog.artifactory` Gradle plugin | Built-in `maven-publish` Gradle plugin |
| `artifactory-maven-plugin` for Maven | Standard `mvn deploy` with `distributionManagement` |
| JFrog CLI (`jfrog rt upload`) | Native tool commands (`npm publish`, `twine upload`, `helm push`, etc.) |
| JFrog npm auth (`jfrog rt npmc`) | Standard `.npmrc` configuration |
| JFrog Docker integration | Standard `docker login` / `docker push` |

---

## Prerequisites

Before starting, ensure you have:

1. A running Pantera instance with the target repositories already created (ask your administrator).
2. A Pantera account and JWT token -- see [Getting Started](getting-started.md).
3. The Pantera base URL (e.g., `https://pantera.example.com:8080`) and repository names for each format.

---

## Step 1: Inventory Your JFrog Usage

Identify which formats and plugins you currently use. Search your codebase:

```bash
# Gradle projects
grep -r "com.jfrog.artifactory\|artifactoryPublish\|jfrog" --include="*.gradle" --include="*.gradle.kts" .

# Maven projects
grep -r "artifactory-maven-plugin\|jfrog" --include="pom.xml" .

# npm projects
grep -r "jfrog\|artifactory" --include=".npmrc" --include="package.json" .

# Python projects
grep -r "jfrog\|artifactory" --include="setup.cfg" --include="pyproject.toml" --include=".pypirc" .

# CI/CD pipelines
grep -r "jfrog\|artifactory" --include="*.yml" --include="*.yaml" --include="Jenkinsfile" --include="*.groovy" .
```

Make a list of every project and which section below applies.

---

## Step 2: Migrate by Format

### Gradle (Maven Publish)

This is the most common migration. JFrog's proprietary `com.jfrog.artifactory` plugin is replaced by Gradle's built-in `maven-publish` plugin.

#### 2a. Remove the JFrog plugin

**In `build.gradle`:**

Remove the plugin application:

```diff
- apply plugin: 'com.jfrog.artifactory'
```

Or in `plugins` block:

```diff
  plugins {
      id 'maven-publish'
-     id 'com.jfrog.artifactory' version '5.x.x'
  }
```

**In `buildscript` dependencies** (root `build.gradle`):

```diff
  buildscript {
      dependencies {
-         classpath "org.jfrog.buildinfo:build-info-extractor-gradle:5.x.x"
      }
  }
```

#### 2b. Replace the `artifactory` block with a publish repository

Remove the entire `artifactory { ... }` block and add a `repositories` section inside `publishing`:

**Before (JFrog):**

```groovy
artifactory {
    contextUrl = 'https://artifactory.example.com/artifactory'
    publish {
        repository {
            repoKey = 'libs-release'
            username = artifactory_username
            password = artifactory_password
        }
        defaults {
            publications('release')
            publishArtifacts = true
            properties = ['qa.level': 'basic', 'q.os': 'android', 'dev.team': 'core']
            publishPom = true
        }
    }
}
```

**After (Pantera):**

```groovy
publishing {
    publications {
        release(MavenPublication) {
            // ... your existing publication config stays the same ...
        }
    }

    repositories {
        maven {
            name = 'pantera'
            url = pantera_repository_url  // e.g., https://pantera.example.com:8080/maven-local
            allowInsecureProtocol = pantera_repository_url.startsWith('http://')
            credentials {
                username = pantera_username
                password = pantera_password
            }
        }
    }
}
```

#### 2c. Update properties

Replace JFrog properties in `gradle.properties` (or CI environment variables):

```properties
# Remove these:
# artifactory_repository_url=https://artifactory.example.com/artifactory
# artifactory_repository_name=libs-release
# artifactory_username=deploy-bot
# artifactory_password=secret

# Add these:
pantera_repository_url=https://pantera.example.com:8080/maven-local
pantera_username=deploy-bot
pantera_password=your-jwt-token
```

> **Note:** In JFrog, the URL was a `contextUrl` plus a `repoKey`. In Pantera, the URL is the full path to the repository: `<base-url>/<repo-name>`.

#### 2d. Update task references

If you have custom tasks that depend on `artifactoryPublish`, replace them:

```diff
  afterEvaluate {
      task cleanBuildPublish {
          dependsOn 'clean'
          dependsOn 'assembleRelease'
-         dependsOn 'artifactoryPublish'
+         dependsOn 'publishReleasePublicationToPanteraRepository'
          tasks.findByName('assembleRelease').mustRunAfter 'clean'
-         tasks.findByName('artifactoryPublish').dependsOn 'generatePomFileForReleasePublication'
-         tasks.findByName('artifactoryPublish').mustRunAfter 'assembleRelease'
+         tasks.findByName('publishReleasePublicationToPanteraRepository').mustRunAfter 'assembleRelease'
      }
  }
```

The `maven-publish` plugin automatically generates the POM, so the explicit `generatePomFileForReleasePublication` dependency is no longer needed.

#### 2e. Publish commands

| JFrog | Pantera |
|-------|---------|
| `./gradlew artifactoryPublish` | `./gradlew publish` |
| `./gradlew artifactoryPublish -Partifactory_...` | `./gradlew publish -Ppantera_...` |
| Custom `cleanBuildPublish` | Same task name, updated dependencies |

The `maven-publish` plugin generates a task per publication and repository:

```
publishReleasePublicationToPanteraRepository   # single publication
publish                                         # all publications to all repositories
```

#### 2f. Gradle Kotlin DSL variant

If your project uses `build.gradle.kts`:

```kotlin
publishing {
    publications {
        create<MavenPublication>("release") {
            afterEvaluate { from(components["release"]) }
        }
    }
    repositories {
        maven {
            name = "pantera"
            url = uri(project.property("pantera_repository_url") as String)
            isAllowInsecureProtocol = (url.toString().startsWith("http://"))
            credentials {
                username = project.property("pantera_username") as String
                password = project.property("pantera_password") as String
            }
        }
    }
}
```

---

### Maven (`pom.xml`)

#### 3a. Remove the JFrog plugin

If your `pom.xml` uses `artifactory-maven-plugin`:

```diff
  <build>
      <plugins>
-         <plugin>
-             <groupId>org.jfrog.buildinfo</groupId>
-             <artifactId>artifactory-maven-plugin</artifactId>
-             <version>3.x.x</version>
-             ...
-         </plugin>
      </plugins>
  </build>
```

#### 3b. Update `distributionManagement`

```diff
  <distributionManagement>
      <repository>
-         <id>central</id>
-         <url>https://artifactory.example.com/artifactory/libs-release</url>
+         <id>pantera</id>
+         <url>https://pantera.example.com:8080/maven-local</url>
      </repository>
      <snapshotRepository>
-         <id>central</id>
-         <url>https://artifactory.example.com/artifactory/libs-snapshot</url>
+         <id>pantera</id>
+         <url>https://pantera.example.com:8080/maven-local</url>
      </snapshotRepository>
  </distributionManagement>
```

#### 3c. Update `~/.m2/settings.xml`

```xml
<settings>
  <servers>
    <server>
      <id>pantera</id>
      <username>your-username</username>
      <password>your-jwt-token</password>
    </server>
  </servers>
  <mirrors>
    <mirror>
      <id>pantera</id>
      <mirrorOf>*</mirrorOf>
      <url>https://pantera.example.com:8080/maven-group</url>
    </mirror>
  </mirrors>
</settings>
```

The `<server><id>` **must** match the `<repository><id>` in your `pom.xml`.

#### 3d. Deploy

```bash
mvn deploy
```

No JFrog plugin, no JFrog CLI. Standard Maven.

---

### npm

#### 4a. Remove JFrog CLI configuration

If you use `jfrog rt npmc` or the `.jfrog` directory:

```bash
rm -rf .jfrog/
```

#### 4b. Update `.npmrc`

```diff
- registry=https://artifactory.example.com/artifactory/api/npm/npm-virtual/
- //artifactory.example.com/artifactory/api/npm/npm-virtual/:_authToken=JFROG_TOKEN
+ registry=https://pantera.example.com:8080/npm-group/
+ //pantera.example.com:8080/:_authToken=your-jwt-token
```

#### 4c. Update `package.json` publishConfig

```diff
  "publishConfig": {
-   "registry": "https://artifactory.example.com/artifactory/api/npm/npm-local/"
+   "registry": "https://pantera.example.com:8080/npm-local/"
  }
```

#### 4d. Publish

```bash
npm publish
```

See the full [npm guide](repositories/npm.md) for yarn and pnpm configuration.

---

### Python (PyPI)

#### 5a. Update `~/.pypirc`

```diff
  [distutils]
  index-servers =
-     artifactory
+     pantera

- [artifactory]
- repository = https://artifactory.example.com/artifactory/api/pypi/pypi-local
- username = deploy-bot
- password = jfrog-token
+ [pantera]
+ repository = https://pantera.example.com:8080/pypi-local/
+ username = your-username
+ password = your-jwt-token
```

#### 5b. Update `pip.conf`

```diff
  [global]
- index-url = https://deploy-bot:token@artifactory.example.com/artifactory/api/pypi/pypi-virtual/simple
+ index-url = https://your-username:your-jwt-token@pantera.example.com:8080/pypi-proxy/simple
+ trusted-host = pantera.example.com
```

#### 5c. Publish

```bash
twine upload -r pantera dist/*
```

See the full [PyPI guide](repositories/pypi.md) for more details.

---

### Docker

#### 6a. Update Docker login

```diff
- docker login artifactory.example.com
+ docker login pantera.example.com:8080
```

Use your Pantera username and JWT token as the password.

#### 6b. Re-tag and push images

```bash
# Re-tag existing images
docker tag artifactory.example.com/docker-local/myimage:1.0 \
           pantera.example.com:8080/docker-local/myimage:1.0

# Push to Pantera
docker push pantera.example.com:8080/docker-local/myimage:1.0
```

#### 6c. Update pull references

Update `Dockerfile` base images, `docker-compose.yml`, Kubernetes manifests, etc.:

```diff
- image: artifactory.example.com/docker-virtual/myimage:1.0
+ image: pantera.example.com:8080/docker-local/myimage:1.0
```

See the full [Docker guide](repositories/docker.md) for daemon configuration.

---

### Helm

#### 7a. Remove JFrog Helm plugin and add Pantera

```diff
- helm repo add myrepo https://artifactory.example.com/artifactory/helm-virtual \
-   --username deploy-bot --password jfrog-token
+ helm repo add pantera https://pantera.example.com:8080/helm-repo \
+   --username your-username --password your-jwt-token

helm repo update
```

#### 7b. Push charts

```bash
# Package the chart
helm package mychart/

# Push to Pantera
helm push mychart-0.1.0.tgz oci://pantera.example.com:8080/helm-repo
```

See the full [Helm guide](repositories/helm.md) for more details.

---

### Go Modules

#### 8a. Update GOPROXY

```diff
- export GOPROXY="https://artifactory.example.com/artifactory/api/go/go-virtual,direct"
+ export GOPROXY="https://your-username:your-jwt-token@pantera.example.com:8080/go-proxy,direct"
+ export GONOSUMCHECK="*"
+ export GOINSECURE="pantera.example.com:8080"  # only if not using HTTPS
```

See the full [Go guide](repositories/go.md) for more details.

---

### PHP (Composer)

#### 9a. Update `composer.json`

```diff
  "repositories": [
-     {
-         "type": "composer",
-         "url": "https://artifactory.example.com/artifactory/api/composer/php-virtual"
-     }
+     {
+         "type": "composer",
+         "url": "https://pantera.example.com:8080/php-repo"
+     }
  ]
```

#### 9b. Configure auth

```bash
composer config --global http-basic.pantera.example.com your-username your-jwt-token
```

See the full [Composer guide](repositories/composer.md) for more details.

---

## Step 3: Update CI/CD Pipelines

### Replace JFrog CLI steps

If your pipelines use `jfrog rt upload`, `jfrog rt build-publish`, or JFrog-specific pipeline steps, replace them with native tool commands.

**Jenkins (Jenkinsfile):**

```diff
  stage('Publish') {
      steps {
-         rtUpload(serverId: 'jfrog', spec: '...')
-         rtPublishBuildInfo(serverId: 'jfrog')
+         sh './gradlew publish -Ppantera_repository_url=$PANTERA_URL -Ppantera_username=$PANTERA_USER -Ppantera_password=$PANTERA_TOKEN'
      }
  }
```

**GitHub Actions:**

```diff
  - name: Publish
-   uses: jfrog/setup-jfrog-cli@v3
-   run: jfrog rt upload "build/*.jar" libs-release/
+   run: ./gradlew publish
    env:
-     JF_URL: ${{ secrets.JFROG_URL }}
-     JF_ACCESS_TOKEN: ${{ secrets.JFROG_TOKEN }}
+     ORG_GRADLE_PROJECT_pantera_repository_url: ${{ secrets.PANTERA_URL }}
+     ORG_GRADLE_PROJECT_pantera_username: ${{ secrets.PANTERA_USER }}
+     ORG_GRADLE_PROJECT_pantera_password: ${{ secrets.PANTERA_TOKEN }}
```

**GitLab CI:**

```diff
  publish:
    script:
-     - jfrog rt upload "build/*.jar" libs-release/
+     - ./gradlew publish
    variables:
-     JFROG_CLI_BUILD_URL: $CI_PIPELINE_URL
+     ORG_GRADLE_PROJECT_pantera_repository_url: $PANTERA_URL
+     ORG_GRADLE_PROJECT_pantera_username: $PANTERA_USER
+     ORG_GRADLE_PROJECT_pantera_password: $PANTERA_TOKEN
```

### Update CI secrets/variables

| Old Variable | New Variable | Value |
|-------------|-------------|-------|
| `JFROG_URL` / `ARTIFACTORY_URL` | `PANTERA_URL` | `https://pantera.example.com:8080` |
| `JFROG_TOKEN` / `ARTIFACTORY_TOKEN` | `PANTERA_TOKEN` | JWT token from Pantera API |
| `JFROG_USER` / `ARTIFACTORY_USER` | `PANTERA_USER` | Pantera username |

---

## Step 4: Migrate Existing Artifacts

If you need to copy existing artifacts from JFrog to Pantera, see the [Import and Migration](import-and-migration.md) guide. The general approach:

1. **Export from JFrog** using the JFrog CLI or REST API:
   ```bash
   # Download all artifacts from a JFrog repo
   jfrog rt download "libs-release/" ./export/ --flat=false
   ```

2. **Import into Pantera** using the bulk import API:
   ```bash
   find ./export -type f \( -name "*.jar" -o -name "*.pom" \) | while read FILE; do
     RELPATH="${FILE#./export/}"
     curl -X PUT \
       -H "Authorization: Basic $(echo -n admin:your-jwt-token | base64)" \
       -H "X-Pantera-Repo-Type: maven" \
       -H "X-Pantera-Idempotency-Key: import-${RELPATH}" \
       --data-binary "@${FILE}" \
       "https://pantera.example.com:8080/.import/maven-local/${RELPATH}"
   done
   ```

3. **For Docker images**, use `skopeo`:
   ```bash
   skopeo copy \
     docker://artifactory.example.com/docker-local/myimage:1.0 \
     docker://pantera.example.com:8080/docker-local/myimage:1.0 \
     --dest-creds your-username:your-jwt-token
   ```

---

## Step 5: Verify and Cut Over

1. **Test builds**: Run a full build cycle (`clean`, `build`, `publish`) against Pantera on a non-critical project first.
2. **Verify artifacts**: Confirm published artifacts are visible via the Pantera UI or API:
   ```bash
   curl -u your-username:your-jwt-token \
     https://pantera.example.com:8080/maven-local/com/example/mylib/1.0.0/
   ```
3. **Parallel period**: Run both JFrog and Pantera in parallel. Publish to both while migrating consumers.
4. **Update consumers**: Switch all downstream projects to pull from Pantera.
5. **Decommission JFrog**: Once all projects are migrated and verified, remove JFrog plugins and decommission the JFrog instance.

---

## Checklist

Use this checklist to track your migration:

- [ ] Inventoried all projects using JFrog (Step 1)
- [ ] Pantera repositories created for each format needed
- [ ] Pantera accounts and JWT tokens generated for CI/CD service accounts
- [ ] **Gradle projects**: Removed `com.jfrog.artifactory` plugin, added `publishing.repositories.maven`
- [ ] **Maven projects**: Updated `distributionManagement` and `settings.xml`
- [ ] **npm projects**: Updated `.npmrc` and `publishConfig`
- [ ] **Python projects**: Updated `.pypirc` and `pip.conf`
- [ ] **Docker projects**: Updated `docker login`, re-tagged images
- [ ] **Helm projects**: Updated `helm repo add`
- [ ] **Go projects**: Updated `GOPROXY`
- [ ] **PHP projects**: Updated `composer.json` and auth
- [ ] CI/CD pipelines updated (removed JFrog CLI steps, updated secrets)
- [ ] Existing artifacts migrated (if needed)
- [ ] Test builds passing against Pantera
- [ ] All consumers switched to Pantera
- [ ] JFrog plugins and dependencies removed from all build files
- [ ] JFrog CLI uninstalled from CI runners

---

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `Could not find method artifactoryPublish()` | JFrog plugin removed but task reference remains | Update custom tasks to use `publish` or `publishXxxToPanteraRepository` |
| `401 Unauthorized` | Wrong or expired JWT token | Generate a new token via `POST /api/v1/auth/token` |
| `405 Method Not Allowed` on publish | Publishing to a group or proxy repository | Publish only to **local** repositories |
| `Could not PUT ... Return code is: 400` | Missing POM or invalid metadata | Ensure `maven-publish` plugin is applied and publication has `from components.xxx` |
| Build still downloads JFrog plugin | `buildscript` classpath not cleaned up | Remove `org.jfrog.buildinfo:build-info-extractor-gradle` from `buildscript.dependencies` |
| `Unknown property 'artifactory_repository_url'` | Old property names in `gradle.properties` | Rename to `pantera_repository_url`, `pantera_username`, `pantera_password` |

---

## Related Pages

- [Import and Migration](import-and-migration.md) -- Bulk import API and backfill CLI
- [Maven](repositories/maven.md) -- Full Maven configuration guide
- [npm](repositories/npm.md) -- Full npm configuration guide
- [Docker](repositories/docker.md) -- Full Docker configuration guide
- [PyPI](repositories/pypi.md) -- Full PyPI configuration guide
- [Helm](repositories/helm.md) -- Full Helm configuration guide
- [Go Modules](repositories/go.md) -- Full Go configuration guide
- [Composer](repositories/composer.md) -- Full Composer configuration guide
- [Getting Started](getting-started.md) -- Obtaining JWT tokens
- [Troubleshooting](troubleshooting.md) -- General troubleshooting