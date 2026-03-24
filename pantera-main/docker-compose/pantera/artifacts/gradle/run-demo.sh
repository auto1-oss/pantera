#!/bin/bash
# Gradle Sample Demo Script for Pantera

set -e

echo "======================================"
echo "Gradle Sample - Pantera Demo"
echo "======================================"
echo ""

# Check if Pantera is running
echo "1. Checking Pantera server..."
if curl -s -f -u ayd:ayd http://localhost:8081/gradle_group > /dev/null 2>&1; then
    echo "   ✓ Pantera server is running"
else
    echo "   ✗ Pantera server is not accessible"
    echo "   Please start Pantera first: cd ../.. && docker-compose up -d"
    exit 1
fi
echo ""

# Show configured repositories
echo "2. Configured repositories:"
./gradlew -q showRepos
echo ""

# Download dependencies
echo "3. Downloading dependencies from gradle_group..."
./gradlew dependencies --console=plain | grep -A 20 "compileClasspath"
echo ""

# Build the project
echo "4. Building the project..."
./gradlew clean build --console=plain
echo "   ✓ Build successful"
echo ""

# Run tests
echo "5. Running tests..."
./gradlew test --console=plain
echo "   ✓ Tests passed"
echo ""

# Show generated artifacts
echo "6. Generated artifacts:"
ls -lh build/libs/
echo ""

# Publish to Pantera
echo "7. Publishing to Pantera gradle repository..."
./gradlew publish --console=plain
echo "   ✓ Published successfully"
echo ""

# Verify publication
echo "8. Verifying published artifacts in Pantera:"
echo "   Checking: http://localhost:8081/gradle/com/example/gradle-sample/1.0.0/"
curl -s -u ayd:ayd http://localhost:8081/gradle/com/example/gradle-sample/1.0.0/ | grep -o 'gradle-sample-[^"]*' | sort -u
echo ""

echo "======================================"
echo "Demo completed successfully!"
echo "======================================"
echo ""
echo "Published artifacts are available at:"
echo "  http://localhost:8081/gradle/com/example/gradle-sample/1.0.0/"
echo ""
echo "To use in another project, add to build.gradle:"
echo "  repositories {"
echo "    maven {"
echo "      url = 'http://localhost:8081/gradle_group'"
echo "      credentials {"
echo "        username = 'ayd'"
echo "        password = 'ayd'"
echo "      }"
echo "    }"
echo "  }"
echo ""
echo "  dependencies {"
echo "    implementation 'com.example:gradle-sample:1.0.0'"
echo "  }"
