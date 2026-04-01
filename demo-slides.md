# Camunda 8 DMN Deep Dive

---

<!-- ============================================================ -->
<!-- SECTION 1: DMN Rules Standardization                          -->
<!-- ============================================================ -->

## Part I
## DMN Rules Standardization

---

## The Problem

Business logic buried in code:

```java
if (interestRate >= 5.0) {
    profitability = "high";
} else if (interestRate >= 2.0) {
    if (requestedLoan < 10000) {
        profitability = "low";
    } else if (requestedLoan <= 1000000) {
        profitability = "medium";
    } else {
        profitability = "high";
    }
} else {
    profitability = "low";
}
// ... and more nested ifs for risk, then approval ...
```

- Hard to read, harder to change
- Business stakeholders can't review it
- Testing requires tracing every branch

---

## Complex Logic → Decision Tables

**Profitability**

| Interest Rate | Requested Loan | Result     |
|---------------|----------------|------------|
| < 2.0         | *(any)*        | **low**    |
| >= 5.0        | *(any)*        | **high**   |
| [2..5]        | < 10,000       | **low**    |
| [2..5]        | [10K..1M]      | **medium** |
| [2..5]        | > 1,000,000    | **high**   |

- Each row is an independent rule
- No nesting, no control flow
- Business analysts can read and validate this directly

---

## Readable and Maintainable

**Risk Assessment**

| Requested Loan    | Monthly Income | Risk       |
|-------------------|----------------|------------|
| < 10,000          | *(any)*        | **low**    |
| [10K..100K]       | < 5,000        | **medium** |
| [10K..100K]       | >= 5,000       | **low**    |
| > 100,000         | < 5,000        | **high**   |
| > 100,000         | [5K..10K]      | **medium** |
| > 100,000         | >= 10,000      | **low**    |

Adding a new rule = adding a row. No code changes. No redeployment.

---

## Chaining Decisions: Output → Input

DMN supports **Decision Requirements Diagrams (DRDs)** — decisions that feed into each other:

```
              ┌──────────────────────────────────┐
              │          INPUT DATA               │
              │                                   │
              │  Interest    Requested   Monthly   │
              │   Rate         Loan      Income   │
              └────┬───────────┬──────────┬───────┘
                   │           │          │
          ┌────────┘     ┌─────┴─────┐    └────────┐
          │              │           │             │
          ▼              ▼           ▼             ▼
   ┌──────────────────────┐   ┌──────────────────────┐
   │    Profitability      │   │        Risk           │
   │                       │   │                       │
   │  rate + loan → level  │   │  loan + income → level│
   └───────────┬───────────┘   └───────────┬───────────┘
               │                           │
               │     "high" / "medium"     │
               │        / "low"            │
               │                           │
               ▼                           ▼
        ┌──────────────────────────────────────┐
        │          LOAN APPROVAL                │
        │                                       │
        │   profitability + risk → approved?    │
        └──────────────────┬────────────────────┘
                           │
                           ▼
                     true / false
```

- 3 inputs → 2 intermediate decisions → 1 final answer
- Each decision is independently testable
- The engine evaluates the full graph automatically
- The engine evaluates the full graph automatically

---

## Chaining in Action

**Loan Approval** consumes outputs from Profitability and Risk:

| Profitability | Risk     | Approved? |
|---------------|----------|-----------|
| low           | not low  | **NO**    |
| low           | low      | **YES**   |
| medium        | high     | **NO**    |
| medium        | not high | **YES**   |
| high          | *(any)*  | **YES**   |

---

## Before / After

| Before (code)                   | After (DMN)                        |
|---------------------------------|------------------------------------|
| Logic scattered across services | Centralized in decision tables     |
| Developers own business rules   | Business analysts can own rules    |
| Changes require code + deploy   | Update DMN, redeploy resource only |
| Testing = tracing code paths    | Testing = input/output pairs       |
| Hard to audit                   | Visual, standardized, auditable    |

---

<!-- ============================================================ -->
<!-- SECTION 2: BPMN + DMN Orchestration Pattern                   -->
<!-- ============================================================ -->

## Part II
## BPMN + DMN Orchestration Pattern

---

## The Pattern

> Decision → conditional API call → next decision

