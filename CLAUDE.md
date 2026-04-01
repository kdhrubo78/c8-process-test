# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Camunda 8 process test project — a Spring Boot 4.0.5 web application (Java 17, Camunda 8.8.21) demonstrating BPMN process testing with DMN decision tables. Uses Testcontainers to spin up a Camunda runtime in Docker for integration tests.

## Build & Test Commands

This project uses the Maven wrapper (`./mvnw`). No local Maven installation required. Docker must be running for tests (Testcontainers).

```bash
# Build (skip tests)
./mvnw clean package -DskipTests

# Run all tests
./mvnw test

# Run a single test class
./mvnw test -Dtest=LoanApprovalProcessTest

# Run a single test method
./mvnw test -Dtest=LoanApprovalProcessTest#shouldApproveLoan_highProfitability

# Run the app
./mvnw spring-boot:run
```

## Architecture

- **Main app**: `C8ProcessTestApplication` — Spring Boot entry point with `@Deployment` annotation that auto-deploys all `.bpmn` and `.dmn` resources
- **BPMN process**: `src/main/resources/bpmn/loan-approval.bpmn` — Business Rule Task calls DMN, XOR gateway routes to approved/rejected end events based on `loan_decision` variable
- **DMN decision**: `src/main/resources/dmn/loan-approval.dmn` — DRD with 3 decisions: Profitability (id: `probability`), Risk (id: `risk`), and Loan Approval (id: `loan_approval`) which combines both
- **Tests**: `LoanApprovalProcessTest` — 6 end-to-end process scenarios (3 approval, 3 rejection); `DmnDecisionTest` — 17 isolated DMN decision tests (5 profitability + 6 risk + 6 approval) using nested classes. Both use `@CamundaSpringProcessTest` which manages the Testcontainers lifecycle, deploys resources, and resets state between tests

## Key Gotchas

- **DMN DRD variable naming**: In Camunda 8, sub-decision results are accessible in parent decisions via the **decision id** (not the output column name). The `loan_approval` decision references `probability` (decision id), not `profitability` (output name).
- **DMN boundary overlaps**: The Profitability decision has overlapping rules at value 5.0 (`[2..5]` inclusive and `>=5.0`). Avoid boundary values in tests to prevent UNIQUE hit policy violations.
- **Test dependency**: `camunda-process-test-spring-4` excludes `camunda-spring-boot-starter` (Boot 3.x) to avoid conflicts with the Boot 4.x starter.
