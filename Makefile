.PHONY: help build test verify clean package package-full run debug lint reinstall

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
	VSCODE_JDWP_ADAPTER_ENDPOINTS="" mvn hpi:run

debug: ## Start Jenkins with remote debugger on port 8000 (attach anytime, does not block)
	VSCODE_JDWP_ADAPTER_ENDPOINTS="" mvn hpi:run -Ddebug="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=localhost:8000"

lint: ## Run Checkstyle and SpotBugs static analysis (report only, non-blocking)
	mvn checkstyle:checkstyle spotbugs:spotbugs

reinstall: ## Clean, rebuild, and install .hpi locally
	mvn clean install -DskipTests