```
                                         ┌──→ [Call Credit API] ──→ [Re-evaluate] ──→ ...
                                        /
[Start] → [DMN: Initial Screen] → [XOR Gateway]
                                        \
                                         └──→ [End: Auto-decided]
```

- DMN makes the **first decision** based on available data
- If more data is needed, the process **fetches it** via an API call
- A **second decision** runs with enriched data
- Data fetched only when needed — no unnecessary API calls

---

## Our Loan Approval Process

```
                                           ┌──→ [End: Approved]
                                          /
[Start] → [DMN: Evaluate Loan] → [XOR Gateway]
  │              │                        \
  │        calls loan_approval             └──→ [End: Rejected]
  │        DMN (DRD)
  │
  ▼
Inputs: interest_rate, requested_loan, monthly_income
```

- **BPMN** handles the process flow and routing
- **DMN** handles the decision logic (3-table DRD)
- Gateway reads `loan_decision` variable from DMN result
- FEEL expression: `=loan_decision = true`

---

## Why This Separation Matters

### BPMN owns
- **When** to call a decision
- **What happens** after the decision (routing, API calls, notifications)
- **Error handling** and retries
- **Process state** and audit trail

### DMN owns
- **How** the decision is made (rules)
- **What inputs** matter
- **What outputs** are produced
- **Business logic** that analysts can review

---

## Conditional Data Fetching

The key insight: **don't fetch everything upfront**

```
[Start] → [DMN: Pre-screen] → [Gateway: Need more data?]
                                    │
                              YES   │   NO
                              ▼     │   ▼
                     [Call External  │  [End: Decision made]
                      API]          │
                              ▼     │
                     [DMN: Final    │
                      Decision]     │
                              ▼     │
                     [End: Decision │
                      made]         │
```

- Pre-screen with cheap, local data first
- Only call external APIs when the pre-screen is inconclusive
- Reduces latency, cost, and external dependencies

---

## Extensibility

Adding a new step is straightforward:

| Change                    | Where               | Impact              |
|---------------------------|---------------------|---------------------|
| New business rule         | DMN table           | Add a row           |
| New decision factor       | DMN input column    | Add a column        |
| New API call              | BPMN service task   | Add a task          |
| New routing path          | BPMN gateway        | Add a sequence flow |
| New sub-decision          | DMN DRD             | Add a decision node |

Each change is isolated. DMN changes don't require BPMN changes and vice versa.

---

<!-- ============================================================ -->
<!-- SECTION 3: Testing & Validation Framework                     -->
<!-- ============================================================ -->

## Part III
## Testing & Validation Framework

---

## Testing Stack

| Layer              | Tool                           | Purpose                       |
|--------------------|--------------------------------|-------------------------------|
| Runtime            | Camunda 8 (Testcontainers)     | Real engine, no mocks         |
| Test framework     | `@CamundaSpringProcessTest`    | Lifecycle, deploy, reset      |
| Assertions         | `CamundaAssert`                | Process state checks          |
| Result validation  | `.withResult()` + AssertJ      | Variable/output verification  |
| Coverage           | Built-in coverage report       | BPMN element coverage         |

No mocks. No stubs. The test spins up a **real Camunda engine** in Docker via Testcontainers.

---

## How to Test Decision Tables

```java
@SpringBootTest
@CamundaSpringProcessTest
class LoanApprovalProcessTest {

    @Autowired
    private CamundaClient client;

    @Test
    void shouldApproveLoan_highProfitability() {
        ProcessInstanceResult result = client
            .newCreateInstanceCommand()
            .bpmnProcessId("loan-approval")
            .latestVersion()
            .variables(Map.of(
                "interest_rate", 6.0,
                "requested_loan", 50000,
                "monthly_income", 3000
            ))
            .withResult()
            .send().join();

        assertThat(result.getVariablesAsMap()
            .get("loan_decision")).isEqualTo(true);
    }
}
```

Pattern: **set inputs → run process → assert outputs**

---

## Testing Decisions Independently

No BPMN needed — evaluate each DMN decision directly:

