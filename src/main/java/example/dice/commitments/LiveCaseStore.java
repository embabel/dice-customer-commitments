package example.dice.commitments;

import com.embabel.dice.audit.AuditActorRef;
import com.embabel.dice.audit.AuditAttribution;
import com.embabel.dice.audit.AuditFailurePolicy;
import com.embabel.dice.audit.AuditingPropositionRepository;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionRepository;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.storage.DrivineAuditLogStore;
import com.embabel.dice.storage.DrivinePropositionRepository;
import com.embabel.dice.store.ContextReadScope;
import com.embabel.dice.store.ReadBudget;
import com.embabel.dice.store.ReadCriteria;
import com.embabel.dice.store.ReadQuery;
import com.embabel.dice.store.ScopedReadSpec;
import com.embabel.dice.temporal.TemporalMetadata;
import org.drivine.manager.PersistenceManager;
import org.drivine.query.CypherStatement;
import org.drivine.query.QuerySpecification;
import org.drivine.schema.SchemaCatalog;
import org.drivine.schema.UniquenessConstraintSpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static example.dice.commitments.LiveCase.CONTEXT;

@Component
@Profile("live")
final class LiveCaseStore {

    private static final int CLAIM_LIMIT = 500;
    private static final ScopedReadSpec SCOPE = ScopedReadSpec.of(ContextReadScope.ofValues(CONTEXT));
    private static final ReadBudget BUDGET = ReadBudget.ofRows(CLAIM_LIMIT);
    private static final Set<String> CLASSIFICATIONS = Set.of(
            "COMMITMENT", "AMENDMENT", "REQUEST", "TARGET", "REMINDER", "NEEDS_REVIEW");
    private static final Set<String> SIGNED_OUTCOMES = Set.of("AGREED", "REVISED");

    private final DrivinePropositionRepository raw;
    private final PropositionRepository audited;
    private final DrivineAuditLogStore audit;
    private final PersistenceManager manager;
    private final ReceiptClock clock = new ReceiptClock(Clock.systemUTC(), new ThreadLocal<>());
    // Legacy receipt payloads have no signedAttested field. Only the store mapper defaults it to false.
    private final JsonMapper json = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();

    LiveCaseStore(PropositionRepository repository, PersistenceManager manager, PlatformTransactionManager transactions) {
        if (!(repository instanceof DrivinePropositionRepository drivine)) {
            throw new IllegalStateException("Live commitments require the DICE SQLite store");
        }
        this.manager = manager;
        this.raw = drivine;
        this.audit = new DrivineAuditLogStore(manager, transactions, clock);
        this.audited = AuditingPropositionRepository.wrapping(raw, audit,
                context -> new AuditAttribution(new AuditActorRef("live:agreement-operator"), null, "source-intake"),
                AuditFailurePolicy.FAIL_WRITE, raw.auditCanonicalLookup());
    }

    @Bean
    static SchemaCatalog liveCommitmentsCatalog() {
        return SchemaCatalog.of(List.of(
                new UniquenessConstraintSpec("LiveCommitmentsCheckpoint", "id"),
                new UniquenessConstraintSpec("LiveCommitmentsReceipt", "id"),
                new UniquenessConstraintSpec("LiveCommitmentsEvidence", "id")))
                .forDatabase("local");
    }

    LiveCase.Snapshot read(Instant at) {
        return audit.duringWrite(CONTEXT, () -> {
            var checkpoint = checkpoint();
            if (at == null) {
                return currentSnapshot(checkpoint);
            }
            // A deduplicated receipt advances the workspace checkpoint without adding an audit event.
            // Include that committed checkpoint in DICE's observed time if the wall clock moved backward.
            var now = clock.instant();
            var observedThrough = checkpoint.head().isAfter(now) ? checkpoint.head() : now;
            var facts = clock.at(observedThrough, () -> audit.beliefSetAsOf(CONTEXT, at)).stream()
                    .map(fact -> new Fact(fact.getId(), fact.getText(), fact.getStatus()))
                    .toList();
            return snapshot(at, checkpoint.baseline(), true, facts);
        });
    }

    Optional<LiveCase.Intake> findIntake(String sourceId) {
        return audit.duringWrite(CONTEXT, () -> receipt(sourceId)
                .map(existing -> intake(currentSnapshot(checkpoint()), existing)));
    }

