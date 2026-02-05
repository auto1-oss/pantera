# High-Performance Parser Integration Design

**Date:** November 23, 2024  
**Purpose:** Design optimal parsers for metadata filtering with minimal performance overhead

---

## Per-adapter cooldown metadata pattern

Parsers and inspectors collaborate per adapter to support cooldown with minimal
overhead:

- Each adapter defines a `MetadataParser<T>` for its metadata format. The parser is
  responsible for parsing the raw response, extracting the ordered version list, and
  (optionally) exposing the current `latest` tag.
- For formats that carry release timestamps directly in metadata, the parser may also
  implement `ReleaseDateProvider<T>` to expose a `Map<String, Instant>` of
  `version → releaseDate`.
- Adapter-specific inspectors implement `CooldownInspector` and, when they can benefit
  from pre-parsed release dates, `MetadataAwareInspector`.
- `JdbcCooldownMetadataService` wires these together: after parsing metadata it calls a
  helper that, when `repoType` and types match, preloads the release-date map from the
  parser into the inspector before evaluating versions.
- NPM is the reference implementation of this pattern:
  - `NpmMetadataParser` implements `MetadataParser<JsonObject>` and
    `ReleaseDateProvider<JsonObject>` by reading the `time` object.
  - `NpmCooldownInspector` implements `CooldownInspector` and `MetadataAwareInspector`
    and serves release dates only from the preloaded map.
  - The metadata service evaluates cooldown only for a bounded subset of the newest
    versions, ensuring predictable latency even for very large packuments.

Future adapters should follow this pattern where possible so release dates are derived
from already-fetched metadata rather than issuing additional upstream HTTP requests.

## Parser Selection Criteria

### Requirements

1. **Memory Efficiency:** Avoid loading entire metadata into memory for large files
2. **Performance:** Parse and filter in < 50ms for P99
3. **Correctness:** Preserve metadata format integrity
4. **Maintainability:** Use well-tested libraries, avoid custom parsers

### Metadata Size Analysis

| Package Type | Typical Size | Large Package Size | Format |
|-------------|--------------|-------------------|--------|
| NPM | 10-100 KB | 1-10 MB (1000+ versions) | JSON |
| PyPI | 5-50 KB | 500 KB - 2 MB | HTML or JSON |
| Maven | 1-10 KB | 50-200 KB | XML |
| Gradle | Same as Maven | Same as Maven | XML + JSON |
| Composer | 10-100 KB | 500 KB - 2 MB | JSON |
| Go | < 1 KB | 10-50 KB | Plain text |

---

## 1. NPM Metadata Parser

### Format

```json
{
  "name": "lodash",
  "dist-tags": {"latest": "4.17.21"},
  "versions": {
    "4.17.21": { /* full package.json */ },
    "4.17.20": { /* full package.json */ }
  },
  "time": {
    "4.17.21": "2021-02-20T16:06:50.000Z",
    "4.17.20": "2020-02-18T21:20:08.000Z"
  }
}
```

### Parser Strategy

**Library:** Jackson (already used in Artipie)

**Approach:** 
- **Small files (< 1 MB):** Parse entire JSON into `JsonNode`, filter, rewrite
- **Large files (> 1 MB):** Use streaming parser to avoid memory spike

**Implementation:**

```java
public class NpmMetadataParser implements MetadataParser<JsonNode> {
    
    private final ObjectMapper mapper = new ObjectMapper();
    
    @Override
    public JsonNode parse(byte[] bytes) throws MetadataParseException {
        try {
            return mapper.readTree(bytes);
        } catch (IOException e) {
            throw new MetadataParseException("Failed to parse NPM metadata", e);
        }
    }
    
    @Override
    public List<String> extractVersions(JsonNode metadata) {
        List<String> versions = new ArrayList<>();
        JsonNode versionsNode = metadata.get("versions");
        if (versionsNode != null && versionsNode.isObject()) {
            versionsNode.fieldNames().forEachRemaining(versions::add);
        }
        return versions;
    }
    
    @Override
    public Optional<String> getLatestVersion(JsonNode metadata) {
        JsonNode distTags = metadata.get("dist-tags");
        if (distTags != null && distTags.has("latest")) {
            return Optional.of(distTags.get("latest").asText());
        }
        return Optional.empty();
    }
}
```

**Filter:**