```java
// Test Profitability decision in isolation
@Test
void midRate_mediumLoan_returnsMedium() {
    EvaluateDecisionResponse response = client
        .newEvaluateDecisionCommand()
        .decisionId("probability")
        .variables(Map.of(
            "interest_rate", 3.0,
            "requested_loan", 50000))
        .send().join();

    assertThat(response.getDecisionOutput())
        .isEqualTo("\"medium\"");
}

// Test Risk decision in isolation
@Test
void largeLoan_lowIncome_returnsHigh() {
    EvaluateDecisionResponse response = client
        .newEvaluateDecisionCommand()
        .decisionId("risk")
        .variables(Map.of(
            "requested_loan", 200000,
            "monthly_income", 3000))
        .send().join();

    assertThat(response.getDecisionOutput())
        .isEqualTo("\"high\"");
}
```

```bash
./mvnw test -Dtest=DmnDecisionTest    # 17 tests: 5 profitability + 6 risk + 6 approval
```

---

## Process Coverage Report

After tests run, a coverage report is generated automatically:

```
target/coverage-report/report.html
```

```
Process coverage: LoanApprovalProcessTest
========================
- loan-approval: 100%
```

- Shows which BPMN elements were exercised
- Identifies untested paths
- Helps ensure both **approval** and **rejection** flows are covered

---

## Mocking REST Outbound Connectors

When your BPMN uses a **REST outbound connector**, the connector runtime runs
inside a Docker container. To test the actual HTTP call, use **WireMock** as
the target endpoint.

### How it works

```
┌─────────────────┐          ┌──────────────────────────┐
│  Test (JVM)     │          │  Docker (Testcontainers)  │
│                 │          │                           │
│  WireMock       │◀─── HTTP ──── Connector Runtime     │
│  :9999          │          │     (connectors-bundle)   │
│                 │          │            ▲              │
│  Start process  │── gRPC ──│──▶  Zeebe Engine          │
│  Assert result  │          │     creates job for       │
│                 │          │     io.camunda:http-json:1 │
└─────────────────┘          └──────────────────────────┘
```

1. Test starts process instance
2. Zeebe creates a connector job (`io.camunda:http-json:1`)
3. Connector runtime picks up the job, reads URL from `{{secrets.DISBURSEMENT_API_URL}}`
4. Connector makes HTTP call → hits WireMock on the host
5. WireMock returns stubbed response
6. Connector completes the job with response data
7. Process continues to the next element

---

## Step 1: Enable Connector Runtime

```java
@WireMockTest(httpPort = 9999)
@SpringBootTest(properties = {
    "camunda.process-test.connectors-enabled=true",
    "camunda.process-test.connectors-docker-image-version=8.8.10",
    "camunda.process-test.connectors-secrets.DISBURSEMENT_API_URL="
        + "http://host.testcontainers.internal:9999/api/disburse"
})
@CamundaSpringProcessTest
class LoanApprovalProcessTest {
```

| Property | Purpose |
|----------|---------|
| `connectors-enabled=true` | Starts `camunda/connectors-bundle` container |
| `connectors-docker-image-version` | Pin connector image tag (SDK version ≠ image tag) |
| `connectors-secrets.*` | Connector secrets resolved inside the container |
| `host.testcontainers.internal` | Hostname to reach the host from inside Docker |

---

## Step 2: Expose Host Port & Stub the API

```java
@BeforeAll
static void exposePort() {
    // Make WireMock port accessible from the connector container
    Testcontainers.exposeHostPorts(9999);
}

@BeforeEach
void setUp() {
    // Stub the disbursement API — connector will call this
    stubFor(get(urlPathEqualTo("/api/disburse"))
        .willReturn(okJson(
            "{\"disbursement_id\": \"DISB-12345\"}")));
}
```

The connector runtime resolves `{{secrets.DISBURSEMENT_API_URL}}`
→ `http://host.testcontainers.internal:9999/api/disburse` → hits WireMock.

---

## Step 3: Run the Process & Assert

```java
@Test
void shouldApproveLoan_highProfitability() {
    ProcessInstanceEvent instance = client
        .newCreateInstanceCommand()
        .bpmnProcessId("loan-approval")
        .latestVersion()
        .variables(Map.of(
            "interest_rate", 6.0,
            "requested_loan", 50000,
            "monthly_income", 3000))
        .send().join();

    assertThat(instance).isCompleted();
    assertThat(instance).hasVariable("loan_decision", true);
}
```

No `mockJobWorker` — the **real connector** makes the HTTP call to WireMock.

---

## BPMN: REST Connector Configuration

The REST outbound connector in BPMN uses `zeebe:ioMapping` to pass inputs
to the connector runtime as job variables:

