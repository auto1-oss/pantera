# Quick Start Guide

## 🚀 Get Started in 3 Steps

### Step 1: Ensure Artipie is Running
```bash
cd ../..
docker-compose up -d
```

### Step 2: Build and Test
```bash
cd artifacts/gradle-sample

# Option A: Using local Gradle wrapper
./gradlew build

# Option B: Using Docker
./docker-demo.sh
```

### Step 3: Publish to Artipie
```bash
# Option A: Using local Gradle
./gradlew publish

# Option B: Using the demo script
./run-demo.sh
```

## 📦 What This Does

1. **Downloads dependencies** from `gradle_group` repository:
   - Guava 32.1.3-jre
   - Apache Commons Lang3 3.14.0
   - JUnit 4.13.2

2. **Builds the project**:
   - Compiles Java source code
   - Runs unit tests
   - Creates JAR files (main, sources, javadoc)

3. **Publishes artifacts** to `gradle` repository:
   - `gradle-sample-1.0.0.jar`
   - `gradle-sample-1.0.0-sources.jar`
   - `gradle-sample-1.0.0-javadoc.jar`
   - `gradle-sample-1.0.0.pom`

## 🔍 Verify Publication

### Check in Browser
Open: http://localhost:8081/gradle/com/example/gradle-sample/1.0.0/

### Check via curl
```bash
curl -u ayd:ayd http://localhost:8081/gradle/com/example/gradle-sample/1.0.0/
```

### Download Published Artifact
```bash
curl -u ayd:ayd -O http://localhost:8081/gradle/com/example/gradle-sample/1.0.0/gradle-sample-1.0.0.jar
```

## 🔧 Use in Another Project

Add to your `build.gradle`:

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

dependencies {
    implementation 'com.example:gradle-sample:1.0.0'
}
```

## 📋 Available Commands

| Command | Description |
|---------|-------------|
| `./gradlew build` | Build the project |
| `./gradlew test` | Run tests |
| `./gradlew publish` | Publish to Artipie |
| `./gradlew dependencies` | Show dependency tree |
| `./gradlew showRepos` | Show configured repos |
| `./run-demo.sh` | Run full demo |
| `./docker-demo.sh` | Run demo in Docker |

## 🐛 Troubleshooting

**Problem:** Dependencies not downloading  
**Solution:** Check `gradle_proxy` is configured and Artipie is running

**Problem:** Publish fails  
**Solution:** Verify `gradle` repository exists and user has write permissions

**Problem:** Authentication errors  
**Solution:** Check credentials in `build.gradle` (username: `ayd`, password: `ayd`)

## 📚 Learn More

See [README.md](README.md) for detailed documentation.