```java
public class NpmMetadataFilter implements MetadataFilter<JsonNode> {
    
    @Override
    public JsonNode filter(JsonNode metadata, Set<String> blockedVersions) {
        if (blockedVersions.isEmpty()) {
            return metadata;
        }
        
        ObjectNode root = (ObjectNode) metadata;
        
        // Filter versions object
        ObjectNode versions = (ObjectNode) root.get("versions");
        if (versions != null) {
            blockedVersions.forEach(versions::remove);
        }
        
        // Filter time object
        ObjectNode time = (ObjectNode) root.get("time");
        if (time != null) {
            blockedVersions.forEach(time::remove);
        }
        
        return root;
    }
    
    @Override
    public JsonNode updateLatest(JsonNode metadata, String newLatest) {
        ObjectNode root = (ObjectNode) metadata;
        ObjectNode distTags = (ObjectNode) root.get("dist-tags");
        if (distTags != null) {
            distTags.put("latest", newLatest);
        }
        return root;
    }
}
```

**Rewriter:**

```java
public class NpmMetadataRewriter implements MetadataRewriter<JsonNode> {
    
    private final ObjectMapper mapper = new ObjectMapper();
    
    @Override
    public byte[] rewrite(JsonNode metadata) throws MetadataRewriteException {
        try {
            return mapper.writeValueAsBytes(metadata);
        } catch (IOException e) {
            throw new MetadataRewriteException("Failed to rewrite NPM metadata", e);
        }
    }
    
    @Override
    public String getContentType() {
        return "application/json";
    }
}
```

**Performance:**
- Parse: 5-20ms (small), 20-100ms (large)
- Filter: 1-5ms
- Rewrite: 5-20ms (small), 20-100ms (large)
- **Total: 10-50ms (small), 40-200ms (large)**

---

## 2. PyPI Metadata Parser

### Format (HTML - Simple Repository API)

```html
<!DOCTYPE html>
<html>
  <body>
    <h1>Links for requests</h1>
    <a href="https://files.pythonhosted.org/.../requests-2.28.0.tar.gz#sha256=abc123">requests-2.28.0.tar.gz</a><br/>
    <a href="https://files.pythonhosted.org/.../requests-2.27.1.tar.gz#sha256=def456">requests-2.27.1.tar.gz</a><br/>
  </body>
</html>
```

### Parser Strategy

**Library:** Jsoup (HTML parser)

**Approach:** Parse HTML, extract `<a>` tags, filter, rebuild HTML

**Implementation:**

```java
public class PyPiMetadataParser implements MetadataParser<Document> {
    
    @Override
    public Document parse(byte[] bytes) throws MetadataParseException {
        try {
            return Jsoup.parse(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new MetadataParseException("Failed to parse PyPI metadata", e);
        }
    }
    
    @Override
    public List<String> extractVersions(Document metadata) {
        List<String> versions = new ArrayList<>();
        Elements links = metadata.select("a");
        for (Element link : links) {
            String filename = link.text();
            // Extract version from filename (e.g., "requests-2.28.0.tar.gz" -> "2.28.0")
            String version = extractVersionFromFilename(filename);
            if (version != null) {
                versions.add(version);
            }
        }
        return versions;
    }
    
    private String extractVersionFromFilename(String filename) {
        // Pattern: {package}-{version}.{ext}
        // Example: requests-2.28.0.tar.gz -> 2.28.0
        Pattern pattern = Pattern.compile("^[^-]+-([0-9][^-]*?)\\.(tar\\.gz|whl|zip|egg)$");
        Matcher matcher = pattern.matcher(filename);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }
    
    @Override
    public Optional<String> getLatestVersion(Document metadata) {
        // PyPI HTML doesn't have explicit "latest" tag
        return Optional.empty();
    }
}
```

**Filter:**

```java
public class PyPiMetadataFilter implements MetadataFilter<Document> {
    
    @Override
    public Document filter(Document metadata, Set<String> blockedVersions) {
        if (blockedVersions.isEmpty()) {
            return metadata;
        }
        
        Elements links = metadata.select("a");
        for (Element link : links) {
            String filename = link.text();
            String version = extractVersionFromFilename(filename);
            if (version != null && blockedVersions.contains(version)) {
                // Remove the <a> tag and the <br/> after it
                Element br = link.nextElementSibling();
                link.remove();
                if (br != null && br.tagName().equals("br")) {
                    br.remove();
                }
            }
        }
        
        return metadata;
    }
    
    @Override
    public Document updateLatest(Document metadata, String newLatest) {
        // PyPI HTML doesn't have "latest" tag to update
        return metadata;
    }
}
```

**Rewriter:**

```java
public class PyPiMetadataRewriter implements MetadataRewriter<Document> {
    
    @Override
    public byte[] rewrite(Document metadata) {
        return metadata.html().getBytes(StandardCharsets.UTF_8);
    }
    
    @Override
    public String getContentType() {
        return "text/html; charset=utf-8";
    }
}
```