```xml
<bpmn:serviceTask id="Task_DisburseLoan" name="Disburse Loan"
    zeebe:modelerTemplate="io.camunda.connectors.HttpJson.v2">
  <bpmn:extensionElements>
    <zeebe:taskDefinition type="io.camunda:http-json:1" />
    <zeebe:ioMapping>
      <zeebe:input source="noAuth" target="authentication.type" />
      <zeebe:input source="GET" target="method" />
      <zeebe:input source="=&quot;{{secrets.DISBURSEMENT_API_URL}}&quot;"
                   target="url" />
    </zeebe:ioMapping>
    <zeebe:taskHeaders>
      <zeebe:header key="resultExpression"
        value="={disbursement_id: response.body.disbursement_id}" />
    </zeebe:taskHeaders>
  </bpmn:extensionElements>
</bpmn:serviceTask>
```

Use the **Camunda Modeler** to configure this — it generates the correct
template, icon, ioMapping, and task headers automatically.

---

## Mocking Job Workers

For service tasks with custom job types (not connectors), use
`mockJobWorker` to complete the job with predefined variables:

```java
@SpringBootTest
@CamundaSpringProcessTest
class MyProcessTest {

    @Autowired
    private CamundaProcessTestContext processTestContext;

    @BeforeEach
    void setUp() {
        // Mock a custom job worker — completes immediately
        // with the given output variables
        processTestContext.mockJobWorker("my-custom-task")
            .thenComplete(Map.of(
                "result", "success",
                "transactionId", "TXN-42"));
    }

    @Test
    void shouldCompleteProcess() {
        ProcessInstanceEvent instance = client
            .newCreateInstanceCommand()
            .bpmnProcessId("my-process")
            .latestVersion()
            .variables(Map.of("input", "value"))
            .send().join();

        assertThat(instance).isCompleted();
        assertThat(instance).hasVariable("result", "success");
    }
}
```

| When to use | Mechanism |
|-------------|-----------|
| **REST outbound connector** (`io.camunda:http-json:1`) | WireMock + `connectors-enabled=true` |
| **Custom job worker** (your own type) | `mockJobWorker("type").thenComplete(...)` |

---

## Testing Gotchas

| Gotcha | Details |
|--------|---------|
| **Docker required** | Testcontainers needs Docker running |
| **First run is slow** | Container image pull (~30s), then cached |
| **DMN boundary values** | Overlapping ranges cause UNIQUE hit policy violations (e.g., `[2..5]` and `>=5.0` both match at 5.0) |
| **DRD variable names** | Sub-decision results are accessible by **decision id**, not output column name |
| **Async assertions** | `CamundaAssert` auto-waits up to 10s for async engine to settle |
| **State isolation** | `@CamundaSpringProcessTest` resets engine between tests |

---

<!-- ============================================================ -->
<!-- SECTION 4: Deployment & Environment Strategy                  -->
<!-- ============================================================ -->

## Part IV
## Deployment & Environment Strategy

---

## Dev / QA / Prod Flow

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐
│     DEV     │ ──→ │     QA      │ ──→ │    PROD     │
│             │     │             │     │             │
│ - Edit DMN  │     │ - Run tests │     │ - Deploy    │
│ - Edit BPMN │     │ - Review    │     │ - Monitor   │
│ - Unit test │     │ - Approve   │     │ - Audit     │
└─────────────┘     └─────────────┘     └─────────────┘
     ▲                                        │
     │         Feedback loop                  │
     └────────────────────────────────────────┘
```

- **DEV**: Edit DMN/BPMN in Camunda Modeler, run tests locally with Testcontainers
- **QA**: Automated test suite, business analyst review of decision tables
- **PROD**: Deploy via API or CI/CD pipeline, monitor with Operate

---

## Deployment via API

### Using the Camunda REST API

```bash
# Deploy BPMN + DMN resources
curl -X POST https://camunda.example.com/v2/deployments \
  -H "Authorization: Bearer $TOKEN" \
  -F "resources=@loan-approval.bpmn" \
  -F "resources=@loan-approval.dmn"
```

### Using the Java client (in code)

```java
client.newDeployResourceCommand()
    .addResourceFromClasspath("bpmn/loan-approval.bpmn")
    .addResourceFromClasspath("dmn/loan-approval.dmn")
    .send()
    .join();
