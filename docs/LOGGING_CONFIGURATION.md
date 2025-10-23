# External Log4j2 Configuration Guide

## Overview

Artipie now supports **external log4j2.xml configuration** that can be modified at runtime without rebuilding the Docker image. This allows you to:

- ✅ Change log levels on the fly
- ✅ Enable/disable specific loggers
- ✅ Control third-party library logging
- ✅ No container restart needed (auto-reload every 30 seconds)

---

## Quick Start

### 1. File Location

The log4j2.xml file is located in the docker-compose directory:
```
artipie-main/docker-compose/log4j2.xml
```

### 2. How It Works

The `docker-compose.yaml` mounts this file into the container:

```yaml
volumes:
  - ./log4j2.xml:/etc/artipie/log4j2.xml

environment:
  - LOG4J_CONFIGURATION_FILE=/etc/artipie/log4j2.xml
```

### 3. Making Changes

**Edit the file:**
```bash
cd artipie-main/docker-compose
nano log4j2.xml
```

**Changes apply automatically** within 30 seconds (see `monitorInterval="30"` in the XML).

**No restart needed!** Log4j2 watches the file and reloads it automatically.

---

## Common Use Cases

### Enable Debug Logging for Maven Adapter

**Find this section in log4j2.xml:**
```xml
<!-- <Logger name="com.artipie.maven" level="DEBUG"/> -->
```

**Uncomment it:**
```xml
<Logger name="com.artipie.maven" level="DEBUG"/>
```

**Save the file.** Within 30 seconds, Maven adapter will log at DEBUG level.

### Reduce Noisy Third-Party Logs

**Change Vert.x logging from INFO to WARN:**
```xml
<!-- Before -->
<Logger name="io.vertx" level="INFO"/>

<!-- After -->
<Logger name="io.vertx" level="WARN"/>
```

### Enable All Artipie Debug Logging

**Change the main Artipie logger:**
```xml
<!-- Before -->
<Logger name="com.artipie" level="INFO" additivity="false">

<!-- After -->
<Logger name="com.artipie" level="DEBUG" additivity="false">
```

### Debug S3 Storage Issues

**Add specific loggers:**
```xml
<Logger name="com.artipie.asto.s3" level="DEBUG"/>
<Logger name="software.amazon.awssdk" level="DEBUG"/>
```

### Debug HTTP Client Issues

**Enable Jetty client logging:**
```xml
<Logger name="org.eclipse.jetty.client" level="DEBUG"/>
<Logger name="com.artipie.http.client" level="DEBUG"/>
```

---

## Log Levels Explained

| Level | Description | Use Case |
|-------|-------------|----------|
| `TRACE` | Most verbose | Deep debugging, shows every operation |
| `DEBUG` | Detailed info | Troubleshooting, understanding flow |
| `INFO` | Normal operations | Production default, important events |
| `WARN` | Warnings | Potential issues, degraded performance |
| `ERROR` | Errors only | Production, only log failures |

---

## Available Loggers

### Artipie Application

| Logger | Description | Default Level |
|--------|-------------|---------------|
| `com.artipie` | All Artipie code | INFO |
| `com.artipie.asto` | Storage operations | INFO |
| `com.artipie.http.client` | HTTP client | INFO |
| `security` | Authentication/authorization | DEBUG |

### Repository Adapters

| Logger | Description |
|--------|-------------|
| `com.artipie.maven` | Maven repositories |
| `com.artipie.npm` | NPM repositories |
| `com.artipie.docker` | Docker registries |
| `com.artipie.pypi` | Python packages |
| `com.artipie.helm` | Helm charts |
| `com.artipie.debian` | Debian packages |
| `com.artipie.rpm` | RPM packages |
| `com.artipie.composer` | PHP Composer |
| `com.artipie.nuget` | NuGet packages |
| `com.artipie.gem` | Ruby gems |
| `com.artipie.conda` | Conda packages |
| `com.artipie.conan` | Conan packages |
| `com.artipie.go` | Go modules |
| `com.artipie.hexpm` | Hex packages |

### Third-Party Libraries

| Logger | Description | Default Level |
|--------|-------------|---------------|
| `io.vertx` | Vert.x HTTP server | INFO |
| `org.eclipse.jetty` | Jetty HTTP client | INFO |
| `software.amazon.awssdk` | AWS SDK (S3) | INFO |
| `io.netty` | Netty async I/O | INFO |
| `io.lettuce` | Redis client | INFO |
| `org.quartz` | Cron scheduler | INFO |

---

## Viewing Logs

### View All Logs (JSON Format)
```bash
docker logs -f artipie 2>&1 | jq .
```

### Filter by Log Level
```bash
# Errors only
docker logs artipie 2>&1 | jq 'select(.log.level == "ERROR")'

# Debug and above
docker logs artipie 2>&1 | jq 'select(.log.level == "DEBUG" or .log.level == "INFO" or .log.level == "WARN" or .log.level == "ERROR")'
```

### Filter by Logger
```bash
# Maven adapter logs only
docker logs artipie 2>&1 | jq 'select(.log.logger | startswith("com.artipie.maven"))'

# S3 storage logs
docker logs artipie 2>&1 | jq 'select(.log.logger | contains("s3"))'
```

### Count Errors by Type
```bash
docker logs artipie 2>&1 | jq -r 'select(.log.level == "ERROR") | .error.type' | sort | uniq -c
```

---

## Advanced Configuration

### Change Auto-Reload Interval

**In log4j2.xml header:**
```xml
<!-- Reload every 10 seconds -->
<Configuration status="WARN" monitorInterval="10">

<!-- Reload every 60 seconds -->
<Configuration status="WARN" monitorInterval="60">
```

### Disable Async Logging (for debugging)

