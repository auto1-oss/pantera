# Logging

> **Guide:** Admin Guide | **Section:** Logging

Pantera uses Log4j2 with ECS (Elastic Common Schema) JSON layout for structured logging. This format is natively compatible with Elasticsearch, Kibana, Splunk, Datadog, and any JSON log aggregator.

---

## ECS JSON Format

All log entries are emitted as single-line JSON objects. Example:

```json
{
  "@timestamp": "2024-12-01T10:30:00.000Z",
  "log.level": "INFO",
  "message": "AsyncApiVerticle started",
  "service.name": "pantera",
  "service.version": "2.0.0",
  "event.category": "api",
  "event.action": "server_start",
  "event.outcome": "success",
  "url.port": 8086
}
```

### Core Fields (every log entry)

| Field | Description |
|-------|-------------|
| `@timestamp` | ISO-8601 event timestamp |
| `log.level` | Log level: TRACE, DEBUG, INFO, WARN, ERROR, FATAL |
| `message` | Human-readable log message (never raw HTTP request lines) |
| `service.name` | Always `pantera` |
| `service.version` | Pantera version |
| `service.environment` | Deployment environment (development, staging, production) |
| `logger_name` | Fully qualified Java class/logger name |
| `process.thread.name` | Thread name |

### Event Fields

| Field | Description | Values |
|-------|-------------|--------|
| `event.category` | Event category | `api`, `authentication`, `storage`, `cache`, `group`, `repository`, `pypi`, `cooldown` |
| `event.action` | Specific action | `server_start`, `artifact_upload`, `token_validate`, `group_fanout_miss`, `group_lookup_miss`, `sso_callback`, etc. |
| `event.outcome` | Result of the action | `success`, `failure`, `unknown` (ECS-compliant values only) |
| `event.duration` | Duration in milliseconds (Pantera convention, deviates from ECS ns standard) | Long integer |
| `event.reason` | Human-readable reason for the outcome | Free text |

### HTTP Fields (request/response logging)

| Field | Description |
|-------|-------------|
| `http.request.method` | HTTP method (GET, POST, PUT, DELETE) |
| `http.request.headers` | Request headers (sanitized, auth stripped) |
| `http.response.status_code` | Numeric HTTP status code |
| `http.response.body.bytes` | Response body size in bytes |
| `http.response.headers` | Response headers |
| `http.response.mime_type` | Response content type |
| `http.version` | HTTP version (1.1, 2) |

### URL Fields

| Field | Description |
|-------|-------------|
| `url.original` | Full original request URI (path + query) |
| `url.path` | Request path component |
| `url.query` | Query string (if present) |
| `url.domain` | Target hostname |
| `url.port` | Target port |
| `url.full` | Complete URL including scheme |

### User and Auth Fields

| Field | Description |
|-------|-------------|
| `user.name` | Authenticated username |

### Error Fields (present on exceptions)

| Field | Description |
|-------|-------------|
| `error.type` | Exception class name |
| `error.message` | Exception message |
| `error.stack_trace` | Full stack trace |

### Package Fields (artifact operations)

| Field | Description | ECS Standard? |
|-------|-------------|--------------|
| `package.name` | Package/artifact name | Yes |
| `package.version` | Package version | Yes |
| `package.size` | Package size in bytes | Yes |
| `package.path` | Storage path of the artifact | Custom |
| `package.release_date` | Upstream release timestamp (ISO-8601, omitted if unavailable) | Custom |
| `package.age` | Age of the package for cooldown evaluation | Custom |
| `package.checksum` | SHA-256 digest of the artifact | Custom |
| `package.group` | Package group/namespace (e.g., Maven groupId) | Custom |

### Repository Fields (custom, Pantera-specific)

| Field | Description |
|-------|-------------|
| `repository.name` | Repository name |
| `repository.type` | Repository type (maven-proxy, npm-hosted, etc.) |

### File Fields (storage operations)

| Field | Description |
|-------|-------------|
| `file.name` | Filename |
| `file.path` | File path in storage |
| `file.size` | File size in bytes |
| `file.type` | File format (ZIP, TAR.GZ, etc.) |
| `file.directory` | Parent directory |
| `file.target_path` | Destination path (for moves/copies) |

### Container Fields (Docker adapter)

| Field | Description |
|-------|-------------|
| `container.image.name` | Docker image name |
| `container.image.tag` | Image tag |
| `container.image.hash.all` | Image digest |

### Destination Fields (proxy/upstream)

| Field | Description |
|-------|-------------|
| `destination.address` | Upstream server hostname |
| `destination.port` | Upstream server port |

