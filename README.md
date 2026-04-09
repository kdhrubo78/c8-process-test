# Camunda 8 Process Test - Loan Approval

A Spring Boot application demonstrating how to test Camunda 8 BPMN processes with DMN decision tables using the [Camunda Process Test](https://docs.camunda.io/docs/apis-tools/testing/getting-started/) library with Testcontainers.

## Prerequisites

- **Java 17** or higher
- **Docker** running locally (required by Testcontainers to spin up the Camunda runtime)
- No local Camunda or Zeebe installation needed — the test framework handles everything

## Tech Stack

| Component | Version |
|---|---|
| Spring Boot | 4.0.5 |
| Camunda 8 | 8.8.21 |
| JUnit 5 | (managed by Spring Boot) |
| Testcontainers | (managed by Camunda test library) |

## Project Structure

```
src/
  main/
    java/com/camunda/c8processtest/
      C8ProcessTestApplication.java         # Spring Boot app with @Deployment annotation
    resources/
      bpmn/
        loan-approval.bpmn                  # BPMN process definition
      dmn/
        loan-approval.dmn                   # DMN decision table (DRD with 3 decisions)
      static/
        demo.html                           # Reveal.js presentation deck
        *.svg                               # Diagram assets (process, DRD, architecture)
      application.properties
      application-demo.properties           # Demo profile (disables Camunda auto-config)
  test/
    java/com/camunda/c8processtest/
      C8ProcessTestApplicationTests.java    # Application context load test (1 scenario)
      LoanApprovalProcessTest.java          # End-to-end process tests (8 scenarios: 6 real + 2 mocked DMN)
      DmnDecisionTest.java                  # Isolated DMN decision tests (17 scenarios)
      TestcontainersConfiguration.java      # Test configuration for Testcontainers
      TestC8ProcessTestApplication.java     # Test application entry point
```

## The BPMN Process

The `loan-approval` process evaluates a loan application using a DMN decision and routes to approval or rejection:

```
                                         +--> [End: Loan Approved]
                                        /  (loan_decision = true)
[Start Event] --> [Business Rule Task] --> [XOR Gateway]
 Loan Request      Evaluate Loan            Loan Approved?
 Received          Application               \
                   (calls DMN)                +--> [End: Loan Rejected]
                                                (loan_decision = false)
```

- **Start Event** — triggers the process with input variables: `interest_rate`, `requested_loan`, `monthly_income`.
- **Business Rule Task** ("Evaluate Loan Application") — calls the `loan_approval` DMN decision via `zeebe:calledDecision`. The DMN result (boolean) is stored in `loan_decision`.
- **Exclusive Gateway** ("Loan Approved?") — routes based on the FEEL expression `=loan_decision = true` or `=loan_decision = false`.
- **End Events** — "Loan Approved" or "Loan Rejected" depending on the decision outcome.

## The DMN Decision (Decision Requirements Diagram)

The `loan-approval.dmn` contains a DRD with three interconnected decision tables:

```
[Interest Rate]   [Requested Loan]   [Monthly Income]
       \               |    \               /
        v              v     v             v
    +------------------+    +-------------+
    |  Profitability   |    |    Risk     |
    |  (probability)   |    |   (risk)    |
    +--------+---------+    +------+------+
             \                    /
              v                  v
         +------------------------+
         |    Loan Approval       |
         |   (loan_approval)      |
         +------------------------+
                   |
                   v
              true / false
```

### Decision 1: Profitability (id: `probability`)

Inputs: `interest_rate`, `requested_loan`

| Interest Rate | Requested Loan | Profitability |
|---|---|---|
| < 2.0 | (any) | low |
| >= 5.0 | (any) | high |
| [2..5] | < 10,000 | low |
| [2..5] | [10,000..1,000,000] | medium |
| [2..5] | > 1,000,000 | high |

### Decision 2: Risk (id: `risk`)

Inputs: `requested_loan`, `monthly_income`

| Requested Loan | Monthly Income | Risk |
|---|---|---|
| < 10,000 | (any) | low |
| [10,000..100,000] | < 5,000 | medium |
| [10,000..100,000] | >= 5,000 | low |
| > 100,000 | < 5,000 | high |
| > 100,000 | [5,000..10,000] | medium |
| > 100,000 | >= 10,000 | low |

### Decision 3: Loan Approval (id: `loan_approval`)

Inputs: `probability` (from Profitability decision), `risk` (from Risk decision)

| Profitability | Risk | Approved? |
|---|---|---|
| low | not low | false |
| low | low | true |
| medium | high | false |
| medium | not high | true |
| high | (any) | true |

**Important DRD note:** In Camunda 8, sub-decision results are accessible in the parent decision using the **decision id** as the variable name (not the output column name). This is why the Loan Approval decision references `probability` (the decision id), not `profitability` (the output column name).

## How the Tests Work

Both test classes use the same annotations:

- `@SpringBootTest` — boots the full Spring application context.
- `@CamundaSpringProcessTest` — provided by the Camunda test library. This annotation:
  - Starts a **Camunda runtime inside a Docker container** via Testcontainers before each test.
  - Automatically configures a `CamundaClient` bean connected to the containerized runtime.
  - Deploys all BPMN and DMN resources declared via the `@Deployment` annotation on the application class.
  - Resets the engine state between tests for isolation.
  - Generates a **process coverage report** after tests complete.

### Process Tests (`LoanApprovalProcessTest`)

End-to-end tests that start a process instance and assert the final outcome using the real DMN decision engine, plus additional tests that mock the DMN for isolation testing. Covers 8 scenarios — 6 using real DMN evaluation and 2 using mocked decisions:

**Real DMN Evaluation Tests (6 scenarios):**

| Test | Interest Rate | Loan | Income | Profitability | Risk | Result |
|---|---|---|---|---|---|---|
| `shouldApproveLoan_highProfitability` | 6.0 | 50,000 | 3,000 | high | medium | **approved** |
| `shouldApproveLoan_mediumProfitabilityLowRisk` | 3.5 | 50,000 | 8,000 | medium | low | **approved** |
| `shouldApproveLoan_lowProfitabilityLowRisk` | 1.5 | 5,000 | 4,000 | low | low | **approved** |
| `shouldRejectLoan_lowProfitabilityHighRisk` | 1.0 | 200,000 | 3,000 | low | high | **rejected** |
| `shouldRejectLoan_mediumProfitabilityHighRisk` | 3.0 | 200,000 | 2,000 | medium | high | **rejected** |
| `shouldRejectLoan_lowProfitabilityMediumRisk` | 1.5 | 50,000 | 3,000 | low | medium | **rejected** |

**Mocked DMN Tests (2 scenarios):**

| Test | DMN Mock | Result |
|---|---|---|
| `shouldComplete_whenDmnMockedApproved` | Loan approved = true | **approved** |
| `shouldComplete_whenDmnMockedRejected` | Loan approved = false | **rejected** |

### DMN Decision Tests (`DmnDecisionTest`)

Tests each DMN decision in isolation — without running the BPMN process — using `newEvaluateDecisionCommand()`. Organized as 3 nested classes:

| Nested Class | Decision ID | Tests | What it validates |
|---|---|---|---|
| `ProfitabilityDecision` | `probability` | 5 | All interest rate / loan amount combinations |
| `RiskDecision` | `risk` | 6 | All loan amount / income combinations |
| `LoanApprovalDecision` | `loan_approval` | 6 | Full DRD evaluation (all 3 decisions), verifies matched rule index and decision output |

### Test Flow

**Process tests** follow this pattern:

1. **Create a process instance** — starts the `loan-approval` process with specific input variables using `client.newCreateInstanceCommand()...send().join()`, which waits for completion and returns a `ProcessInstanceEvent` with all final variables.
2. **Assert the decision outcome** — checks that `loan_decision` equals `true` (approved) or `false` (rejected) based on the DMN evaluation using `CamundaAssert.assertThat()`.
3. **For mocked tests** — use `processTestContext.mockDmnDecision()` to replace the real DMN with a stub before creating the instance.

**DMN tests** follow this pattern:

1. **Evaluate a decision directly** — calls `client.newEvaluateDecisionCommand()` with the decision ID and input variables.
2. **Assert the output and matched rule** — checks the decision output value and verifies the correct rule index was matched.

Since the process uses a Business Rule Task (not a Service Task), the DMN is evaluated automatically by the Camunda engine — no manual job activation is needed.

## Running the Tests

### Run all tests

```bash
./mvnw test
```

### Run the process tests

```bash
./mvnw test -Dtest=LoanApprovalProcessTest
```

### Run the DMN decision tests

```bash
./mvnw test -Dtest=DmnDecisionTest
```

### Run the application context load test

```bash
./mvnw test -Dtest=C8ProcessTestApplicationTests
```

### Run a specific test method

```bash
./mvnw test -Dtest=LoanApprovalProcessTest#shouldApproveLoan_highProfitability
```

The first run will be slower as Docker pulls the Camunda container image. Subsequent runs reuse the cached image.

## Process Coverage Report

After tests run, a coverage report is generated at:

```
target/coverage-report/report.html
```

Open this file in a browser to see which BPMN process elements were exercised by the `LoanApprovalProcessTest` tests. The report is generated by the Camunda Process Test framework as part of the test execution lifecycle.

## Code Coverage with JaCoCo

This project uses **JaCoCo** (Java Code Coverage) to measure test coverage of the application code. JaCoCo is automatically invoked during test execution and generates coverage reports.

### Running Tests with Coverage

Simply run the standard test command — JaCoCo is attached automatically:

```bash
./mvnw test
```

JaCoCo will:
1. **Attach an agent** to the JVM before tests execute
2. **Collect coverage data** as tests run
3. **Generate HTML/XML reports** after tests complete

### Viewing the Coverage Report

After running tests, open the JaCoCo coverage report at:

```
target/site/jacoco/index.html
```

This report shows:
- **Line Coverage** — percentage of executable lines covered by tests
- **Branch Coverage** — percentage of conditional branches covered
- **Complexity** — cyclomatic complexity metrics
- **Class-level breakdown** — coverage details for each Java class

### Coverage Thresholds

This project enforces **minimum coverage requirements** via JaCoCo's `check` goal:

| Metric | Minimum Required |
|---|---|
| Line Coverage | 80% |
| Branch Coverage | 70% |

If tests don't meet these thresholds, the build will **fail**. This ensures code quality and comprehensive test coverage.

To skip coverage enforcement (not recommended):

```bash
./mvnw test -Djacoco.skip=true
```

Or to generate reports without enforcement:

```bash
./mvnw jacoco:report
```

### Understanding Coverage Results

When viewing the HTML report:
- **Green** — code is covered by tests
- **Red** — code is not covered by tests (dead code or missing tests)
- **Yellow** — code is partially covered (only some branches executed)

Use these insights to:
- Identify untested code paths
- Improve test scenarios for better coverage
- Detect dead code that can be removed

### JaCoCo Integration

The JaCoCo plugin is configured in `pom.xml` with three phases:

1. **prepare-agent** — Attaches the JaCoCo Java agent before tests run
2. **report** — Generates HTML and XML reports after the test phase
3. **check** — Validates that coverage meets the configured thresholds

To customize coverage thresholds, edit the `<rules>` section in `pom.xml` (around line 144).

## Key Dependencies

### Runtime

- **`camunda-spring-boot-4-starter`** — Camunda SDK for Spring Boot 4.x. Provides the `CamundaClient`, `@Deployment` annotation for auto-deploying BPMN/DMN resources, and job worker infrastructure.

### Test

- **`camunda-process-test-spring-4`** — Camunda's testing library for Spring Boot 4.x. Provides `@CamundaSpringProcessTest`, `CamundaAssert`, and the Testcontainers-based Camunda runtime. The `camunda-spring-boot-starter` (Boot 3.x variant) is excluded to avoid conflicts with the Boot 4.x starter.

## Adding More Tests

To test additional processes:

1. Add `.bpmn` files to `src/main/resources/bpmn/` and `.dmn` files to `src/main/resources/dmn/` — they will be auto-deployed via the `@Deployment` annotation.
2. Write a test class annotated with `@SpringBootTest` and `@CamundaSpringProcessTest`.
3. Inject `CamundaClient` and use it to create instances, activate jobs, and assert outcomes.

Common assertions available via `CamundaAssert.assertThat(processInstance)`:

| Assertion | Description |
|---|---|
| `.isActive()` | Process instance is running |
| `.isCompleted()` | Process instance reached an end event |
| `.hasActiveElements("Task Name")` | Specific element(s) are currently active |
| `.hasCompletedElements("Task Name")` | Specific element(s) have been completed |
| `.hasVariable("variableName", value)` | Assert a process variable has a specific value |

You can access process variables directly from the returned `ProcessInstanceEvent` and assert their values using `CamundaAssert.assertThat()` or standard assertion libraries.

## DMN Design Notes

- The Profitability decision has overlapping rules at boundary value 5.0 (`[2..5]` inclusive and `>=5.0`). Tests use values clearly inside one range (e.g., 6.0) to avoid UNIQUE hit policy violations.
- The `loan_approval` decision's input expression uses `probability` (the decision id) to access the Profitability sub-decision output — this is how Camunda 8's DRD evaluation makes required decision results available.

## Test Isolation & Mocking

The test suite demonstrates three levels of testing:

1. **Application Context Load Test** (`C8ProcessTestApplicationTests`) — Verifies that the Spring Boot application context loads successfully with the Camunda client. This is a simple smoke test that ensures all beans are properly configured.

2. **End-to-End Process Tests** (`LoanApprovalProcessTest`) — 8 tests that cover the complete BPMN process with the real DMN decision engine (6 tests) and with mocked DMN decisions (2 tests). These tests verify the entire workflow, from process start to completion, with actual business rules or stubbed rules depending on the test scenario.

3. **Isolated DMN Decision Tests** (`DmnDecisionTest`) — 17 tests that evaluate each DMN decision independently without running the BPMN process. This approach allows testing decision logic in isolation and provides granular coverage of all decision table rules.

This multi-layered testing approach ensures comprehensive coverage: smoke tests verify infrastructure, end-to-end tests verify workflows with real and mocked rules, and isolated decision tests verify rule logic in detail.

## Demo Slides

A reveal.js presentation deck is included and served as a static resource by the Spring Boot app. Start with the `demo` profile (disables Camunda auto-configuration so no cluster is needed):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=demo
```

Then open **http://localhost:8080/demo.html**

Navigate with arrow keys. Press `F` for fullscreen, `Esc` for slide overview.

## References

- [Camunda Process Testing - Getting Started](https://docs.camunda.io/docs/apis-tools/testing/getting-started/)
- [Camunda Process Testing - Assertions](https://docs.camunda.io/docs/apis-tools/testing/assertions/)
- [Camunda Spring Zeebe SDK](https://docs.camunda.io/docs/apis-tools/spring-zeebe-sdk/getting-started/)
- [Camunda DMN Engine](https://docs.camunda.io/docs/components/modeler/dmn/)
- [Business Rule Tasks](https://docs.camunda.io/docs/components/modeler/bpmn/business-rule-tasks/)