**Replace AsyncConsole with Console:**
```xml
<Root level="INFO">
    <AppenderRef ref="Console"/>  <!-- Direct, not async -->
</Root>
```

### Add File Logging

**Add a file appender:**
```xml
<Appenders>
    <Console name="Console" target="SYSTEM_OUT">
        <EcsLayout serviceName="${service.name}"/>
    </Console>
    
    <!-- Add file appender -->
    <RollingFile name="File" fileName="/var/log/artipie/artipie.log"
                 filePattern="/var/log/artipie/artipie-%d{yyyy-MM-dd}.log.gz">
        <EcsLayout serviceName="${service.name}"/>
        <Policies>
            <TimeBasedTriggeringPolicy interval="1"/>
            <SizeBasedTriggeringPolicy size="100MB"/>
        </Policies>
        <DefaultRolloverStrategy max="7"/>
    </RollingFile>
</Appenders>

<Loggers>
    <Root level="INFO">
        <AppenderRef ref="Console"/>
        <AppenderRef ref="File"/>
    </Root>
</Loggers>
```

**Mount log directory:**
```yaml
volumes:
  - ./logs:/var/log/artipie
```

### Change to Plain Text Format (instead of JSON)

**Replace EcsLayout with PatternLayout:**
```xml
<Console name="Console" target="SYSTEM_OUT">
    <PatternLayout pattern="[%p] %d{ISO8601} %t %c - %m%n"/>
</Console>
```

---

## Troubleshooting

### Changes Not Taking Effect

1. **Check monitorInterval:**
   ```xml
   <Configuration status="WARN" monitorInterval="30">
   ```
   Wait at least this many seconds.

2. **Check file is mounted:**
   ```bash
   docker exec artipie cat /etc/artipie/log4j2.xml
   ```

3. **Check for XML syntax errors:**
   ```bash
   docker logs artipie 2>&1 | grep -i "log4j"
   ```

4. **Restart container (last resort):**
   ```bash
   docker-compose restart artipie
   ```

### Too Many Logs

**Increase log levels to WARN or ERROR:**
```xml
<Logger name="io.vertx" level="WARN"/>
<Logger name="org.eclipse.jetty" level="WARN"/>
<Root level="WARN"/>
```

### Not Enough Logs

**Enable DEBUG for specific components:**
```xml
<Logger name="com.artipie" level="DEBUG"/>
```

### Logs Not in JSON Format

**Check EcsLayout is configured:**
```xml
<Console name="Console" target="SYSTEM_OUT">
    <EcsLayout serviceName="${service.name}"/>
</Console>
```

---

## Environment Variables

You can override settings via environment variables in `docker-compose.yaml`:

```yaml
environment:
  # Service identification (appears in logs)
  - ARTIPIE_ENV=production
  
  # Override specific log levels
  - LOG4J_LOGGER_com_artipie=DEBUG
  - LOG4J_LOGGER_com_artipie_maven=TRACE
  
  # Change root level
  - LOG4J_LEVEL=INFO
```

**Note:** File-based configuration takes precedence over environment variables.

---

## Best Practices

### Production
- ✅ Use INFO level for `com.artipie`
- ✅ Use WARN or ERROR for third-party libraries
- ✅ Keep async logging enabled
- ✅ Monitor log volume

### Staging/Development
- ✅ Use DEBUG level for troubleshooting
- ✅ Enable specific adapter logging as needed
- ✅ Can use TRACE for deep debugging
- ✅ Consider file logging for analysis

### Performance
- ✅ Keep async logging enabled (10x faster)
- ✅ Don't enable TRACE in production
- ✅ Filter noisy third-party loggers
- ✅ Use appropriate log levels

---

## Example Scenarios

### Scenario 1: Maven Proxy Not Working

**Enable debug logging:**
```xml
<Logger name="com.artipie.maven" level="DEBUG"/>
<Logger name="com.artipie.http.client" level="DEBUG"/>
<Logger name="org.eclipse.jetty.client" level="DEBUG"/>
```

**View logs:**
```bash
docker logs -f artipie 2>&1 | jq 'select(.log.logger | contains("maven"))'
```

### Scenario 2: S3 Connection Issues

**Enable AWS SDK logging:**
```xml
<Logger name="software.amazon.awssdk" level="DEBUG"/>
<Logger name="com.artipie.asto.s3" level="DEBUG"/>
```

### Scenario 3: Authentication Failures

**Security logger is already at DEBUG:**
```xml
<Logger name="security" level="DEBUG" additivity="false">
    <AppenderRef ref="AsyncConsole"/>
</Logger>
```

**View auth logs:**
```bash
docker logs artipie 2>&1 | jq 'select(.log.logger == "security")'
```

### Scenario 4: Performance Issues

**Enable performance-related logging:**
```xml
<Logger name="com.artipie.asto" level="DEBUG"/>
<Logger name="io.vertx.core.impl.BlockedThreadChecker" level="DEBUG"/>
```

---

## Migration from Old Configuration

### Before (log4j.properties - deprecated)
```properties
log4j.rootLogger=INFO, CONSOLE
log4j.logger.com.artipie=DEBUG
```

### After (log4j2.xml - current)
```xml
<Logger name="com.artipie" level="DEBUG"/>
<Root level="INFO">
    <AppenderRef ref="AsyncConsole"/>
</Root>
```

---

## Summary

✅ **External configuration** - Edit without rebuilding  
✅ **Auto-reload** - Changes apply in 30 seconds  
✅ **Comprehensive** - Control all libraries  
✅ **JSON format** - Elasticsearch/Kibana ready  
✅ **Production ready** - Async, performant  

**File location:** `artipie-main/docker-compose/log4j2.xml`  
**Documentation:** This file  
**Support:** See main documentation for troubleshooting
