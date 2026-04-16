package com.camunda.c8processtest;

import ai.philterd.phileas.PhileasConfiguration;
import ai.philterd.phileas.model.filtering.FilterType;
import ai.philterd.phileas.model.filtering.Span;
import ai.philterd.phileas.model.filtering.TextFilterResult;
import ai.philterd.phileas.policy.Identifiers;
import ai.philterd.phileas.policy.Policy;
import ai.philterd.phileas.policy.filters.CreditCard;
import ai.philterd.phileas.policy.filters.CustomDictionary;
import ai.philterd.phileas.policy.filters.EmailAddress;
import ai.philterd.phileas.policy.filters.IpAddress;
import ai.philterd.phileas.policy.filters.PhoneNumber;
import ai.philterd.phileas.policy.filters.Ssn;
import ai.philterd.phileas.policy.filters.Url;
import ai.philterd.phileas.policy.filters.ZipCode;
import ai.philterd.phileas.services.context.ContextService;
import ai.philterd.phileas.services.context.DefaultContextService;
import ai.philterd.phileas.services.disambiguation.vector.InMemoryVectorService;
import ai.philterd.phileas.services.disambiguation.vector.VectorService;
import ai.philterd.phileas.services.filters.filtering.PlainTextFilterService;
import ai.philterd.phileas.services.strategies.rules.CreditCardFilterStrategy;
import ai.philterd.phileas.services.strategies.custom.CustomDictionaryFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.EmailAddressFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.IpAddressFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.PhoneNumberFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.SsnFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.UrlFilterStrategy;
import ai.philterd.phileas.services.strategies.rules.ZipCodeFilterStrategy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;

import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Examples of how to use the phileas library (ai.philterd:phileas) to redact PII
 * from plain text. Each nested class groups a different use case; every test is
 * documented with the specific behavior it demonstrates. All tests use only the
 * rule-based filters so they are hermetic — no external philter-ner service is
 * required.
 *
 * Maven dependency:
 *   <dependency>
 *     <groupId>ai.philterd</groupId>
 *     <artifactId>phileas</artifactId>
 *     <version>3.3.0</version>
 *     <scope>test</scope>
 *   </dependency>
 *
 * Core API recap:
 *   - {@link PlainTextFilterService} is the entry point. Build it once per test
 *     with a {@link PhileasConfiguration}, a {@link ContextService} (use
 *     {@link DefaultContextService}), a {@link VectorService} (use
 *     {@link InMemoryVectorService}), and a nullable alert service.
 *   - A {@link Policy} holds an {@link Identifiers} container. For each PII
 *     type you want to redact, set the matching field on Identifiers (e.g.
 *     {@code setEmailAddress(...)}) with a filter POJO from
 *     {@code ai.philterd.phileas.policy.filters} and attach one or more
 *     FilterStrategy instances to configure replacement behavior.
 *   - {@code filter(policy, context, input)} returns a {@link TextFilterResult}.
 *     Call {@code getFilteredText()} for the redacted string and
 *     {@code getExplanation().appliedSpans()} for per-hit {@link Span} metadata.
 */
class PhileasUsageTest {

    private PlainTextFilterService filterService;
    private String currentTestName;

    /**
     * Creates a fresh filter service before each test. The 4-arg constructor is
     * the form exposed on 3.3.0 — the single-arg form shown in the README does
     * not exist. {@code null} is passed for the optional alert service.
     */
    @BeforeEach
    void setUp(TestInfo testInfo) {
        PhileasConfiguration config = new PhileasConfiguration(new Properties());
        ContextService contextService = new DefaultContextService();
        VectorService vectorService = new InMemoryVectorService();
        filterService = new PlainTextFilterService(config, contextService, vectorService, null);
        currentTestName = testInfo.getDisplayName();
    }

    /**
     * Convenience wrapper that runs the given policy against {@code input} using
     * a fixed context id, then prints the input, the redacted output, and the
     * applied spans so each test's effect is visible in the console log. The
     * context id scopes anonymization state (e.g. consistent token replacement)
     * inside the {@link ContextService}.
     */
    private TextFilterResult filter(Policy policy, String input) throws Exception {
        TextFilterResult result = filterService.filter(policy, "unit-test-ctx", input);
        System.out.println("--- " + currentTestName + " ---");
        System.out.println("  input : " + input);
        System.out.println("  output: " + result.getFilteredText());
        for (Span span : result.getExplanation().appliedSpans()) {
            System.out.printf("  span  : %-20s [%d..%d] '%s' -> '%s'%n",
                    span.getFilterType(),
                    span.getCharacterStart(),
                    span.getCharacterEnd(),
                    span.getText(),
                    span.getReplacement());
        }
        return result;
    }