    LiveCase.Intake save(LiveCase.Upload upload, List<LiveCase.Candidate> candidates) {
        if (upload == null || upload.title() == null || upload.title().isBlank() || upload.kind() == null
                || !LiveCase.SOURCE_KINDS.contains(upload.kind()) || upload.text() == null || upload.text().isBlank()) {
            throw new IllegalArgumentException("Supply a title, document text, and a supported source kind.");
        }
        var sourceId = LiveCase.sourceId(upload);
        var supplied = List.copyOf(candidates);
        if (supplied.isEmpty() || supplied.stream().anyMatch(candidate -> !isGrounded(candidate, sourceId))) {
            throw new IllegalArgumentException("Every claim must be grounded in the submitted source.");
        }
        var prepared = uniqueAssessments(supplied);
        return audit.duringWrite(CONTEXT, () -> {
            var checkpoint = checkpoint();
            var existing = receipt(sourceId);
            if (existing.isPresent()) {
                return intake(currentSnapshot(checkpoint), existing.get());
            }
            var facts = current();
            var predecessor = eligiblePredecessor(upload, prepared, facts);
            var receivedAt = clock.instant();
            if (!receivedAt.isAfter(checkpoint.head())) {
                receivedAt = checkpoint.head().plusNanos(1);
            }
            var source = new LiveCase.Source(sourceId, upload.title(), upload.kind(), upload.text(), receivedAt,
                    upload.signedAttested(), upload.replacesClaimId());
            return clock.at(receivedAt, () -> write(source, upload, prepared, predecessor, facts.size(), checkpoint));
        });
    }

    private static boolean isGrounded(LiveCase.Candidate candidate, String sourceId) {
        var proposition = candidate.proposition();
        return proposition != null && CONTEXT.equals(proposition.getContextIdValue())
                && proposition.getGrounding().contains(sourceId) && !proposition.getText().isBlank()
                && candidate.classification() != null && CLASSIFICATIONS.contains(candidate.classification())
                && candidate.provider() != null && !candidate.provider().isBlank();
    }

    private static List<LiveCase.Candidate> uniqueAssessments(List<LiveCase.Candidate> candidates) {
        var assessments = new LinkedHashMap<AssessmentKey, LiveCase.Candidate>();
        for (var candidate : candidates) {
            var key = new AssessmentKey(candidate.proposition().getText(), candidate.classification(), candidate.provider());
            assessments.putIfAbsent(key, candidate);
        }
        return List.copyOf(assessments.values());
    }

    private Proposition eligiblePredecessor(LiveCase.Upload upload, List<LiveCase.Candidate> candidates,
                                            List<Proposition> facts) {
        if (!upload.signedAttested() || !"SIGNED_AMENDMENT".equals(upload.kind()) || upload.replacesClaimId() == null
                || candidates.stream().filter(candidate -> "AMENDMENT".equals(candidate.classification())).count() != 1) {
            return null;
        }

        return facts.stream()
                .filter(fact -> fact.getId().equals(upload.replacesClaimId()) && fact.getStatus() == PropositionStatus.ACTIVE)
                .filter(fact -> {
                    var previous = evidence(fact.getId());
                    return SIGNED_OUTCOMES.contains(previous.outcome());
                })
                .findFirst().orElse(null);
    }

    private LiveCase.Intake write(LiveCase.Source source, LiveCase.Upload upload, List<LiveCase.Candidate> candidates,
                                  Proposition predecessor, int currentSize, Checkpoint checkpoint) {
        var classifications = new HashMap<String, Set<String>>();
        for (var candidate : candidates) {
            classifications.computeIfAbsent(candidate.proposition().getText(), text -> new HashSet<>())
                    .add(candidate.classification());
        }
        var observations = new ArrayList<Observation>();
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            var id = source.id() + ":" + index;
            var classification = candidate.classification();
            var conflicting = classifications.get(candidate.proposition().getText()).size() > 1;
            var draft = candidate.proposition().toDraft().withId(id).withStatus(PropositionStatus.ACTIVE)
                    .withTemporal(null);
            var replacement = !conflicting && "AMENDMENT".equals(classification) && predecessor != null
                    && !predecessor.getText().equals(candidate.proposition().getText());
            if (replacement) {
                draft = draft.withTemporal(new TemporalMetadata(null, null, null, null, List.of(predecessor.getId())));
            }
            var proposition = draft.toProposition();
            if (currentSize >= CLAIM_LIMIT && raw.auditCanonicalLookup().findAuditCanonicalId(proposition) == null) {
                throw new IllegalStateException("This workspace has reached its claim limit.");
            }
            var saved = audited.save(proposition);
            var duplicate = !saved.getId().equals(id);
            var proposed = decision(upload, candidate, replacement ? predecessor.getId() : null);
            var applied = outcome(upload, conflicting, duplicate, proposed);
            observations.add(new Observation(saved.getId(), saved.getText(), classification, candidate.provider(), applied));
            if (duplicate) {
                // The receipt describes this source. The canonical claim keeps its original authority and citation.
                continue;
            }
            currentSize++;
            manager.execute(query("""
                    CREATE (:LiveCommitmentsEvidence {id: $id, contextId: $scope, payload: $payload})
                    """, id, json.writeValueAsString(new Evidence(source.id(), classification, candidate.provider(),
                    applied.outcome(), applied.reason(), applied.replacesClaimId()))));
            if (replacement) {
                audited.save(predecessor.withStatus(PropositionStatus.SUPERSEDED));
            }
        }

