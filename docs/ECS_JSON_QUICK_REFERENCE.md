# ECS JSON Logging - Quick Reference

## Sample Log Output

### Error Log Example
```json
{
  "@timestamp": "2025-10-23T16:49:47.465Z",
  "log.level": "ERROR",
  "message": "Unable to execute HTTP request: Acquire operation took longer than the configured maximum time",
  "ecs.version": "1.2.0",
  "service.name": "artipie",
  "service.version": "1.0-SNAPSHOT",
  "service.environment": "production",
  "event.dataset": "artipie",
  "process.thread.name": "vert.x-eventloop-thread-3",
  "log.logger": "com.artipie.asto.s3.S3Storage",
  "error.type": "software.amazon.awssdk.core.exception.SdkClientException",
  "error.message": "Unable to execute HTTP request: Acquire operation took longer than the configured maximum time",
  "error.stack_trace": "software.amazon.awssdk.core.exception.SdkClientException: Unable to execute HTTP request: Acquire operation took longer than the configured maximum time\n\tat software.amazon.awssdk.core.exception.SdkClientException$BuilderImpl.build(SdkClientException.java:111)\n\tat software.amazon.awssdk.core.internal.http.pipeline.stages.AsyncExecutionFailureExceptionReportingStage.execute(AsyncExecutionFailureExceptionReportingStage.java:51)"
}
```

### Info Log Example
```json
{
  "@timestamp": "2025-10-23T16:50:12.123Z",
  "log.level": "INFO",
  "message": "Repository maven-proxy initialized successfully",
  "ecs.version": "1.2.0",
  "service.name": "artipie",
  "service.version": "1.0-SNAPSHOT",
  "service.environment": "production",
  "event.dataset": "artipie",
  "process.thread.name": "main",
  "log.logger": "com.artipie.RepositorySlices"
}
```

### Debug Log with MDC Context
```json
{
  "@timestamp": "2025-10-23T16:50:15.789Z",
  "log.level": "DEBUG",
  "message": "Downloading artifact from S3",
  "ecs.version": "1.2.0",
  "service.name": "artipie",
  "service.version": "1.0-SNAPSHOT",
  "service.environment": "production",
  "event.dataset": "artipie",
  "process.thread.name": "vert.x-worker-thread-2",
  "log.logger": "com.artipie.asto.s3.S3Storage",
  "labels": {
    "repository": "maven-proxy",
    "artifact": "org/springframework/spring-core/5.3.20/spring-core-5.3.20.jar",
    "request_id": "abc123"
  }
}
```

## Key ECS Fields

| Field | Description | Example |
|-------|-------------|---------|
| `@timestamp` | ISO8601 timestamp in UTC | `2025-10-23T16:49:47.465Z` |
| `log.level` | Log level | `ERROR`, `WARN`, `INFO`, `DEBUG`, `TRACE` |
| `message` | Log message | `Request failed` |
| `service.name` | Service identifier | `artipie`, `artipie-npm` |
| `service.version` | Service version | `1.0-SNAPSHOT` |
| `service.environment` | Environment | `production`, `staging`, `development` |
| `process.thread.name` | Thread name | `vert.x-eventloop-thread-3` |
| `log.logger` | Logger class name | `com.artipie.http.client.jetty.JettyClientSlice` |
| `error.type` | Exception class | `java.io.EOFException` |
| `error.message` | Exception message | `Connection closed` |
| `error.stack_trace` | Full stack trace | Multi-line string |
| `labels` | Custom key-value pairs | `{"repository": "maven-proxy"}` |

## Kibana Queries

### Find All Errors
```
log.level: ERROR
```

### Find Specific Error Type
```
error.type: "SdkClientException"
```

### Find Connection Issues
```
error.message: *connection* OR error.message: *timeout*
```

### Find Errors in Specific Service
```
service.name: artipie AND log.level: ERROR
```

### Find Errors from Specific Logger
```
log.logger: "com.artipie.asto.s3.S3Storage" AND log.level: ERROR
```

### Find Errors in Time Range
```
@timestamp: [2025-10-23T16:00:00 TO 2025-10-23T17:00:00] AND log.level: ERROR
```

### Find by Custom Label
```
labels.repository: "maven-proxy" AND log.level: ERROR
```