> **Custom fields:** Fields marked "Custom" above are Pantera-specific extensions under the `package.*` and `repository.*` namespaces. ECS is designed to be extensible — custom fields are fully supported by Elasticsearch and won't conflict with standard ECS mappings.

> **Field naming convention:** All fields follow ECS dot-notation (`namespace.field`). Custom fields use `package.*` for artifact metadata and `repository.*` for repository metadata. Never use bare field names or underscores in field names.

---

## External Configuration

Mount a custom `log4j2.xml` to override the default logging configuration.

### Docker Compose

```yaml
services:
  pantera:
    volumes:
      - ./log4j2.xml:/etc/pantera/log4j2.xml
    environment:
      - LOG4J_CONFIGURATION_FILE=/etc/pantera/log4j2.xml
```

### Docker Run

```bash
docker run -d \
  -v /path/to/log4j2.xml:/etc/pantera/log4j2.xml \
  -e LOG4J_CONFIGURATION_FILE=/etc/pantera/log4j2.xml \
  pantera:2.0.0
```

### JAR Deployment

```bash
java -DLOG4J_CONFIGURATION_FILE=/etc/pantera/log4j2.xml \
  -cp pantera.jar:lib/* \
  com.auto1.pantera.VertxMain --config-file=/etc/pantera/pantera.yml
```

---

## Hot Reload

Log4j2 supports hot reload of the configuration file without restarting Pantera. Set the `monitorInterval` attribute in the `<Configuration>` element:

```xml
<Configuration status="WARN" monitorInterval="30">
```

With `monitorInterval="30"`, Log4j2 checks the configuration file every 30 seconds and reloads if changed. This allows you to change log levels in production without any downtime.

### Example log4j2.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="WARN" monitorInterval="30">
  <Appenders>
    <Console name="Console" target="SYSTEM_OUT">
      <EcsLayout serviceName="pantera" serviceVersion="2.0.0"/>
    </Console>
  </Appenders>
  <Loggers>
    <Logger name="com.auto1.pantera" level="INFO"/>
    <Logger name="com.auto1.pantera.npm" level="DEBUG"/>
    <Logger name="security" level="INFO"/>
    <Logger name="io.vertx" level="WARN"/>
    <Logger name="org.eclipse.jetty" level="WARN"/>
    <Logger name="software.amazon.awssdk" level="WARN"/>
    <Root level="INFO">
      <AppenderRef ref="Console"/>
    </Root>
  </Loggers>
</Configuration>
```

---

## Available Loggers

Configure log levels per subsystem by adding `<Logger>` entries to your `log4j2.xml`:

### Application Loggers

| Logger Name | Default Level | Covers |
|-------------|---------------|--------|
| `com.auto1.pantera` | INFO | All Pantera application code |
| `com.auto1.pantera.asto` | INFO | Storage operations (S3, filesystem) |
| `com.auto1.pantera.http.client` | INFO | Outbound HTTP client (proxy requests) |
| `com.auto1.pantera.scheduling` | INFO | Scheduled tasks and background jobs |
| `security` | INFO | Authentication and authorization events |

### Adapter Loggers

| Logger Name | Default Level | Format |
|-------------|---------------|--------|
| `com.auto1.pantera.maven` | INFO | Maven |
| `com.auto1.pantera.npm` | INFO | npm |
| `com.auto1.pantera.docker` | INFO | Docker |
| `com.auto1.pantera.pypi` | INFO | PyPI |
| `com.auto1.pantera.helm` | INFO | Helm |
| `com.auto1.pantera.debian` | INFO | Debian |
| `com.auto1.pantera.rpm` | INFO | RPM |
| `com.auto1.pantera.composer` | INFO | Composer |
| `com.auto1.pantera.nuget` | INFO | NuGet |
| `com.auto1.pantera.gem` | INFO | RubyGems |
| `com.auto1.pantera.conda` | INFO | Conda |
| `com.auto1.pantera.conan` | INFO | Conan |
| `com.auto1.pantera.go` | INFO | Go |
| `com.auto1.pantera.hexpm` | INFO | Hex |

### Framework Loggers

| Logger Name | Default Level | Covers |
|-------------|---------------|--------|
| `io.vertx` | INFO | Vert.x framework |
| `org.eclipse.jetty` | INFO | Jetty HTTP client |
| `software.amazon.awssdk` | INFO | AWS SDK (S3 operations) |
| `io.netty` | INFO | Netty async I/O |
| `org.quartz` | INFO | Quartz scheduler |

### Enabling Debug Logging

To enable DEBUG logging for a specific adapter (e.g., npm):

```xml
<Logger name="com.auto1.pantera.npm" level="DEBUG"/>
```

To enable DEBUG for all proxy HTTP client requests:

```xml
<Logger name="com.auto1.pantera.http.client" level="DEBUG"/>
```

To enable DEBUG for S3 storage operations:

```xml
<Logger name="com.auto1.pantera.asto" level="DEBUG"/>
```

---

## Viewing and Filtering Logs

### Docker Logs

```bash
# View all logs
docker logs pantera