```

### Using Spring Boot auto-deployment

```java
@Deployment(resources = {
    "classpath*:/bpmn/**/*.bpmn",
    "classpath*:/dmn/**/*.dmn"
})
```

Resources are deployed automatically on application startup.

---

## Deployment via Modeler

### Camunda Modeler (Desktop)

1. Open DMN/BPMN file in Camunda Modeler
2. Click **Deploy** → select cluster
3. Done — no code changes, no build, no CI/CD

### Web Modeler (SaaS)

1. Edit DMN directly in the browser
2. Business analysts can modify rules
3. Deploy to cluster with one click
4. Built-in version history

Best for: **quick rule changes** by business analysts without developer involvement.

---

## Versioning of Decisions

Camunda 8 auto-versions every deployment:

```
loan_approval v1  →  loan_approval v2  →  loan_approval v3
   (initial)         (added rule)          (changed threshold)
```

### Key behaviors

| Scenario | What happens |
|----------|--------------|
| **Deploy new DMN version** | New version becomes "latest" automatically |
| **New process instance** | Business Rule Task evaluates latest DMN version when it executes |
| **Running instance (hasn't reached BRT)** | Will pick up the new DMN version when it gets there |
| **Running instance (already past BRT)** | Unaffected — decision was already evaluated |
| **Rollback** | Re-deploy old DMN → becomes new "latest" |

---

## Version Pinning

### Always use latest (default)

```java
client.newCreateInstanceCommand()
    .bpmnProcessId("loan-approval")
    .latestVersion()           // <-- always newest
    .send().join();
```

### Pin to a specific version

```java
client.newCreateInstanceCommand()
    .bpmnProcessId("loan-approval")
    .version(2)                // <-- always version 2
    .send().join();
```

Pin when: regulatory requirements, gradual rollout, A/B testing decisions.

---

## CI/CD Integration

```
┌──────────┐     ┌──────────┐     ┌──────────┐     ┌──────────┐
│  Commit  │ ──→ │  Build   │ ──→ │  Test    │ ──→ │  Deploy  │
│          │     │          │     │          │     │          │
│ .bpmn    │     │ mvn      │     │ 6 tests  │     │ REST API │
│ .dmn     │     │ compile  │     │ pass?    │     │ or CLI   │
│ .java    │     │          │     │          │     │          │
└──────────┘     └──────────┘     └──────────┘     └──────────┘
                                       │
                                  FAIL │
                                       ▼
                                  Block deploy
```

```yaml
# Example GitHub Actions step
- name: Test DMN decisions
  run: ./mvnw test -Dtest=LoanApprovalProcessTest

- name: Deploy to Camunda
  if: success()
  run: |
    curl -X POST $CAMUNDA_URL/v2/deployments \
      -H "Authorization: Bearer $TOKEN" \
      -F "resources=@src/main/resources/bpmn/loan-approval.bpmn" \
      -F "resources=@src/main/resources/dmn/loan-approval.dmn"