**Performance:**
- Parse: 5-15ms
- Filter: 1-5ms
- Rewrite: 5-15ms
- **Total: 10-35ms**

---

## 3. Maven Metadata Parser

### Format

```xml
<?xml version="1.0" encoding="UTF-8"?>
<metadata>
  <groupId>org.springframework</groupId>
  <artifactId>spring-core</artifactId>
  <versioning>
    <latest>6.0.0</latest>
    <release>6.0.0</release>
    <versions>
      <version>6.0.0</version>
      <version>5.3.23</version>
      <version>5.3.22</version>
    </versions>
    <lastUpdated>20221116120000</lastUpdated>
  </versioning>
</metadata>
```

### Parser Strategy

**Library:** DOM4J or JDOM2 (lightweight XML parsers)

**Approach:** Parse XML, modify DOM, serialize back

**Implementation:**

```java
public class MavenMetadataParser implements MetadataParser<Document> {
    
    private final SAXReader reader = new SAXReader();
    
    @Override
    public Document parse(byte[] bytes) throws MetadataParseException {
        try {
            return reader.read(new ByteArrayInputStream(bytes));
        } catch (DocumentException e) {
            throw new MetadataParseException("Failed to parse Maven metadata", e);
        }
    }
    
    @Override
    public List<String> extractVersions(Document metadata) {
        List<String> versions = new ArrayList<>();
        Element root = metadata.getRootElement();
        Element versioning = root.element("versioning");
        if (versioning != null) {
            Element versionsElem = versioning.element("versions");
            if (versionsElem != null) {
                for (Element version : versionsElem.elements("version")) {
                    versions.add(version.getText());
                }
            }
        }
        return versions;
    }
    
    @Override
    public Optional<String> getLatestVersion(Document metadata) {
        Element root = metadata.getRootElement();
        Element versioning = root.element("versioning");
        if (versioning != null) {
            Element latest = versioning.element("latest");
            if (latest != null) {
                return Optional.of(latest.getText());
            }
        }
        return Optional.empty();
    }
}
```

**Filter:**

```java
public class MavenMetadataFilter implements MetadataFilter<Document> {
    
    @Override
    public Document filter(Document metadata, Set<String> blockedVersions) {
        if (blockedVersions.isEmpty()) {
            return metadata;
        }
        
        Element root = metadata.getRootElement();
        Element versioning = root.element("versioning");
        if (versioning != null) {
            Element versionsElem = versioning.element("versions");
            if (versionsElem != null) {
                List<Element> toRemove = new ArrayList<>();
                for (Element version : versionsElem.elements("version")) {
                    if (blockedVersions.contains(version.getText())) {
                        toRemove.add(version);
                    }
                }
                toRemove.forEach(versionsElem::remove);
            }
        }
        
        return metadata;
    }
    
    @Override
    public Document updateLatest(Document metadata, String newLatest) {
        Element root = metadata.getRootElement();
        Element versioning = root.element("versioning");
        if (versioning != null) {
            Element latest = versioning.element("latest");
            if (latest != null) {
                latest.setText(newLatest);
            }
            Element release = versioning.element("release");
            if (release != null) {
                release.setText(newLatest);
            }
            // Update lastUpdated timestamp
            Element lastUpdated = versioning.element("lastUpdated");
            if (lastUpdated != null) {
                String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
                lastUpdated.setText(timestamp);
            }
        }
        return metadata;
    }
}
```

**Rewriter:**

```java
public class MavenMetadataRewriter implements MetadataRewriter<Document> {
    
    @Override
    public byte[] rewrite(Document metadata) throws MetadataRewriteException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            XMLWriter writer = new XMLWriter(out, OutputFormat.createPrettyPrint());
            writer.write(metadata);
            writer.close();
            return out.toByteArray();
        } catch (IOException e) {
            throw new MetadataRewriteException("Failed to rewrite Maven metadata", e);
        }
    }
    
    @Override
    public String getContentType() {
        return "application/xml";
    }
}
```

**Performance:**
- Parse: 1-5ms
- Filter: < 1ms
- Rewrite: 1-5ms
- **Total: 2-10ms**

---

## 4. Composer Metadata Parser

### Format

```json
{
  "packages": {
    "vendor/package": {
      "2.0.0": { /* version metadata */ },
      "1.9.0": { /* version metadata */ }
    }
  }
}
```

### Parser Strategy

**Same as NPM** - Use Jackson for JSON parsing

**Implementation:** Similar to NPM parser, but extract versions from `packages.{name}` object keys

**Performance:** Same as NPM (10-50ms small, 40-200ms large)

---

## 5. Go Metadata Parser