# Follow logs in real time
docker logs -f pantera

# Last 100 lines
docker logs --tail 100 pantera
```

### Filtering with jq

Since logs are JSON, use `jq` for powerful filtering:

```bash
# Filter by log level
docker logs pantera 2>&1 | jq 'select(.["log.level"] == "ERROR")'

# Filter by event category
docker logs pantera 2>&1 | jq 'select(.["event.category"] == "authentication")'

# Filter by repository adapter
docker logs pantera 2>&1 | jq 'select(.["logger_name"] | startswith("com.auto1.pantera.npm"))'

# Filter errors with stack traces
docker logs pantera 2>&1 | jq 'select(.["error.type"] != null)'

# Show only timestamp, level, and message
docker logs pantera 2>&1 | jq '{time: .["@timestamp"], level: .["log.level"], msg: .message}'

# Filter HTTP 4xx/5xx errors with structured fields
docker logs pantera 2>&1 | jq 'select(.["http.response.status_code"] >= 400)'

# Filter by specific repository
docker logs pantera 2>&1 | jq 'select(.["repository.name"] == "pypi-proxy")'

# Show request details for slow requests (>1s)
docker logs pantera 2>&1 | jq 'select(.["event.duration"] > 1000) | {method: .["http.request.method"], path: .["url.original"], status: .["http.response.status_code"], duration_ms: .["event.duration"]}'

# Filter group lookup failures (artifact not found in any member)
docker logs pantera 2>&1 | jq 'select(.["event.action"] == "group_lookup_miss")'

# Filter authentication events
docker logs pantera 2>&1 | jq 'select(.["event.category"] == "authentication" and .["event.outcome"] == "failure")'
```

### Elasticsearch / Kibana Queries

When using Kibana Discover or ES|QL:

```
# All HTTP errors
http.response.status_code >= 400

# Group lookup misses (real 404s, not fanout noise)
event.action: "group_lookup_miss"

# Authentication failures
event.category: "authentication" AND event.outcome: "failure"

# Slow requests (>2 seconds)
event.duration > 2000

# Specific package operations
package.name: "pydantic" AND event.category: "cooldown"
```

> **Note:** Individual group member 404s during fanout are logged at `DEBUG` level with `event.action: "group_fanout_miss"`. These are expected behavior and not visible at default INFO level. Only the aggregate "not found in any member" is logged at `WARN` with `event.action: "group_lookup_miss"`.

### Log Aggregation

For production deployments, ship logs to a centralized system:

- **Elasticsearch + Kibana** -- Native ECS support; logs index directly with correct field mappings.
- **Splunk** -- Use the JSON source type for automatic field extraction.
- **Datadog** -- Configure the Docker log integration with JSON parsing.
- **Grafana Loki** -- Use the Docker log driver or Promtail for log collection.

---

## GC Logs

GC logs are written to `/var/pantera/logs/gc.log` by default (configured in JVM_ARGS). They use the JDK Unified Logging format:

```
-Xlog:gc*:file=/var/pantera/logs/gc.log:time,uptime:filecount=5,filesize=100m
```

This creates up to 5 rotated GC log files, each up to 100 MB. Analyze GC logs with tools like GCViewer or GCEasy.

---

## Heap Dumps

Heap dumps are automatically generated on OutOfMemoryError:

```
-XX:+HeapDumpOnOutOfMemoryError
-XX:HeapDumpPath=/var/pantera/logs/dumps/heapdump.hprof
```

Analyze heap dumps with Eclipse MAT, VisualVM, or JProfiler.

---

## Related Pages

- [Configuration](configuration.md) -- Logging-related configuration
- [Environment Variables](environment-variables.md) -- LOG4J_CONFIGURATION_FILE and JVM_ARGS
- [Monitoring](monitoring.md) -- Complementary metrics-based observability
- [Troubleshooting](troubleshooting.md) -- Using logs to diagnose issues
- [Performance Tuning](performance-tuning.md) -- JVM settings including GC logging
