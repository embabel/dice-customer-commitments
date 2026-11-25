package example.dice.commitments;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.dice.audit.AuditCoverageException;
import com.embabel.dice.proposition.Proposition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveCaseStoreTest {

    // R1. Every new proposition has grounding in the exact submitted source.
    // E1. Signed authority requires attestation and an explicit active predecessor for a revision.
    // I1. The predecessor and successor change together in historical DICE views.
    // L1. Retrying or rephrasing an unchanged fact preserves the first canonical evidence.

    @TempDir
    Path directory;

    @Test
    void E1_I1_appliesOnlyAnAttestedAmendmentAndReplaysTheWholeTransitionAfterRestart() {
        Instant baseline;
        Instant amendmentTime;
        String priorId;
        String revisedId;
        String agreementText = "Both parties signed that Rivet will move the depot accounts by November 25.";
        String amendmentText = "Both parties signed an extension moving the depot migration to December 2.";
        try (var app = start()) {
            var store = app.getBean(LiveCaseStore.class);
            baseline = store.read(null).baseline();
            var agreement = new LiveCase.Upload("Signed onboarding", "SIGNED_AGREEMENT", agreementText, null, true);
            var agreed = save(store, agreement, "Rivet will migrate depot accounts by November 25.", "COMMITMENT");
            var prior = agreed.claims().getFirst();
            priorId = prior.id();
            assertThat(prior.outcome()).isEqualTo("AGREED");
            assertThat(prior.source().text()).isEqualTo(agreementText);
            assertThat(prior.source().receivedAt()).isEqualTo(agreed.snapshot().asOf());

            var email = new LiveCase.Upload("Customer request", "EMAIL", "Can we move it to November 21?", priorId, true);
            var suggestion = save(store, email, "Tandem asks to move migration to November 21.", "AMENDMENT");
            assertThat(suggestion.claims().getFirst().outcome()).isEqualTo("REVIEW_NEEDED");
            assertThat(claim(suggestion.snapshot(), priorId).status()).isEqualTo("ACTIVE");

            var notAttested = new LiveCase.Upload("Unattested candidate", "SIGNED_AMENDMENT",
                    "The attached amendment might change the date to December 1.", priorId);
            var unconfirmed = save(store, notAttested, "The migration moves to December 1.", "AMENDMENT");
            assertThat(unconfirmed.claims().getFirst().outcome()).isEqualTo("REVIEW_NEEDED");
            assertThat(claim(unconfirmed.snapshot(), priorId).status()).isEqualTo("ACTIVE");

            var amendment = new LiveCase.Upload("Signed extension", "SIGNED_AMENDMENT", amendmentText, priorId, true);
            var amended = store.save(amendment, List.of(
                    candidate(amendment, "Rivet will migrate depot accounts by December 2.", "AMENDMENT"),
                    candidate(amendment, "The parties should confirm the cutover time in writing.", "REMINDER")));
            var revised = amended.claims().getFirst();
            revisedId = revised.id();
            amendmentTime = amended.snapshot().asOf();
            assertThat(revised.outcome()).isEqualTo("REVISED");
            assertThat(revised.replacesClaimId()).isEqualTo(priorId);
            assertThat(amended.claims()).allMatch(claim -> claim.source().receivedAt().equals(amendmentTime));
            assertThat(amended.claims()).allMatch(claim -> claim.source().text().equals(amendmentText));
            assertThat(claim(amended.snapshot(), priorId).status()).isEqualTo("SUPERSEDED");
        }

        try (var restarted = start()) {
            var store = restarted.getBean(LiveCaseStore.class);
            var before = store.read(amendmentTime.minusNanos(1));
            assertThat(before.claims()).extracting(LiveCase.Claim::id).doesNotContain(revisedId);
            assertThat(claim(before, priorId).status()).isEqualTo("ACTIVE");
            assertThat(claim(before, priorId).source().text()).isEqualTo(agreementText);
            var after = store.read(amendmentTime);
            assertThat(claim(after, priorId).status()).isEqualTo("SUPERSEDED");
            assertThat(claim(after, revisedId).source().text()).isEqualTo(amendmentText);
            assertThat(after.claims()).filteredOn(claim -> claim.source().receivedAt().equals(amendmentTime))
                    .hasSize(2);
            assertThat(store.read(null).asOf()).isEqualTo(amendmentTime);
            assertThat(store.read(baseline).claims()).isEmpty();
            assertThatThrownBy(() -> store.read(baseline.minusNanos(1))).isInstanceOf(AuditCoverageException.class);
        }
    }

    @Test
    void R1_L1_checksGroundingAndPreservesCanonicalCitationsAcrossRetriesAndDuplicateText() {
        try (var app = start()) {
            var store = app.getBean(LiveCaseStore.class);
            var email = new LiveCase.Upload("Saturday question", "EMAIL", "Could we try Saturday?", null);
            var other = new LiveCase.Upload("Slack import", "CALL", "Following up on the request for Saturday.", null);
            String text = "Tandem requested a Saturday cutover.";
            assertThatThrownBy(() -> store.save(email, List.of(candidate(other, text, "REQUEST"))))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(store.read(null).claims()).isEmpty();

            var first = save(store, email, text, "REQUEST");
            var retry = save(store, email, text, "TARGET");
            var duplicateCandidate = new LiveCase.Candidate(candidate(other, text, "TARGET").proposition(),
                    "TARGET", "another-decision-model");
            var duplicate = store.save(other, List.of(duplicateCandidate, duplicateCandidate));
            assertThat(retry).isEqualTo(first);
            assertThat(duplicate.claims()).hasSize(1);
            var observation = duplicate.claims().getFirst();
            assertThat(observation.id()).isEqualTo(first.claims().getFirst().id());
            assertThat(observation.source().text()).isEqualTo(other.text());
            assertThat(observation.classification()).isEqualTo("TARGET");
            assertThat(observation.decisionProvider()).isEqualTo("another-decision-model");
            assertThat(observation.reason()).contains("matches an existing claim");
            assertThat(duplicate.snapshot().claims()).isEqualTo(first.claims());
            assertThat(duplicate.snapshot().claims()).hasSize(1);
            assertThat(duplicate.snapshot().asOf()).isAfter(first.snapshot().asOf());
            assertThat(store.read(first.snapshot().asOf()).claims()).isEqualTo(first.claims());
        }
    }

    @Test
    void E1_doesNotRetireAnAgreementWhenTheAmendmentDeduplicatesToAnEarlierUnsignedClaim() {
        LiveCase.Upload amendment;
        LiveCase.Intake duplicate;
        try (var app = start()) {
            var store = app.getBean(LiveCaseStore.class);
            var agreement = new LiveCase.Upload("Signed agreement", "SIGNED_AGREEMENT",
                    "We signed for November 25.", null, true);
            var priorId = save(store, agreement, "Migration is due November 25.", "COMMITMENT")
                    .claims().getFirst().id();

            var email = new LiveCase.Upload("Proposed date", "EMAIL", "Could we make it December 2?", null);
            var proposed = save(store, email, "Migration is due December 2.", "REQUEST").claims().getFirst();

            amendment = new LiveCase.Upload("Signed extension", "SIGNED_AMENDMENT",
                    "We signed an extension to December 2.", priorId, true);
            duplicate = save(store, amendment, proposed.text(), "AMENDMENT");
            var observation = duplicate.claims().getFirst();
            assertThat(observation.id()).isEqualTo(proposed.id());
            assertThat(observation.source().text()).isEqualTo(amendment.text());
            assertThat(observation.classification()).isEqualTo("AMENDMENT");
            assertThat(observation.outcome()).isEqualTo("REVIEW_NEEDED");
            assertThat(observation.reason()).contains("matches an existing claim");
            assertThat(observation.replacesClaimId()).isNull();
            assertThat(claim(duplicate.snapshot(), proposed.id())).isEqualTo(proposed);
            assertThat(claim(duplicate.snapshot(), priorId).status()).isEqualTo("ACTIVE");
        }
        try (var restarted = start()) {
            var store = restarted.getBean(LiveCaseStore.class);
            assertThat(save(store, amendment, duplicate.claims().getFirst().text(), "REQUEST")).isEqualTo(duplicate);
            assertThat(store.read(duplicate.snapshot().asOf()).claims()).isEqualTo(duplicate.snapshot().claims());
        }
    }

    @Test
    void E1_holdsConflictingAssessmentsOfTheSameClaimInOneReceiptForReview() {
        try (var app = start()) {
            var store = app.getBean(LiveCaseStore.class);
            var signed = new LiveCase.Upload("Signed handoff", "SIGNED_AGREEMENT",
                    "The parties signed the handoff schedule.", null, true);
            String text = "The handoff is scheduled for Saturday.";
            var result = store.save(signed, List.of(
                    candidate(signed, text, "COMMITMENT"), candidate(signed, text, "REQUEST")));
            assertThat(result.claims()).extracting(LiveCase.Claim::classification).containsExactly("COMMITMENT", "REQUEST");
            assertThat(result.claims()).extracting(LiveCase.Claim::outcome).containsOnly("REVIEW_NEEDED");
            assertThat(result.claims()).extracting(LiveCase.Claim::id).containsOnly(result.snapshot().claims().getFirst().id());
            assertThat(result.snapshot().claims()).extracting(LiveCase.Claim::outcome).containsExactly("REVIEW_NEEDED");
        }
    }

    private static LiveCase.Intake save(LiveCaseStore store, LiveCase.Upload upload, String text, String classification) {
        return store.save(upload, List.of(candidate(upload, text, classification)));
    }

    private static LiveCase.Candidate candidate(LiveCase.Upload upload, String text, String classification) {
        var proposition = Proposition.draft(LiveCase.CONTEXT, text, 0.9)
                .withGrounding(List.of(LiveCase.sourceId(upload))).toProposition();
        return new LiveCase.Candidate(proposition, classification, "test-decision-model");
    }

    private static LiveCase.Claim claim(LiveCase.Snapshot snapshot, String id) {
        return snapshot.claims().stream().filter(claim -> claim.id().equals(id)).findFirst().orElseThrow();
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(Application.class, NoProviderCalls.class)
                .web(WebApplicationType.NONE)
                .profiles("live")
                .run("--commitments.db=" + directory.resolve("workspace.sqlite"),
                        "--commitments.model=store-test", "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                        "--spring.autoconfigure.exclude=com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration");
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class NoProviderCalls {
        @Bean
        LlmService<?> storeTestModel() {
            return new SpringAiLlmService("store-test", "test", prompt -> {
                throw new AssertionError("Store tests must run without provider calls");
            });
        }
    }
}