### Format

```
v1.3.0
v1.2.0
v1.1.1
```

### Parser Strategy

**Approach:** Simple string split by newlines

**Implementation:**

```java
public class GoMetadataParser implements MetadataParser<List<String>> {
    
    @Override
    public List<String> parse(byte[] bytes) {
        String content = new String(bytes, StandardCharsets.UTF_8);
        return Arrays.stream(content.split("\n"))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toList());
    }
    
    @Override
    public List<String> extractVersions(List<String> metadata) {
        return metadata;
    }
    
    @Override
    public Optional<String> getLatestVersion(List<String> metadata) {
        // Go list doesn't have explicit "latest" tag
        return Optional.empty();
    }
}
```

**Filter:**

```java
public class GoMetadataFilter implements MetadataFilter<List<String>> {
    
    @Override
    public List<String> filter(List<String> metadata, Set<String> blockedVersions) {
        return metadata.stream()
            .filter(v -> !blockedVersions.contains(v))
            .collect(Collectors.toList());
    }
    
    @Override
    public List<String> updateLatest(List<String> metadata, String newLatest) {
        // No "latest" tag in Go list
        return metadata;
    }
}
```

**Rewriter:**

```java
public class GoMetadataRewriter implements MetadataRewriter<List<String>> {
    
    @Override
    public byte[] rewrite(List<String> metadata) {
        return String.join("\n", metadata).getBytes(StandardCharsets.UTF_8);
    }
    
    @Override
    public String getContentType() {
        return "text/plain; charset=utf-8";
    }
}
```

**Performance:**
- Parse: < 1ms
- Filter: < 1ms
- Rewrite: < 1ms
- **Total: < 3ms**

---

## Performance Summary

| Package Type | Parse | Filter | Rewrite | Total (P99) |
|-------------|-------|--------|---------|-------------|
| NPM (small) | 5-20ms | 1-5ms | 5-20ms | **10-50ms** |
| NPM (large) | 20-100ms | 1-5ms | 20-100ms | **40-200ms** |
| PyPI | 5-15ms | 1-5ms | 5-15ms | **10-35ms** |
| Maven | 1-5ms | < 1ms | 1-5ms | **2-10ms** |
| Gradle | 1-5ms | < 1ms | 1-5ms | **2-10ms** |
| Composer | 5-20ms | 1-5ms | 5-20ms | **10-50ms** |
| Go | < 1ms | < 1ms | < 1ms | **< 3ms** |

**Conclusion:** All parsers meet the < 50ms target for typical packages. Large NPM packages may exceed this, but can be optimized with streaming parsers if needed.

---

## Optimization Strategies

### 1. Streaming Parser for Large NPM Packages

For packages with 1000+ versions (> 1 MB metadata):

```java
public class StreamingNpmMetadataFilter {
    
    public byte[] filterLarge(InputStream input, Set<String> blockedVersions) throws IOException {
        JsonFactory factory = new JsonFactory();
        JsonParser parser = factory.createParser(input);
        
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        JsonGenerator generator = factory.createGenerator(output);
        
        String currentField = null;
        boolean inVersions = false;
        String currentVersion = null;
        
        while (parser.nextToken() != null) {
            JsonToken token = parser.getCurrentToken();
            
            if (token == JsonToken.FIELD_NAME) {
                currentField = parser.getCurrentName();
                if ("versions".equals(currentField)) {
                    inVersions = true;
                }
            }
            
            if (inVersions && token == JsonToken.FIELD_NAME) {
                currentVersion = parser.getCurrentName();
                if (blockedVersions.contains(currentVersion)) {
                    // Skip this version object
                    parser.nextToken();
                    parser.skipChildren();
                    continue;
                }
            }
            
            // Copy token to output
            copyCurrentEvent(parser, generator);
        }
        
        generator.close();
        return output.toByteArray();
    }
}
```

### 2. Parallel Version Checks

Check all versions against cooldown database in parallel:

```java
CompletableFuture.allOf(
    versions.stream()
        .map(v -> checkBlocked(repo, pkg, v))
        .toArray(CompletableFuture[]::new)
);
```

### 3. Metadata Cache

Cache filtered metadata for 5-15 minutes to avoid repeated parsing:

```java
String cacheKey = String.format("metadata:%s:%s:filtered", repo, pkg);
byte[] cached = this.cache.get(cacheKey);
if (cached != null) {
    return CompletableFuture.completedFuture(cached);
}
```

---

## Next Steps

1. Implement parsers for each package type
2. Add unit tests for parsing, filtering, rewriting
3. Add performance benchmarks
4. Integrate with proxy slices
5. Test with real package manager clients

