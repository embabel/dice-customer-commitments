package example.dice.commitments;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.common.Resolutions;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.provenance.ConnectorRef;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;

import static example.dice.commitments.LiveCase.CONTEXT;
import static example.dice.commitments.LiveCase.SOURCE_KINDS;
import static example.dice.commitments.LiveCase.sourceId;

@Service
@Profile("live")
final class LiveIntake {

    private static final int MAX_TITLE_LENGTH = 160;
    private static final int MAX_TEXT_LENGTH = 16_000;
    private static final int MAX_CLAIM_ID_LENGTH = 160;
    private static final String SOURCE_CONNECTOR = "customer-commitments-live";
    private static final String SIGNED_AMENDMENT = "SIGNED_AMENDMENT";
    private static final String SIGNED_AGREEMENT = "SIGNED_AGREEMENT";
    private static final DataDictionary SCHEMA = DataDictionary.fromClasses("customer-commitments", Organisation.class);

    private final LiveCaseStore store;
    private final CommitmentDecision decision;
    private final PropositionPipeline pipeline;

    LiveIntake(LiveCaseStore store, CommitmentDecision decision, Ai ai, LiveModelConfiguration models) {
        this.store = store;
        this.decision = decision;
        this.pipeline = PropositionPipeline.withExtractor(
                LlmPropositionExtractor.builder(LlmOptions.withModel(models.name())).withAi(ai)
                        .withTemplate("dice/extract_commitments"));
    }

    LiveCase.Intake ingest(LiveCase.Upload upload) {
        validate(upload);
        var id = sourceId(upload);
        var prior = priorClaimText(upload.replacesClaimId());
        var scope = SourceAnalysisContext.withContextId(CONTEXT)
                .withEntityResolver((suggestions, schema) -> new Resolutions<>(suggestions.getChunkIds(), List.of()))
                .withSchema(SCHEMA)
                .withSourceLocator(new ConnectorRef(SOURCE_CONNECTOR, id));
        var result = pipeline.processText(upload.text(), id, scope);
        if (result instanceof ChunkPropositionResult.Failed) {
            throw new IllegalStateException("DICE could not extract claims from the submitted source.");
        }
        var propositions = result.getPropositions();
        if (propositions.isEmpty()) {
            throw new IllegalArgumentException("DICE did not find any claims in this source.");
        }
        if (propositions.stream().anyMatch(proposition -> !CONTEXT.equals(proposition.getContextIdValue())
                || !proposition.getGrounding().contains(id) || proposition.getText().isBlank())) {
            throw new IllegalStateException("DICE returned a claim without grounding in the submitted source.");
        }

        var candidates = propositions.stream().map(proposition -> {
            var assessed = decision.assess(upload.kind(), upload.text(), proposition.getText(), prior);
            return new LiveCase.Candidate(proposition, assessed.classification(), assessed.provider());
        }).toList();
        // All provider calls have finished. The store rechecks the selected predecessor in its write transaction.
        return store.save(upload, candidates);
    }

    private String priorClaimText(String predecessorId) {
        if (predecessorId == null) {
            return null;
        }
        return store.read(null).claims().stream()
                .filter(claim -> claim.id().equals(predecessorId) && "ACTIVE".equals(claim.status()))
                .filter(claim -> "AGREED".equals(claim.outcome()) || "REVISED".equals(claim.outcome()))
                .map(LiveCase.Claim::text)
                .findFirst().orElse(null);
    }

    private static void validate(LiveCase.Upload upload) {
        if (upload == null || !nonblankWithin(upload.title(), MAX_TITLE_LENGTH)
                || !nonblankWithin(upload.text(), MAX_TEXT_LENGTH)) {
            throw new IllegalArgumentException("Supply a title of up to 160 characters and source text of up to 16,000 characters.");
        }
        var kind = upload.kind();
        if (kind == null || !SOURCE_KINDS.contains(kind)) {
            throw new IllegalArgumentException("Select a supported source kind.");
        }
        if (upload.signedAttested() && !(SIGNED_AGREEMENT.equals(kind) || SIGNED_AMENDMENT.equals(kind))) {
            throw new IllegalArgumentException("Attestation is available for signed agreements and signed amendments.");
        }
        if (upload.replacesClaimId() != null && (!SIGNED_AMENDMENT.equals(kind)
                || !nonblankWithin(upload.replacesClaimId(), MAX_CLAIM_ID_LENGTH))) {
            throw new IllegalArgumentException("Only a signed amendment can select a commitment to replace.");
        }
    }

    private static boolean nonblankWithin(String value, int limit) {
        return value != null && !value.isBlank() && value.length() <= limit;
    }

    record Organisation(String name) {
    }
}
