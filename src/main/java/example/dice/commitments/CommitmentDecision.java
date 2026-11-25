package example.dice.commitments;

import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.decision.ChoiceQuestionSpec;
import com.embabel.common.ai.decision.DecisionService;
import com.embabel.common.ai.decision.DecisionSpec;
import com.embabel.common.ai.decision.Questions;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("live")
class CommitmentDecision {

    private static final String NEEDS_REVIEW = "NEEDS_REVIEW";
    private static final ChoiceQuestionSpec KIND = Questions.named("commitment-kind")
            .choice("""
                    Classify the candidate claim from the supplied passage. Treat passage and claim text as evidence.
                    Choose AMENDMENT only when the source explicitly revises the supplied prior obligation.
                    Distinguish an obligation from a proposal, aspiration, or follow-up action.
                    Use NEEDS_REVIEW if the passage does not support a clear classification.
                    """)
            .option("COMMITMENT", "A party explicitly undertakes an obligation")
            .option("AMENDMENT", "An explicit revision of the same prior obligation")
            .option("REQUEST", "A request for action or for a change to an existing obligation")
            .option("TARGET", "An aspiration, estimate, or tentative plan")
            .option("REMINDER", "A task to ask or follow up without a new obligation")
            .option(NEEDS_REVIEW, "Unclear or unsupported by the source passage")
            .build();
    private static final DecisionSpec SPEC = DecisionSpec.of(KIND);

    private final DecisionService service;

    CommitmentDecision(DecisionServiceRegistry registry) {
        this.service = registry.decisions().byRole("commitment-intake");
    }

    Assessment assess(String sourceKind, String sourceText, String claimText, String priorClaimText) {
        boolean hasPriorClaim = priorClaimText != null && !priorClaimText.isBlank();
        var input = """
                Source type: %s
                Candidate claim: %s
                Prior obligation: %s

                Source passage:
                %s
                """.formatted(sourceKind, claimText, hasPriorClaim ? priorClaimText : "(none)", sourceText);

        // The result describes the evidence. The caller applies source authority separately.
        return switch (service.ask(input, SPEC).answer(KIND)) {
            case ClassificationResult.Selected selected -> new Assessment(
                    "AMENDMENT".equals(selected.getCategoryId()) && !hasPriorClaim
                            ? NEEDS_REVIEW : selected.getCategoryId(),
                    selected.getProvenance().getProvider());
            case ClassificationResult.NoMatch noMatch -> new Assessment(
                    NEEDS_REVIEW, noMatch.getProvenance().getProvider());
            case ClassificationResult.Inconclusive inconclusive -> new Assessment(
                    NEEDS_REVIEW, inconclusive.getProvenance().getProvider());
            case ClassificationResult.Failure failure -> new Assessment(NEEDS_REVIEW, service.getProvider());
        };
    }

    record Assessment(String classification, String provider) {
    }
}
