package com.camunda.c8processtest;

import io.camunda.client.CamundaClient;
import io.camunda.client.api.response.EvaluateDecisionResponse;
import io.camunda.client.api.response.EvaluatedDecision;
import io.camunda.client.api.response.MatchedDecisionRule;
import io.camunda.process.test.api.CamundaSpringProcessTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests each DMN decision independently — without running the BPMN process.
 * Validates matched rules, evaluated inputs, and decision outputs.
 */
@SpringBootTest
@CamundaSpringProcessTest
class DmnDecisionTest {

    @Autowired
    private CamundaClient client;

    @Nested
    class ProfitabilityDecision {

        @Test
        void lowRate_returnsLow() {
            EvaluateDecisionResponse response = evaluateProfitability(1.5, 50000);
            assertDecisionOutput(response, "probability", "\"low\"");
            assertMatchedRuleIndex(response, "probability", 1); // rule 1: <2.0
        }

        @Test
        void highRate_returnsHigh() {
            EvaluateDecisionResponse response = evaluateProfitability(6.0, 50000);
            assertDecisionOutput(response, "probability", "\"high\"");
            assertMatchedRuleIndex(response, "probability", 2); // rule 2: >=5.0
        }

        @Test
        void midRate_smallLoan_returnsLow() {
            EvaluateDecisionResponse response = evaluateProfitability(3.0, 5000);
            assertDecisionOutput(response, "probability", "\"low\"");
            assertMatchedRuleIndex(response, "probability", 3); // rule 3: [2..5] + <10000
        }

        @Test
        void midRate_mediumLoan_returnsMedium() {
            EvaluateDecisionResponse response = evaluateProfitability(3.0, 50000);
            assertDecisionOutput(response, "probability", "\"medium\"");
            assertMatchedRuleIndex(response, "probability", 4); // rule 4: [2..5] + [10K..1M]
        }

        @Test
        void midRate_largeLoan_returnsHigh() {
            EvaluateDecisionResponse response = evaluateProfitability(3.0, 2000000);
            assertDecisionOutput(response, "probability", "\"high\"");
            assertMatchedRuleIndex(response, "probability", 5); // rule 5: [2..5] + >1M
        }

        private EvaluateDecisionResponse evaluateProfitability(double rate, int loan) {
            return client
                    .newEvaluateDecisionCommand()
                    .decisionId("probability")
                    .variables(Map.of("interest_rate", rate, "requested_loan", loan))
                    .send().join();
        }
    }

    @Nested
    class RiskDecision {

        @Test
        void smallLoan_returnsLow() {
            EvaluateDecisionResponse response = evaluateRisk(5000, 3000);
            assertDecisionOutput(response, "risk", "\"low\"");
            assertMatchedRuleIndex(response, "risk", 1); // rule 1: <10000
        }

        @Test
        void mediumLoan_lowIncome_returnsMedium() {
            EvaluateDecisionResponse response = evaluateRisk(50000, 3000);
            assertDecisionOutput(response, "risk", "\"medium\"");
            assertMatchedRuleIndex(response, "risk", 2); // rule 2: [10K..100K] + <5000
        }

        @Test
        void mediumLoan_highIncome_returnsLow() {
            EvaluateDecisionResponse response = evaluateRisk(50000, 8000);
            assertDecisionOutput(response, "risk", "\"low\"");
            assertMatchedRuleIndex(response, "risk", 3); // rule 3: [10K..100K] + >=5000
        }

        @Test
        void largeLoan_lowIncome_returnsHigh() {
            EvaluateDecisionResponse response = evaluateRisk(200000, 3000);
            assertDecisionOutput(response, "risk", "\"high\"");
            assertMatchedRuleIndex(response, "risk", 4); // rule 4: >100K + <5000
        }

        @Test
        void largeLoan_midIncome_returnsMedium() {
            EvaluateDecisionResponse response = evaluateRisk(200000, 7000);
            assertDecisionOutput(response, "risk", "\"medium\"");
            assertMatchedRuleIndex(response, "risk", 5); // rule 5: >100K + [5K..10K]
        }

