#!/bin/bash
# Bump Pantera version in all locations using Maven Versions Plugin

set -e

if [ -z "$1" ]; then
    echo "Usage: ./bump-version.sh <new-version>"
    echo "Example: ./bump-version.sh 1.1.0"
    echo "         ./bump-version.sh 2.0.0-RC1"
    exit 1
fi

NEW_VERSION="$1"
OLD_VERSION=$(mvn help:evaluate -Dexpression=project.version -q -DforceStdout 2>/dev/null || grep -m 1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')

echo "=== Pantera Version Bump (Multi-Module) ==="
echo ""
echo "Current version: $OLD_VERSION"
echo "New version:     $NEW_VERSION"
echo ""
echo "This will update ALL 33 Maven modules using Maven Versions Plugin"
echo ""
read -p "Continue? (y/n) " -n 1 -r
echo ""
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "Aborted."
    exit 1
fi

# 1. Update all Maven modules using versions plugin
echo "1. Updating all Maven modules (this may take a moment)..."
mvn versions:set -DnewVersion=$NEW_VERSION -DgenerateBackupPoms=false -q
if [ $? -ne 0 ]; then
    echo "❌ Maven versions:set failed!"
    exit 1
fi

# 1b. Manually update build-tools (standalone module without parent)
echo "   Updating build-tools (standalone module)..."
if [[ "$OSTYPE" == "darwin"* ]]; then
    sed -i '' "s|<version>$OLD_VERSION</version>|<version>$NEW_VERSION</version>|" build-tools/pom.xml
else
    sed -i "s|<version>$OLD_VERSION</version>|<version>$NEW_VERSION</version>|" build-tools/pom.xml
fi

echo "   ✅ Updated all Maven modules"

# 2. Update docker-compose image tag
echo "2. Updating docker-compose.yaml (image tag)..."
if [[ "$OSTYPE" == "darwin"* ]]; then
    sed -i '' "s|auto1-pantera:$OLD_VERSION|auto1-pantera:$NEW_VERSION|" pantera-main/docker-compose/docker-compose.yaml
else
    sed -i "s|auto1-pantera:$OLD_VERSION|auto1-pantera:$NEW_VERSION|" pantera-main/docker-compose/docker-compose.yaml
fi

# 3. Update docker-compose environment variable (now in .env file)
echo "3. Updating .env and .env.example (PANTERA_VERSION)..."
if [[ "$OSTYPE" == "darwin"* ]]; then
    sed -i '' "s|PANTERA_VERSION=$OLD_VERSION|PANTERA_VERSION=$NEW_VERSION|" pantera-main/docker-compose/.env
    sed -i '' "s|PANTERA_VERSION=$OLD_VERSION|PANTERA_VERSION=$NEW_VERSION|" pantera-main/docker-compose/.env.example
else
    sed -i "s|PANTERA_VERSION=$OLD_VERSION|PANTERA_VERSION=$NEW_VERSION|" pantera-main/docker-compose/.env
    sed -i "s|PANTERA_VERSION=$OLD_VERSION|PANTERA_VERSION=$NEW_VERSION|" pantera-main/docker-compose/.env.example
fi

echo "   ✅ Updated .env and .env.example"

# 4. Update Dockerfile PANTERA_VERSION
echo "4. Updating Dockerfile (PANTERA_VERSION)..."
if [[ "$OSTYPE" == "darwin"* ]]; then
    sed -i '' "s|ENV PANTERA_VERSION=$OLD_VERSION|ENV PANTERA_VERSION=$NEW_VERSION|" pantera-main/Dockerfile
else
    sed -i "s|ENV PANTERA_VERSION=$OLD_VERSION|ENV PANTERA_VERSION=$NEW_VERSION|" pantera-main/Dockerfile
fi
echo "   ✅ Updated Dockerfile"

echo ""
echo "✅ Version bumped successfully!"
echo ""
echo "Changes made:"
echo "  - All 33 Maven modules: $OLD_VERSION → $NEW_VERSION"
echo "  - docker-compose.yaml: image tag updated"
echo "  - .env: PANTERA_VERSION updated"
echo "  - .env.example: PANTERA_VERSION updated"
echo "  - Dockerfile: PANTERA_VERSION updated"
echo ""
echo "Verification:"
echo "  - Parent version:  $(grep -m 1 '<version>' pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')"
echo "  - Build-tools:     $(grep -m 1 '<version>' build-tools/pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')"
echo "  - Pantera-main:    $(grep -m 1 '<version>' pantera-main/pom.xml | sed 's/.*<version>\(.*\)<\/version>.*/\1/')"
echo ""
echo "Next steps:"
echo "  1. Review changes:  git diff pom.xml */pom.xml"
echo "  2. Test build:      mvn clean install -DskipTests"
echo "  3. Build Docker:    docker build -t pantera/pantera:$NEW_VERSION ."
echo "  4. Test locally:    cd pantera-main/docker-compose && docker-compose up -d"
echo "  5. Verify version:  docker logs pantera 2>&1 | jq '.service.version' | head -1"
echo "  6. Commit:          git commit -am 'Bump version to $NEW_VERSION'"
echo "  7. Tag:             git tag v$NEW_VERSION"
echo "  8. Push:            git push && git push --tags"
echo ""
echo "To revert changes:"
echo "  git checkout pom.xml */pom.xml pantera-main/docker-compose/docker-compose.yaml pantera-main/docker-compose/.env.example pantera-main/Dockerfile"
echo "  # Note: .env is gitignored - manually update PANTERA_VERSION if needed"
echo ""