        var receipt = new Receipt(source, List.copyOf(observations));
        var updated = new Checkpoint(checkpoint.baseline(), source.receivedAt());
        manager.execute(query("""
                CREATE (:LiveCommitmentsReceipt {id: $id, contextId: $scope, payload: $payload})
                """, source.id(), json.writeValueAsString(receipt)));
        manager.execute(query("""
                MATCH (c:LiveCommitmentsCheckpoint {id: $id})
                SET c.payload = $payload
                """, CONTEXT, json.writeValueAsString(updated)));
        return intake(currentSnapshot(updated), receipt);
    }

    private static Decision outcome(LiveCase.Upload upload, boolean conflicting, boolean duplicate, Decision proposed) {
        if (conflicting) {
            return new Decision("REVIEW_NEEDED", "The model assigned conflicting classifications to this claim.", null);
        }
        if (!duplicate) {
            return proposed;
        }
        var signedSource = "SIGNED_AGREEMENT".equals(upload.kind()) || "SIGNED_AMENDMENT".equals(upload.kind());
        if (signedSource || !"RECORDED".equals(proposed.outcome())) {
            return new Decision("REVIEW_NEEDED",
                    "This assessment matches an existing claim. The submitted source was recorded for review and the agreement stayed as recorded.",
                    null);
        }
        return new Decision("RECORDED",
                "This assessment matches an existing claim. The submitted source was recorded with the existing claim as its reference.",
                null);
    }

    private static Decision decision(LiveCase.Upload upload, LiveCase.Candidate candidate, String replacementId) {
        if (replacementId != null) {
            return new Decision("REVISED", "The operator attested to the signed amendment and selected an active commitment.",
                    replacementId);
        }
        return switch (candidate.classification()) {
            case "COMMITMENT" -> upload.signedAttested() && "SIGNED_AGREEMENT".equals(upload.kind())
                    ? new Decision("AGREED", "The operator attested to a signed agreement.", null)
                    : new Decision("REVIEW_NEEDED", "A commitment needs a signed agreement attested by the operator.", null);
            case "AMENDMENT" -> new Decision("REVIEW_NEEDED",
                    "An amendment needs an attested signed source, one replacement claim, and an active signed obligation.", null);
            case "NEEDS_REVIEW" -> new Decision("REVIEW_NEEDED", "The decision model could not classify this claim.", null);
            default -> new Decision("RECORDED", "This claim adds context to the agreement.", null);
        };
    }

    private static LiveCase.Intake intake(LiveCase.Snapshot snapshot, Receipt receipt) {
        var canonical = new HashMap<String, LiveCase.Claim>();
        snapshot.claims().forEach(claim -> canonical.put(claim.id(), claim));
        var claims = receipt.observations().stream().map(observation -> {
            var claim = canonical.get(observation.id());
            var decision = observation.decision();
            return new LiveCase.Claim(claim.id(), observation.textOr(claim.text()), observation.classification(),
                    claim.status(), decision.outcome(), decision.reason(), observation.provider(),
                    decision.replacesClaimId(), receipt.source());
        }).toList();
        return new LiveCase.Intake(snapshot, claims);
    }

    private List<Proposition> current() {
        return raw.scopedReads().query(SCOPE, ReadQuery.of(ReadCriteria.empty()), BUDGET);
    }

    private LiveCase.Snapshot currentSnapshot(Checkpoint checkpoint) {
        var facts = current().stream().map(fact -> new Fact(fact.getId(), fact.getText(), fact.getStatus())).toList();
        return snapshot(checkpoint.head(), checkpoint.baseline(), false, facts);
    }

    private LiveCase.Snapshot snapshot(Instant at, Instant baseline, boolean historical, List<Fact> facts) {
        var receipts = receiptsThrough(at);
        var sources = new HashMap<String, LiveCase.Source>();
        receipts.forEach(receipt -> sources.put(receipt.source().id(), receipt.source()));

        var canonicalText = new HashMap<String, String>();
        for (var fact : facts) {
            canonicalText.put(fact.id(), fact.text());
        }
        var claims = facts.stream().map(fact -> claim(fact, sources)).toList();
        var reviews = receipts.stream().map(receipt -> review(receipt, canonicalText)).toList();
        return new LiveCase.Snapshot(at, baseline, historical, claims, reviews);
    }

    private LiveCase.Claim claim(Fact fact, Map<String, LiveCase.Source> sources) {
        var evidence = evidence(fact.id());
        var source = Objects.requireNonNull(sources.get(evidence.sourceId()), "Claim source is missing at this checkpoint");
        return new LiveCase.Claim(fact.id(), fact.text(), evidence.classification(), fact.status().name(),
                evidence.outcome(), evidence.reason(), evidence.decisionProvider(), evidence.replacesClaimId(), source);
    }

    private static LiveCase.ReviewReceipt review(Receipt receipt, Map<String, String> canonicalText) {
        var decisions = receipt.observations().stream().map(observation -> {
            var decision = observation.decision();
            return new LiveCase.ReviewDecision(observation.id(), observation.textOr(canonicalText.get(observation.id())),
                    observation.classification(), decision.outcome(), decision.reason(), observation.provider());
        }).toList();
        return new LiveCase.ReviewReceipt(receipt.source(), decisions);
    }

    private Checkpoint checkpoint() {
        return manager.optionalGetOne(query("""
                MATCH (c:LiveCommitmentsCheckpoint {id: $id}) RETURN c.payload AS payload
                """, CONTEXT, "").transform(String.class))
                .map(value -> json.readValue(value, Checkpoint.class))
                .orElseGet(() -> {
                    var baseline = clock.instant();
                    audit.initializeBaseline(CONTEXT, baseline, List.of());
                    var checkpoint = new Checkpoint(baseline, baseline);
                    manager.execute(query("""
                            CREATE (:LiveCommitmentsCheckpoint {id: $id, payload: $payload})
                            """, CONTEXT, json.writeValueAsString(checkpoint)));
                    return checkpoint;
                });
    }

    private List<Receipt> receiptsThrough(Instant at) {
        return manager.query(query("""
                MATCH (r:LiveCommitmentsReceipt {contextId: $scope}) RETURN r.payload AS payload
                """, CONTEXT, "").transform(String.class)).stream()
                .map(value -> json.readValue(value, Receipt.class))
                .filter(receipt -> !receipt.source().receivedAt().isAfter(at))
                .sorted(Comparator.comparing(receipt -> receipt.source().receivedAt()))
                .toList();
    }

    private Optional<Receipt> receipt(String id) {
        return manager.optionalGetOne(query("""
                MATCH (r:LiveCommitmentsReceipt {id: $id, contextId: $scope}) RETURN r.payload AS payload
                """, id, "").transform(String.class)).map(value -> json.readValue(value, Receipt.class));
    }

    private Evidence evidence(String id) {
        return manager.optionalGetOne(query("""
                MATCH (e:LiveCommitmentsEvidence {id: $id, contextId: $scope}) RETURN e.payload AS payload
                """, id, "").transform(String.class)).map(value -> json.readValue(value, Evidence.class)).orElseThrow();
    }

    private static QuerySpecification<Object> query(String cypher, String id, String payload) {
        return QuerySpecification.withStatement(new CypherStatement(cypher))
                .bind(Map.of("id", id, "scope", CONTEXT, "payload", payload));
    }

    private record Decision(String outcome, String reason, String replacesClaimId) {
    }

    private record AssessmentKey(String text, String classification, String provider) {
    }

    private record Observation(String id, String text, String classification, String provider, Decision decision) {
        String textOr(String canonicalText) {
            // Receipts written before this field was introduced refer to immutable canonical claim text.
            return text == null ? Objects.requireNonNull(canonicalText, "Receipt claim is missing") : text;
        }
    }

    private record Fact(String id, String text, PropositionStatus status) {
    }

    private record Receipt(LiveCase.Source source, List<Observation> observations) {
    }

    private record Evidence(String sourceId, String classification, String decisionProvider, String outcome,
                            String reason, String replacesClaimId) {
    }

    private record Checkpoint(Instant baseline, Instant head) {
    }

    /** Audit appends sample this clock independently, even when they share a database transaction. */
    private static final class ReceiptClock extends Clock {
        private final Clock wallClock;
        private final ThreadLocal<Instant> receipt;

        private ReceiptClock(Clock wallClock, ThreadLocal<Instant> receipt) {
            this.wallClock = wallClock;
            this.receipt = receipt;
        }

        @Override
        public ZoneId getZone() {
            return wallClock.getZone();
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new ReceiptClock(wallClock.withZone(zone), receipt);
        }

        @Override
        public Instant instant() {
            var pinned = receipt.get();
            return pinned == null ? wallClock.instant() : pinned;
        }

        <T> T at(Instant instant, Supplier<T> action) {
            receipt.set(instant);
            try {
                return action.get();
            } finally {
                receipt.remove();
            }
        }
    }
}
