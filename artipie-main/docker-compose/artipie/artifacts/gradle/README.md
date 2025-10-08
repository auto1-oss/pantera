# Gradle Sample Project for Artipie

This is a sample Gradle project demonstrating how to:
1. **Download dependencies** from Artipie `gradle_group` repository
2. **Publish artifacts** to Artipie `gradle` repository

## Project Structure

```
gradle-sample/
├── build.gradle              # Build configuration
├── settings.gradle           # Project settings
├── gradle/
│   └── wrapper/
│       └── gradle-wrapper.properties
├── src/
│   ├── main/java/com/example/
│   │   └── HelloWorld.java   # Main application
│   └── test/java/com/example/
│       └── HelloWorldTest.java # Unit tests
└── README.md
```

## Dependencies

The project uses the following dependencies from `gradle_group`:
- **Guava** 32.1.3-jre - Google's core libraries
- **Apache Commons Lang3** 3.14.0 - Utility functions
- **JUnit** 4.13.2 - Testing framework

## Prerequisites

1. **Artipie server running** on `http://localhost:8081`
2. **Gradle repositories configured:**
   - `gradle_group` - for downloading dependencies
   - `gradle` - for publishing artifacts
3. **Credentials:** username=`ayd`, password=`ayd`

## Build Commands

### 1. Download Dependencies
```bash
./gradlew dependencies
```

This will download all dependencies from `gradle_group` repository.

### 2. Build the Project
```bash
./gradlew build
```

This will:
- Compile the source code
- Run unit tests
- Create JAR files (main, sources, javadoc)

### 3. Run the Application
```bash
./gradlew run
```

Or run the JAR directly:
```bash
java -jar build/libs/gradle-sample-1.0.0.jar
```

### 4. Run Tests
```bash
./gradlew test
```

### 5. Publish to Artipie
```bash
./gradlew publishMavenJavaPublicationToArtipieRepository
```

Or publish all:
```bash
./gradlew publish
```

This will publish the following artifacts to `gradle` repository:
- `gradle-sample-1.0.0.jar` - Main JAR
- `gradle-sample-1.0.0-sources.jar` - Sources JAR
- `gradle-sample-1.0.0-javadoc.jar` - Javadoc JAR
- `gradle-sample-1.0.0.pom` - Maven POM file

### 6. Show Configured Repositories
```bash
./gradlew showRepos
```

## Using Docker

If you want to run Gradle in a Docker container:

```bash
docker run --rm -it \
  --network artipie-main_default \
  -v $(pwd):/project \
  -w /project \
  gradle:8.5-jdk11 \
  gradle build publish
```

## Verification

After publishing, you can verify the artifact in Artipie:

```bash
# List artifacts in gradle repository
curl -u ayd:ayd http://localhost:8081/gradle/com/example/gradle-sample/

# Download the published JAR
curl -u ayd:ayd -O http://localhost:8081/gradle/com/example/gradle-sample/1.0.0/gradle-sample-1.0.0.jar
```

## Configuration Details

### Download Repository (gradle_group)
```gradle
repositories {
    maven {
        url = 'http://localhost:8081/gradle_group'
        allowInsecureProtocol = true
        credentials {
            username = 'ayd'
            password = 'ayd'
        }
    }
}
```

### Publish Repository (gradle)
```gradle
publishing {
    repositories {
        maven {
            name = 'artipie'
            url = 'http://localhost:8081/gradle'
            allowInsecureProtocol = true
            credentials {
                username = 'ayd'
                password = 'ayd'
            }
        }
    }
}
```

## Troubleshooting

### Issue: Dependencies not downloading
- Verify Artipie server is running
- Check `gradle_proxy` is configured and working
- Verify credentials are correct

### Issue: Publish fails
- Ensure `gradle` repository exists and is writable
- Check user `ayd` has publish permissions
- Verify network connectivity to Artipie

### Issue: Authentication errors
- Update credentials in `build.gradle`
- Or set environment variables:
  ```bash
  export ARTIPIE_USERNAME=ayd
  export ARTIPIE_PASSWORD=ayd
  ```
  
  Then update `build.gradle`:
  ```gradle
  credentials {
      username = System.getenv('ARTIPIE_USERNAME')
      password = System.getenv('ARTIPIE_PASSWORD')
  }
  ```

## Next Steps

1. Modify `group` and `version` in `build.gradle`
2. Add your own source code
3. Configure additional dependencies
4. Publish to your Artipie instance
5. Use the published artifact in other projects

## License

Apache License 2.0