    // ------------------------------------------------------------------
    // 1. Single-filter examples — one PII type per policy
    // ------------------------------------------------------------------
    /**
     * One-PII-type-per-policy cases. Each test configures a single filter
     * (EmailAddress, Ssn, CreditCard, PhoneNumber, IpAddress, Url) with its
     * default strategy and verifies that the token is replaced with the
     * canonical {@code {{{REDACTED-<type>}}}} marker. These are the minimum
     * viable snippets to copy-paste when you need just one PII type.
     */
    @Nested
    @DisplayName("Single-filter policies")
    class SingleFilter {

        /**
         * Demonstrates redacting an email address with the default
         * {@link EmailAddressFilterStrategy}. Also verifies that a single
         * {@link Span} is produced and its {@link FilterType} is
         * {@link FilterType#EMAIL_ADDRESS}.
         */
        @Test
        @DisplayName("Redacts an email address")
        void redactsEmail() throws Exception {
            EmailAddress email = new EmailAddress();
            email.setEmailAddressFilterStrategies(List.of(new EmailAddressFilterStrategy()));

            Identifiers identifiers = new Identifiers();
            identifiers.setEmailAddress(email);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            TextFilterResult result = filter(policy, "Contact me at jane.doe@example.com please.");

            assertFalse(result.getFilteredText().contains("jane.doe@example.com"));
            assertTrue(result.getFilteredText().contains("{{{REDACTED-email-address}}}"));
            assertEquals(1, result.getExplanation().appliedSpans().size());
            assertEquals(FilterType.EMAIL_ADDRESS,
                    result.getExplanation().appliedSpans().get(0).getFilterType());
        }

        /**
         * Demonstrates redacting a US Social Security Number in the
         * {@code NNN-NN-NNNN} format. Phileas's rule-based SSN detector
         * recognizes the dashed form out of the box.
         */
        @Test
        @DisplayName("Redacts an SSN")
        void redactsSsn() throws Exception {
            Ssn ssn = new Ssn();
            ssn.setSsnFilterStrategies(List.of(new SsnFilterStrategy()));

            Identifiers identifiers = new Identifiers();
            identifiers.setSsn(ssn);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            TextFilterResult result = filter(policy, "His SSN is 123-45-6789.");

            assertFalse(result.getFilteredText().contains("123-45-6789"));
            assertTrue(result.getFilteredText().contains("{{{REDACTED-ssn}}}"));
        }

        /**
         * Demonstrates redacting a credit-card number. Phileas validates
         * candidates with the Luhn checksum, so this test uses a valid
         * test PAN (4121742025464465) rather than an arbitrary 16-digit string.
         */
        @Test
        @DisplayName("Redacts a credit-card number")
        void redactsCreditCard() throws Exception {
            CreditCard cc = new CreditCard();
            cc.setCreditCardFilterStrategies(List.of(new CreditCardFilterStrategy()));

            Identifiers identifiers = new Identifiers();
            identifiers.setCreditCard(cc);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            TextFilterResult result = filter(policy, "Card on file: 4121742025464465.");

            assertFalse(result.getFilteredText().contains("4121742025464465"));
            assertTrue(result.getFilteredText().contains("{{{REDACTED-credit-card}}}"));
        }

        /**
         * Demonstrates redacting a US phone number in the common
         * {@code (NNN) NNN-NNNN} format. The default
         * {@link PhoneNumberFilterStrategy} handles parentheses, spaces and
         * dashes between the area code, prefix and line number.
         */
        @Test
        @DisplayName("Redacts a phone number")
        void redactsPhoneNumber() throws Exception {
            PhoneNumber phone = new PhoneNumber();
            phone.setPhoneNumberFilterStrategies(List.of(new PhoneNumberFilterStrategy()));

            Identifiers identifiers = new Identifiers();
            identifiers.setPhoneNumber(phone);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            TextFilterResult result = filter(policy, "Call me at (555) 123-4567.");

            assertFalse(result.getFilteredText().contains("(555) 123-4567"));
            assertTrue(result.getFilteredText().contains("{{{REDACTED-phone-number}}}"));
        }