## Docker Log Viewing

### View JSON logs
```bash
docker logs artipie 2>&1 | jq .
```

### Filter by log level
```bash
docker logs artipie 2>&1 | jq 'select(.log.level == "ERROR")'
```

### Extract error messages
```bash
docker logs artipie 2>&1 | jq -r 'select(.log.level == "ERROR") | .message'
```

### Count errors by type
```bash
docker logs artipie 2>&1 | jq -r 'select(.log.level == "ERROR") | .error.type' | sort | uniq -c
```

### View last 100 errors
```bash
docker logs --tail 1000 artipie 2>&1 | jq 'select(.log.level == "ERROR")' | tail -100
```

## Adding Custom Fields (MDC)

In your Java code:

```java
import org.slf4j.MDC;

// Add context
MDC.put("repository", "maven-proxy");
MDC.put("artifact", artifactPath);
MDC.put("request_id", requestId);

try {
    // Your code
    logger.info("Processing artifact");
} finally {
    // Clean up
    MDC.clear();
}
```

This will add fields to the JSON output:
```json
{
  "message": "Processing artifact",
  "labels": {
    "repository": "maven-proxy",
    "artifact": "org/springframework/spring-core/5.3.20/spring-core-5.3.20.jar",
    "request_id": "abc123"
  }
}
```

## Performance Tips

1. **Use Async Logging** - Already configured in artipie-main
2. **Filter at Source** - Set appropriate log levels per package
3. **Avoid String Concatenation** - Use parameterized logging:
   ```java
   // Bad
   logger.debug("Processing " + artifact + " from " + repo);
   
   // Good
   logger.debug("Processing {} from {}", artifact, repo);
   ```
4. **Use Markers** - For special event types:
   ```java
   import org.slf4j.Marker;
   import org.slf4j.MarkerFactory;
   
   Marker SECURITY = MarkerFactory.getMarker("SECURITY");
   logger.warn(SECURITY, "Failed login attempt for user: {}", username);
   ```

## Elasticsearch Index Settings

Recommended settings for Artipie logs:

```json
{
  "settings": {
    "number_of_shards": 1,
    "number_of_replicas": 1,
    "index.lifecycle.name": "artipie-logs-policy",
    "index.lifecycle.rollover_alias": "artipie-logs"
  },
  "mappings": {
    "properties": {
      "@timestamp": { "type": "date" },
      "log.level": { "type": "keyword" },
      "message": { 
        "type": "text",
        "fields": {
          "keyword": { "type": "keyword", "ignore_above": 256 }
        }
      },
      "service.name": { "type": "keyword" },
      "service.version": { "type": "keyword" },
      "service.environment": { "type": "keyword" },
      "process.thread.name": { "type": "keyword" },
      "log.logger": { "type": "keyword" },
      "error.type": { "type": "keyword" },
      "error.message": { "type": "text" },
      "error.stack_trace": { 
        "type": "text",
        "index": false
      },
      "labels": { "type": "object" }
    }
  }
}
```

## Troubleshooting

### Logs not appearing in Elasticsearch

1. Check Docker logs are in JSON format:
   ```bash
   docker logs artipie 2>&1 | head -1 | jq .
   ```

2. Verify Filebeat is running:
   ```bash
   docker ps | grep filebeat
   ```

3. Check Filebeat logs:
   ```bash
   docker logs filebeat
   ```

### Invalid JSON in logs

If you see plain text mixed with JSON, check:
- All modules have log4j2.xml (not log4j.properties)
- No third-party libraries using different logging
- Stdout/stderr not mixed

### Performance issues

If logging is slow:
- Ensure async logging is enabled
- Increase ring buffer size in log4j2.xml
- Reduce log level for noisy packages
- Consider batching in Filebeat

## Environment Variables

Set these in Docker Compose or Kubernetes:

```yaml
environment:
  # Service identification
  - ARTIPIE_ENV=production
  
  # Log4j2 configuration
  - LOG4J_FORMAT_MSG_NO_LOOKUPS=true
  - log4j2.asyncLoggerRingBufferSize=262144
  - log4j2.asyncLoggerWaitStrategy=Sleep
  
  # Optional: Override config file location
  - log4j.configurationFile=/etc/artipie/log4j2.xml
```
