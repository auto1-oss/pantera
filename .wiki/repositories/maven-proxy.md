## Maven proxy

Maven proxy repository will redirect all the requests to the remotes. Repository configuration allows
to specify several remotes, Artipie will try to obtain the artifact from the remotes list one by one
while the artifact is not found. If storage is configured, previously downloaded packages will be
available when source repository is down:

```yaml
repo:
  type: maven-proxy
  storage:
    type: fs
    path: /tmp/artipie/maven-central-cache
  http_client: # optional, settings for the HttpClient that will be used in xxx-proxy repositories
    connection_timeout: 25000 # optional, default 15000 ms 
    idle_timeout: 500 # optional, default 0
    trust_all: true # optional, default false
    follow_redirects: true # optional, default true
    http3: true # optional, default false
    jks: # optional
      path: /var/artipie/keystore.jks
      password: secret
    proxies:
      - url: http://proxy1.com
      - url: https://proxy2.com
        # the HTTP "Basic" authentication defined in RFC 2617
        realm: user_realm # if this field is defined, then `username` and `password` are mandatory
        username: user_name
        password: user_password
  remotes:
    - url: https://repo.maven.apache.org/maven2
      username: Aladdin # optional
      password: OpenSesame # optional
    - url: https://maven.example.com/
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
where `{host}` and `{port}` are Artipie service host and port, `{repository-name}`
is the name of maven repository.

### Cooldown behaviour

Proxy repositories participate in the global cooldown policy (see [configuration](../Configuration.md#cooldown-settings)).
If a requested Maven artefact is newer than the cached version, or if it was released within the configured
fresh-release window, Artipie temporarily blocks the download and responds with HTTP 403 providing the
expected unblock time. Administrators can unblock specific artefacts (and their dependency set) through the
REST API endpoints exposed under `/api/v1/repository/{rname}/cooldown/`. After the cooldown finishes or a
manual unblock occurs, the version is permanently served without further delay.