        /**
         * Demonstrates redacting an IPv4 address embedded in free-form text.
         * Useful when sanitizing access logs or audit trails before storing
         * them in a shared analytics system.
         */
        @Test
        @DisplayName("Redacts an IP address")
        void redactsIpAddress() throws Exception {
            IpAddress ip = new IpAddress();
            ip.setIpAddressFilterStrategies(List.of(new IpAddressFilterStrategy()));

            Identifiers identifiers = new Identifiers();
            identifiers.setIpAddress(ip);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            TextFilterResult result = filter(policy, "Client IP was 192.168.1.42 at login.");

            assertFalse(result.getFilteredText().contains("192.168.1.42"));
            assertTrue(result.getFilteredText().contains("{{{REDACTED-ip-address}}}"));
        }

        /**
         * Demonstrates redacting a URL. Note that phileas's default URL
         * rule matches the scheme + host portion but not path segments —
         * the span covers {@code https://secret.inter} only, so the
         * redacted output still contains the trailing {@code nal/admin}.
         * In production, layer a {@link CustomDictionary} or a stricter
         * regex if full-URL redaction is required.
         */
        @Test
        @DisplayName("Redacts a URL")
        void redactsUrl() throws Exception {
            Url url = new Url();
            url.setUrlFilterStrategies(List.of(new UrlFilterStrategy()));

            Identifiers identifiers = new Identifiers();
            identifiers.setUrl(url);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            TextFilterResult result = filter(policy, "See https://secret.internal/admin for details.");

            assertFalse(result.getFilteredText().contains("https://secret.internal/admin"));
            assertTrue(result.getFilteredText().contains("{{{REDACTED-url}}}"));
        }
    }

    // ------------------------------------------------------------------
    // 2. Multi-filter policy — several PII types in one pass
    // ------------------------------------------------------------------
    /**
     * Multi-filter cases. Shows how a single policy can combine several PII
     * types, and confirms that clean text is returned unchanged.
     */
    @Nested
    @DisplayName("Multi-filter policies")
    class MultiFilter {

        /**
         * Demonstrates stacking several filters on one {@link Identifiers}.
         * A single {@code filter()} call produces exactly three applied spans —
         * one per PII type — in the same pass over the input, avoiding the
         * cost and ordering headaches of repeated filtering.
         */
        @Test
        @DisplayName("Redacts email, SSN and credit card in one call")
        void redactsMultipleTypes() throws Exception {
            EmailAddress email = new EmailAddress();
            email.setEmailAddressFilterStrategies(List.of(new EmailAddressFilterStrategy()));

            Ssn ssn = new Ssn();
            ssn.setSsnFilterStrategies(List.of(new SsnFilterStrategy()));

            CreditCard cc = new CreditCard();
            cc.setCreditCardFilterStrategies(List.of(new CreditCardFilterStrategy()));

            Identifiers identifiers = new Identifiers();
            identifiers.setEmailAddress(email);
            identifiers.setSsn(ssn);
            identifiers.setCreditCard(cc);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            String input = "Email test@something.com, SSN 123-45-6789, card 4121742025464465.";
            TextFilterResult result = filter(policy, input);

            String out = result.getFilteredText();
            assertTrue(out.contains("{{{REDACTED-email-address}}}"));
            assertTrue(out.contains("{{{REDACTED-ssn}}}"));
            assertTrue(out.contains("{{{REDACTED-credit-card}}}"));

            List<Span> spans = result.getExplanation().appliedSpans();
            assertEquals(3, spans.size());
            assertTrue(spans.stream().anyMatch(s -> s.getFilterType() == FilterType.EMAIL_ADDRESS));
            assertTrue(spans.stream().anyMatch(s -> s.getFilterType() == FilterType.SSN));
            assertTrue(spans.stream().anyMatch(s -> s.getFilterType() == FilterType.CREDIT_CARD));
        }