        @Test
        void largeLoan_highIncome_returnsLow() {
            EvaluateDecisionResponse response = evaluateRisk(200000, 15000);
            assertDecisionOutput(response, "risk", "\"low\"");
            assertMatchedRuleIndex(response, "risk", 6); // rule 6: >100K + >=10000
        }

        private EvaluateDecisionResponse evaluateRisk(int loan, int income) {
            return client
                    .newEvaluateDecisionCommand()
                    .decisionId("risk")
                    .variables(Map.of("requested_loan", loan, "monthly_income", income))
                    .send().join();
        }
    }

    @Nested
    class LoanApprovalDecision {

        @Test
        void highProfitability_anyRisk_approved() {
            EvaluateDecisionResponse response = evaluateLoanApproval(6.0, 50000, 3000);
            assertDecisionOutput(response, "loan_approval", "true");

            // Verify all 3 decisions in the DRD were evaluated
            assertThat(response.getEvaluatedDecisions()).hasSize(3);
            assertEvaluatedDecisionIds(response, "probability", "risk", "loan_approval");
        }

        @Test
        void mediumProfitability_lowRisk_approved() {
            EvaluateDecisionResponse response = evaluateLoanApproval(3.5, 50000, 8000);
            assertDecisionOutput(response, "loan_approval", "true");
            assertThat(response.getEvaluatedDecisions()).hasSize(3);
        }

        @Test
        void mediumProfitability_highRisk_rejected() {
            EvaluateDecisionResponse response = evaluateLoanApproval(3.0, 200000, 2000);
            assertDecisionOutput(response, "loan_approval", "false");
            assertThat(response.getEvaluatedDecisions()).hasSize(3);
        }

        @Test
        void lowProfitability_lowRisk_approved() {
            EvaluateDecisionResponse response = evaluateLoanApproval(1.5, 5000, 4000);
            assertDecisionOutput(response, "loan_approval", "true");
            assertThat(response.getEvaluatedDecisions()).hasSize(3);
        }

        @Test
        void lowProfitability_mediumRisk_rejected() {
            EvaluateDecisionResponse response = evaluateLoanApproval(1.5, 50000, 3000);
            assertDecisionOutput(response, "loan_approval", "false");
            assertThat(response.getEvaluatedDecisions()).hasSize(3);
        }

        @Test
        void lowProfitability_highRisk_rejected() {
            EvaluateDecisionResponse response = evaluateLoanApproval(1.0, 200000, 3000);
            assertDecisionOutput(response, "loan_approval", "false");
            assertThat(response.getEvaluatedDecisions()).hasSize(3);
        }

        private EvaluateDecisionResponse evaluateLoanApproval(double rate, int loan, int income) {
            return client
                    .newEvaluateDecisionCommand()
                    .decisionId("loan_approval")
                    .variables(Map.of(
                            "interest_rate", rate,
                            "requested_loan", loan,
                            "monthly_income", income))
                    .send().join();
        }
    }

    // --- Shared assertion helpers ---

    private void assertDecisionOutput(EvaluateDecisionResponse response, String decisionId, String expectedOutput) {
        EvaluatedDecision decision = findDecision(response, decisionId);
        assertThat(decision.getDecisionOutput()).isEqualTo(expectedOutput);
        assertThat(decision.getMatchedRules()).as("Expected exactly one matched rule for %s", decisionId).hasSize(1);
    }

    private void assertMatchedRuleIndex(EvaluateDecisionResponse response, String decisionId, int expectedIndex) {
        EvaluatedDecision decision = findDecision(response, decisionId);
        List<MatchedDecisionRule> rules = decision.getMatchedRules();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getRuleIndex()).as("Rule index for %s", decisionId).isEqualTo(expectedIndex);
    }

    private void assertEvaluatedDecisionIds(EvaluateDecisionResponse response, String... expectedIds) {
        List<String> ids = response.getEvaluatedDecisions().stream()
                .map(EvaluatedDecision::getDecisionId)
                .toList();
        assertThat(ids).containsExactly(expectedIds);
    }

    private EvaluatedDecision findDecision(EvaluateDecisionResponse response, String decisionId) {
        return response.getEvaluatedDecisions().stream()
                .filter(d -> d.getDecisionId().equals(decisionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Decision '" + decisionId + "' not found in evaluated decisions"));
    }
}
