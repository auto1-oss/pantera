.PHONY: help build test publish clean demo docker-demo verify

help:
	@echo "Gradle Sample - Pantera Demo"
	@echo ""
	@echo "Available targets:"
	@echo "  make build        - Build the project"
	@echo "  make test         - Run tests"
	@echo "  make publish      - Publish to Pantera"
	@echo "  make clean        - Clean build artifacts"
	@echo "  make demo         - Run full demo"
	@echo "  make docker-demo  - Run demo in Docker"
	@echo "  make verify       - Verify published artifacts"
	@echo "  make all          - Build, test, and publish"

build:
	./gradlew build

test:
	./gradlew test

publish:
	./gradlew publish

clean:
	./gradlew clean

demo:
	./run-demo.sh

docker-demo:
	./docker-demo.sh

verify:
	@echo "Checking published artifacts..."
	@curl -s -u ayd:ayd http://localhost:8081/gradle/com/example/gradle-sample/1.0.0/ | grep -o 'gradle-sample-[^"]*' | sort -u || echo "No artifacts found"

all: clean build test publish verify
	@echo "✓ All tasks completed successfully!"
