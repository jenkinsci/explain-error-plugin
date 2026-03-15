.PHONY: help build test verify clean package run debug lint

# Default target
.DEFAULT_GOAL := help

help: ## Show this help message
	@grep -E '^[a-zA-Z_-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "\033[36m%-15s\033[0m %s\n", $$1, $$2}'

build: ## Compile the plugin (skip tests)
	mvn compile

test: ## Run all unit tests
	mvn test

verify: ## Compile, test, and verify (full CI check)
	mvn verify

clean: ## Remove build artifacts
	mvn clean

package: ## Build the .hpi plugin file (skip tests)
	mvn package -DskipTests

package-full: ## Build the .hpi plugin file (with tests)
	mvn package

run: ## Start Jenkins locally with the plugin (http://localhost:8080/jenkins)
	mvn hpi:run

debug: ## Start Jenkins in debug mode (port 8000)
	mvnDebug hpi:run

lint: ## Run Checkstyle (report only) and SpotBugs static analysis
	mvn checkstyle:checkstyle spotbugs:check

reinstall: clean package ## Clean, rebuild, and install .hpi locally
	mvn install -DskipTests
