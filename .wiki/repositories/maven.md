## Maven

To host a [Maven](https://maven.apache.org/) repository for Java artifacts and dependencies try the
following configuration:

```yaml
repo:
  type: maven
  storage:
    type: fs
    path: /tmp/artipie/data
```

To use this repository as regular maven repository in Java project, add the following configuration
into `pom` project file (alternatively [configure](https://maven.apache.org/guides/mini/guide-multiple-repositories.html)
it via [`~/.m2/settings.xml`](https://maven.apache.org/settings.html)):

```xml
<repositories>
    <repository>
        <id>{artipie-server-id}</id>
        <url>http://{host}:{port}/{repository-name}</url>
    </repository>
</repositories>
```
Then run `mvn install` (or `mvn install -U` to force download dependencies).

To deploy the project into Artipie repository, add [`<distributionManagement>`](https://maven.apache.org/pom.html#Distribution_Management)
section to [`pom.xml`](https://maven.apache.org/guides/introduction/introduction-to-the-pom.html)
project file (don't forget to specify authentication credentials in
[`~/.m2/settings.xml`](https://maven.apache.org/settings.html#Servers)
for `artipie` server):

```xml
<project>
  [...]
  <distributionManagement>
    <snapshotRepository>
      <id>artipie</id>
      <url>http://{host}:{port}/{repository-name}</url>
    </snapshotRepository>
    <repository>
      <id>artipie</id>
      <url>http://{host}:{port}/{repository-name}</url>
    </repository>
  </distributionManagement>
</project>
```
In the examples above `{host}` and `{port}` are Artipie service host and port, `{repository-name}`
is the name of maven repository.

## Maven Group Repository

A Maven group aggregates multiple Maven repositories (local and proxy) into a single virtual repository.
Artipie merges `maven-metadata.xml` files from all members.

```yaml
repo:
  type: maven-group
  storage:
    type: fs
    path: /var/artipie/data
  settings:
    repositories:
      - maven-releases    # local repository
      - maven-snapshots   # local repository
      - central-proxy     # proxy to Maven Central
    group:
      index:
        remote_exists_ttl: 15m
        remote_not_exists_ttl: 5m
      metadata:
        ttl: 5m
```

Members are listed in priority order - if the same artifact version exists in multiple members,
the first member wins. Configure in your `pom.xml`:

```xml
<repositories>
    <repository>
        <id>artipie-group</id>
        <url>http://{host}:{port}/maven-group</url>
    </repository>
</repositories>
```

See [Group Cache Configuration](../Configuration-Group-Cache) for detailed cache settings.