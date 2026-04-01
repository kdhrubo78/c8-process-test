package com.camunda.c8processtest;

import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.ProcessInstanceEvent;
import io.camunda.process.test.api.CamundaProcessTestContext;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.testcontainers.Testcontainers;

import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static io.camunda.process.test.api.CamundaAssert.assertThat;

@WireMockTest(httpPort = 9999)
@SpringBootTest(properties = {
        "camunda.process-test.connectors-enabled=true",
        "camunda.process-test.connectors-docker-image-version=8.8.10",
        "camunda.process-test.connectors-secrets.DISBURSEMENT_API_URL=http://host.testcontainers.internal:9999/api/disburse"
})
@CamundaSpringProcessTest
class LoanApprovalProcessTest {

    @BeforeAll
    static void exposePort() {
        Testcontainers.exposeHostPorts(9999);
    }

    @Autowired
    private CamundaClient client;

    @Autowired
    private CamundaProcessTestContext processTestContext;

    @BeforeEach
    void setUp() {
        // Stub the disbursement REST API — the REST outbound connector in the
        // Camunda container will call this WireMock endpoint.
        stubFor(get(urlPathEqualTo("/api/disburse"))
                .willReturn(okJson("{\"disbursement_id\": \"DISB-12345\"}")));
    }

    // --- Approval scenarios (real DMN) ---

    @Test
    void shouldApproveLoan_highProfitability() {
        // Interest rate > 5.0 → high profitability → approved regardless of risk
        ProcessInstanceEvent instance = createLoanInstance(6.0, 50000, 3000);
        assertThat(instance).isCompleted();
        assertThat(instance).hasVariable("loan_decision", true);
    }

    @Test
    void shouldApproveLoan_mediumProfitabilityLowRisk() {
        // Interest rate [2..5], loan [10000..1000000] → medium profitability
        // Loan [10000..100000], income >= 5000 → low risk
        ProcessInstanceEvent instance = createLoanInstance(3.5, 50000, 8000);
        assertThat(instance).isCompleted();
        assertThat(instance).hasVariable("loan_decision", true);
    }

    @Test
    void shouldApproveLoan_lowProfitabilityLowRisk() {
        // Interest rate < 2.0 → low profitability
        // Loan < 10000 → low risk
        ProcessInstanceEvent instance = createLoanInstance(1.5, 5000, 4000);
        assertThat(instance).isCompleted();
        assertThat(instance).hasVariable("loan_decision", true);
    }

    // --- Rejection scenarios (real DMN) ---

    @Test
    void shouldRejectLoan_lowProfitabilityHighRisk() {
        // Interest rate < 2.0 → low profitability
        // Loan > 100000, income < 5000 → high risk
        ProcessInstanceEvent instance = createLoanInstance(1.0, 200000, 3000);
        assertThat(instance).isCompleted();
        assertThat(instance).hasVariable("loan_decision", false);
    }

    @Test
    void shouldRejectLoan_mediumProfitabilityHighRisk() {
        // Interest rate [2..5], loan [10000..1000000] → medium profitability
        // Loan > 100000, income < 5000 → high risk
        ProcessInstanceEvent instance = createLoanInstance(3.0, 200000, 2000);
        assertThat(instance).isCompleted();
        assertThat(instance).hasVariable("loan_decision", false);
    }

    @Test
    void shouldRejectLoan_lowProfitabilityMediumRisk() {
        // Interest rate < 2.0 → low profitability
        // Loan [10000..100000], income < 5000 → medium risk
        ProcessInstanceEvent instance = createLoanInstance(1.5, 50000, 3000);
        assertThat(instance).isCompleted();
        assertThat(instance).hasVariable("loan_decision", false);
    }

    // --- Mocked DMN scenarios ---

    @Test
    void shouldComplete_whenDmnMockedApproved() {
        // Mock the DMN — the real decision table is NOT evaluated
        processTestContext.mockDmnDecision("loan_approval", Map.of(
                "approved", true
        ));

        // Input values are irrelevant — DMN is mocked
        ProcessInstanceEvent instance = createLoanInstance(0.0, 0, 0);

        assertThat(instance).isCompleted();
        assertThat(instance).hasVariable("loan_decision", Map.of("approved", true));
    }

    @Test
    void shouldComplete_whenDmnMockedRejected() {
        processTestContext.mockDmnDecision("loan_approval", Map.of(
                "approved", false
        ));

        ProcessInstanceEvent instance = createLoanInstance(0.0, 0, 0);

        assertThat(instance).isCompleted();
        assertThat(instance).hasVariable("loan_decision", Map.of("approved", false));
    }

    private ProcessInstanceEvent createLoanInstance(double interestRate, int requestedLoan, int monthlyIncome) {
        return client
                .newCreateInstanceCommand()
                .bpmnProcessId("loan-approval")
                .latestVersion()
                .variables(Map.of(
                        "interest_rate", interestRate,
                        "requested_loan", requestedLoan,
                        "monthly_income", monthlyIncome
                ))
                .send()
                .join();
    }
}