```

---

<!-- ============================================================ -->
<!-- WRAP UP                                                       -->
<!-- ============================================================ -->

# Summary

---

## Key Takeaways

| Section | Takeaway |
|---------|----------|
| **1. DMN Rules** | Replace nested code with readable decision tables |
| **2. Orchestration** | BPMN for flow, DMN for logic — fetch data only when needed |
| **3. Testing** | Real engine via Testcontainers, input/output test pattern |
| **4. Deployment** | API or Modeler deploy, auto-versioning, CI/CD ready |

---

## Live Demo

1. Walk through the DMN in Camunda Modeler
2. Run the 6 test scenarios — 3 approvals, 3 rejections
3. Show the process coverage report
4. Change a DMN rule → see a test outcome flip

```bash
./mvnw test -Dtest=LoanApprovalProcessTest
```

---

<!-- ============================================================ -->
<!-- APPENDIX A: RBAC for DMN Rules                                -->
<!-- ============================================================ -->

## Appendix A
## RBAC for DMN Rules — Self-Managed vs SaaS

---

## Who Can Access & Update DMN Rules?

| Action              | SaaS                                          | Self-Managed                                      |
|---------------------|-----------------------------------------------|---------------------------------------------------|
| **View rules**      | Modeler, Analyst, Admin, Owner                | Any user/group with `READ` on the decision        |
| **Edit rules**      | Modeler, Analyst, Admin, Owner                | Any user/group with `UPDATE` on the decision      |
| **Deploy to cluster** | Admin & Owner only — *Modeler cannot deploy* | Any user/group with deploy permission via Identity |
| **Delete decisions** | Admin & Owner only                           | Any user/group with `DELETE` on the decision       |

**SaaS** — 4 fixed roles: Owner, Admin, Modeler, Analyst. Permissions are per-role, not per-decision.

**Self-Managed** — Create custom roles via Identity (Keycloak / OIDC). Assign `READ` / `UPDATE` / `DELETE` per decision ID.

---

<!-- ============================================================ -->
<!-- APPENDIX B: DMN as a Service                                  -->
<!-- ============================================================ -->

## Appendix B
## DMN as a Service — Orchestration Cluster API

---

## DMN as a Service

Any app can evaluate decisions via the Cluster API — no BPMN needed:

```
┌──────────┐      REST / gRPC       ┌────────────────────┐
│  Your    │ ──────────────────────→ │  Camunda Cluster   │
│  App     │    evaluate decision    │                    │
│          │ ←────────────────────── │  DMN Engine        │
└──────────┘      result: true       └────────────────────┘
```

### REST API — `POST /v2/decision-definitions/evaluation`

```bash
curl -X POST https://<cluster>/v2/decision-definitions/evaluation \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "decisionId": "loan_approval",
    "variables": {
      "interest_rate": 6.0,
      "requested_loan": 50000,
      "monthly_income": 3000
    }
  }'
```

API Reference: [Evaluate Decision](https://docs.camunda.io/docs/apis-tools/camunda-api-rest/specifications/evaluate-decision/) | [Camunda REST API Overview](https://docs.camunda.io/docs/apis-tools/camunda-api-rest/camunda-api-rest-overview/)

---

## Java Client Example

```java
// Any microservice can call DMN decisions — no BPMN process required
EvaluateDecisionResponse response = camundaClient
    .newEvaluateDecisionCommand()
    .decisionId("loan_approval")
    .variables(Map.of(
        "interest_rate", 6.0,
        "requested_loan", 50000,
        "monthly_income", 3000))
    .send().join();

boolean approved = response.getDecisionOutput().equals("true");
```

### Why use the cluster as a decision service?

| Benefit                | Detail                                              |
|------------------------|-----------------------------------------------------|
| **Centralized rules**  | One source of truth — all apps use the same decisions|
| **Version managed**    | Update rules without redeploying any app            |
| **Language agnostic**  | REST API works from Python, Node, Go, etc.          |
| **Auditable**          | Every evaluation is logged in the cluster           |

---

<!-- ============================================================ -->
<!-- APPENDIX E: BPMN vs DMN Versioning                            -->
<!-- ============================================================ -->

## Appendix E
## BPMN vs DMN Versioning

---

## BPMN vs DMN: Version Resolution

|                        | BPMN Process                                      | DMN Decision                                                                                   |
|------------------------|---------------------------------------------------|------------------------------------------------------------------------------------------------|
| **Version locked at**  | Process instance creation                         | Decision evaluation (task execution)                                                           |
| **Deploy new version** | Only new instances use it                         | All future evaluations use it — including running instances that haven't reached the task yet   |
| **Running instances**  | Stay on the BPMN version they started with        | Pick up the latest DMN version when the Business Rule Task executes                            |
| **Migration needed?**  | Yes — use migration API to move to new BPMN version | No — DMN changes are picked up automatically                                                 |

---

## DMN-Only Change: No Redeployment of BPMN

```
Timeline:
─────────────────────────────────────────────────────────

  t1: Deploy BPMN v1 + DMN v1
  t2: Start process instance A          → uses BPMN v1
  t3: Instance A reaches BRT            → evaluates DMN v1
  t4: Deploy DMN v2 (only DMN, no BPMN)
  t5: Start process instance B          → uses BPMN v1 (unchanged)
  t6: Instance B reaches BRT            → evaluates DMN v2 ✓
  t7: Start process instance C          → uses BPMN v1
  t8: Instance C reaches BRT            → evaluates DMN v2 ✓

BRT = Business Rule Task
```

DMN rule changes take effect immediately for all future evaluations — no BPMN redeployment or instance migration required.