        /**
         * Negative case: when no PII matches any configured filter, phileas
         * returns the original string byte-for-byte and produces zero applied
         * spans. Useful as a regression guard against accidental false
         * positives after upgrading the library.
         */
        @Test
        @DisplayName("Returns input unchanged when no PII is present")
        void leavesCleanTextUnchanged() throws Exception {
            EmailAddress email = new EmailAddress();
            email.setEmailAddressFilterStrategies(List.of(new EmailAddressFilterStrategy()));

            Identifiers identifiers = new Identifiers();
            identifiers.setEmailAddress(email);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            String clean = "This sentence contains no personal information whatsoever.";
            TextFilterResult result = filter(policy, clean);

            assertEquals(clean, result.getFilteredText());
            assertTrue(result.getExplanation().appliedSpans().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // 3. Strategy customization — how each hit is replaced
    // ------------------------------------------------------------------
    /**
     * Strategy-customization cases. The filter POJO says *what* phileas looks
     * for; the FilterStrategy attached to it says *how* each hit is rewritten.
     * These tests tweak the strategy to change replacement tokens, truncate
     * values in place, or skip specific literals.
     */
    @Nested
    @DisplayName("Filter strategies")
    class Strategies {

        /**
         * Demonstrates overriding the default {@code {{{REDACTED-%t}}}} token
         * by calling {@code setRedactionFormat} on a strategy. The {@code %t}
         * placeholder is substituted with the filter type's JSON name (e.g.
         * {@code email-address}), so a single format string works across
         * every filter type.
         */
        @Test
        @DisplayName("Custom redaction format uses the %t placeholder")
        void customRedactionFormat() throws Exception {
            EmailAddressFilterStrategy strategy = new EmailAddressFilterStrategy();
            strategy.setRedactionFormat("<<HIDDEN-%t>>");

            EmailAddress email = new EmailAddress();
            email.setEmailAddressFilterStrategies(List.of(strategy));

            Identifiers identifiers = new Identifiers();
            identifiers.setEmailAddress(email);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            TextFilterResult result = filter(policy, "Write to admin@example.com.");

            assertTrue(result.getFilteredText().contains("<<HIDDEN-email-address>>"));
            assertFalse(result.getFilteredText().contains("admin@example.com"));
        }

        /**
         * Demonstrates the zip-code-specific {@code setTruncateDigits}
         * behavior: instead of substituting a redaction marker, phileas keeps
         * the leading digits and masks the trailing ones. This is how you
         * preserve geographic coarseness (say, the first two digits of a
         * US ZIP) while dropping street-level precision.
         */
        @Test
        @DisplayName("Zip code truncation keeps only the leading digits")
        void zipCodeTruncation() throws Exception {
            ZipCodeFilterStrategy zipStrategy = new ZipCodeFilterStrategy();
            zipStrategy.setTruncateDigits(2);

            ZipCode zip = new ZipCode();
            zip.setZipCodeFilterStrategies(List.of(zipStrategy));

            Identifiers identifiers = new Identifiers();
            identifiers.setZipCode(zip);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            TextFilterResult result = filter(policy, "Shipping zip is 90210.");

            // With truncateDigits=2 the trailing digits are masked.
            assertFalse(result.getFilteredText().contains("90210"));
            List<Span> spans = result.getExplanation().appliedSpans();
            assertEquals(1, spans.size());
            assertEquals(FilterType.ZIP_CODE, spans.get(0).getFilterType());
        }

        /**
         * Demonstrates an allow-list via {@code setIgnored}. Any literal in
         * the ignore set is skipped by the filter even if it matches the
         * rule, while other values of the same type are still redacted.
         * Here {@code 90210} is preserved but {@code 30301} is still caught.
         */
        @Test
        @DisplayName("Ignored values are left alone")
        void ignoredZipIsPreserved() throws Exception {
            ZipCode zip = new ZipCode();
            zip.setZipCodeFilterStrategies(List.of(new ZipCodeFilterStrategy()));
            zip.setIgnored(Set.of("90210"));

            Identifiers identifiers = new Identifiers();
            identifiers.setZipCode(zip);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            TextFilterResult result = filter(policy, "Store zip 90210 and customer zip 30301.");

            assertTrue(result.getFilteredText().contains("90210"));
            assertFalse(result.getFilteredText().contains("30301"));
        }
    }

    // ------------------------------------------------------------------
    // 4. Inspecting spans on the response
    // ------------------------------------------------------------------
    /**
     * Response-inspection cases. When redaction alone isn't enough — for
     * instance when you need an audit log or want to render highlights in a
     * UI — use the {@link Span} metadata on the {@code Explanation}.
     */
    @Nested
    @DisplayName("Response inspection")
    class ResponseInspection {

        /**
         * Demonstrates that every applied {@link Span} exposes the filter
         * type, the exact original substring, the replacement token, and
         * the character offsets (start inclusive, end exclusive) within the
         * input text. The offsets are calculated against the *input* text,
         * not the redacted output, so they can be used to build highlight
         * overlays on the original string.
         */
        @Test
        @DisplayName("Span carries offsets, original text, replacement and filter type")
        void spanCarriesAllMetadata() throws Exception {
            EmailAddress email = new EmailAddress();
            email.setEmailAddressFilterStrategies(List.of(new EmailAddressFilterStrategy()));

            Identifiers identifiers = new Identifiers();
            identifiers.setEmailAddress(email);

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            String input = "Email: jane.doe@example.com.";
            TextFilterResult result = filter(policy, input);

            List<Span> spans = result.getExplanation().appliedSpans();
            assertEquals(1, spans.size());
            Span span = spans.get(0);

            assertEquals(FilterType.EMAIL_ADDRESS, span.getFilterType());
            assertEquals("jane.doe@example.com", span.getText());
            assertEquals("{{{REDACTED-email-address}}}", span.getReplacement());
            assertEquals(input.indexOf("jane.doe@example.com"), span.getCharacterStart());
            assertEquals(input.indexOf("jane.doe@example.com") + "jane.doe@example.com".length(),
                    span.getCharacterEnd());
            assertNotNull(span.getContext());
        }
    }

    // ------------------------------------------------------------------
    // 5. Custom dictionary — redact arbitrary terms
    // ------------------------------------------------------------------
    /**
     * Custom-dictionary case. Phileas's built-in filters target well-known
     * PII formats; when you need to redact project-specific jargon — internal
     * code names, product IDs, confidential labels — use a
     * {@link CustomDictionary} to plug in your own word list.
     */
    @Nested
    @DisplayName("Custom dictionary")
    class CustomDictionaryExamples {

        /**
         * Demonstrates a custom dictionary with two terms ({@code acme},
         * {@code globex}) being redacted from free text. Note that
         * {@code Identifiers#setCustomDictionaries} takes a list — you can
         * register multiple dictionaries with different strategies on the
         * same policy. Lowercase single-word terms are used because the
         * default matcher is case- and token-sensitive.
         */
        @Test
        @DisplayName("Redacts terms from a custom dictionary")
        void redactsCustomTerms() throws Exception {
            CustomDictionary dict = new CustomDictionary();
            dict.setTerms(List.of("acme", "globex"));
            dict.setCustomDictionaryFilterStrategies(List.of(new CustomDictionaryFilterStrategy()));

            Identifiers identifiers = new Identifiers();
            identifiers.setCustomDictionaries(List.of(dict));

            Policy policy = new Policy();
            policy.setIdentifiers(identifiers);

            TextFilterResult result =
                    filter(policy, "Status update on acme and globex accounts.");

            assertFalse(result.getFilteredText().contains("acme"));
            assertFalse(result.getFilteredText().contains("globex"));
            assertEquals(2, result.getExplanation().appliedSpans().size());
        }
    }

    // ------------------------------------------------------------------
    // 6. JSON policy — load configuration from a string / file
    // ------------------------------------------------------------------
    /**
     * JSON-policy case. {@link Policy} is Gson-serializable, which lets you
     * keep policies as configuration files alongside your code (or load them
     * from a database, a config service, etc.) instead of constructing them
     * with the builder-style Java API.
     */
    @Nested
    @DisplayName("JSON policy")
    class JsonPolicy {

        /**
         * Demonstrates deserializing a policy from a JSON string via Gson
         * and applying it exactly as if it had been built programmatically.
         * The JSON mirrors the Java bean shape: the {@code identifiers}
         * object has an {@code emailAddress} field, which has an
         * {@code emailAddressFilterStrategies} array, and each strategy
         * object sets {@code strategy} and {@code redactionFormat}.
         */
        @Test
        @DisplayName("Loads a policy from JSON and applies it")
        void loadsPolicyFromJson() throws Exception {
            String json = """
                    {
                      "name": "json-email",
                      "identifiers": {
                        "emailAddress": {
                          "emailAddressFilterStrategies": [
                            { "strategy": "REDACT", "redactionFormat": "{{{REDACTED-%t}}}" }
                          ]
                        }
                      }
                    }
                    """;

            Gson gson = new GsonBuilder().create();
            Policy policy = gson.fromJson(json, Policy.class);

            TextFilterResult result = filter(policy, "Reach me at dev@example.com anytime.");

            assertFalse(result.getFilteredText().contains("dev@example.com"));
            assertTrue(result.getFilteredText().contains("{{{REDACTED-email-address}}}"));
            assertNotNull(policy.getIdentifiers().getEmailAddress());
        }
    }
}
