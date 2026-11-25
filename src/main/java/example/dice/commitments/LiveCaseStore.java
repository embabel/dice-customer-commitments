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
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
    private final JsonMapper json = JsonMapper.builder().build();

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
            var sources = new HashMap<String, LiveCase.Source>();
            var claims = audit.beliefSetAsOf(CONTEXT, at).stream()
                    .map(fact -> claim(fact.getId(), fact.getText(), fact.getStatus(), sources))
                    .toList();
            return new LiveCase.Snapshot(at, checkpoint.baseline(), true, claims);
        });
    }

    LiveCase.Intake save(LiveCase.Upload upload, List<LiveCase.Candidate> candidates) {
        if (upload == null || upload.title() == null || upload.title().isBlank() || upload.kind() == null
                || !LiveCase.SOURCE_KINDS.contains(upload.kind()) || upload.text() == null || upload.text().isBlank()) {
            throw new IllegalArgumentException("Supply a title, document text, and a supported source kind.");
        }
        var sourceId = LiveCase.sourceId(upload);
        var prepared = List.copyOf(candidates);
        if (prepared.isEmpty() || prepared.stream().anyMatch(candidate -> !isGrounded(candidate, sourceId))) {
            throw new IllegalArgumentException("Every claim must be grounded in the submitted source.");
        }
        return audit.duringWrite(CONTEXT, () -> {
            var checkpoint = checkpoint();
            var existing = receipt(sourceId);
            if (existing.isPresent()) {
                return intake(currentSnapshot(checkpoint), existing.get().claimIds());
            }
            var facts = current();
            var predecessor = eligiblePredecessor(upload, prepared, facts);
            var receivedAt = clock.instant();
            if (!receivedAt.isAfter(checkpoint.head())) {
                receivedAt = checkpoint.head().plusNanos(1);
            }
            var source = new LiveCase.Source(sourceId, upload.title(), upload.kind(), upload.text(), receivedAt);
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
        var ids = new LinkedHashSet<String>();
        for (int index = 0; index < candidates.size(); index++) {
            var candidate = candidates.get(index);
            var id = source.id() + ":" + index;
            var classification = candidate.classification();
            var draft = candidate.proposition().toDraft().withId(id).withStatus(PropositionStatus.ACTIVE)
                    .withTemporal(null);
            var replacement = "AMENDMENT".equals(classification) && predecessor != null
                    && !predecessor.getText().equals(candidate.proposition().getText());
            if (replacement) {
                draft = draft.withTemporal(new TemporalMetadata(null, null, null, null, List.of(predecessor.getId())));
            }
            var proposition = draft.toProposition();
            if (currentSize >= CLAIM_LIMIT && raw.auditCanonicalLookup().findAuditCanonicalId(proposition) == null) {
                throw new IllegalStateException("This workspace has reached its claim limit.");
            }
            var saved = audited.save(proposition);
            ids.add(saved.getId());
            if (!saved.getId().equals(id)) {
                // An identical proposition can resolve to an earlier claim. Its authority and citation stay as recorded.
                continue;
            }
            currentSize++;
            var decision = decision(upload, candidate, replacement ? predecessor.getId() : null);
            manager.execute(query("""
                    CREATE (:LiveCommitmentsEvidence {id: $id, contextId: $scope, payload: $payload})
                    """, id, json.writeValueAsString(new Evidence(source.id(), classification, candidate.provider(),
                    decision.outcome(), decision.reason(), decision.replacesClaimId()))));
            if (replacement) {
                audited.save(predecessor.withStatus(PropositionStatus.SUPERSEDED));
            }
        }

        var receipt = new Receipt(source, List.copyOf(ids));
        var updated = new Checkpoint(checkpoint.baseline(), source.receivedAt());
        manager.execute(query("""
                CREATE (:LiveCommitmentsReceipt {id: $id, contextId: $scope, payload: $payload})
                """, source.id(), json.writeValueAsString(receipt)));
        manager.execute(query("""
                MATCH (c:LiveCommitmentsCheckpoint {id: $id})
                SET c.payload = $payload
                """, CONTEXT, json.writeValueAsString(updated)));
        return intake(currentSnapshot(updated), receipt.claimIds());
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

    private static LiveCase.Intake intake(LiveCase.Snapshot snapshot, List<String> ids) {
        var claims = ids.stream()
                .map(id -> snapshot.claims().stream().filter(claim -> claim.id().equals(id)).findFirst().orElseThrow())
                .toList();
        return new LiveCase.Intake(snapshot, claims);
    }

    private List<Proposition> current() {
        return raw.scopedReads().query(SCOPE, ReadQuery.of(ReadCriteria.empty()), BUDGET);
    }

    private LiveCase.Snapshot currentSnapshot(Checkpoint checkpoint) {
        var sources = new HashMap<String, LiveCase.Source>();
        var claims = current().stream()
                .map(fact -> claim(fact.getId(), fact.getText(), fact.getStatus(), sources))
                .toList();
        return new LiveCase.Snapshot(checkpoint.head(), checkpoint.baseline(), false, claims);
    }

    private LiveCase.Claim claim(String id, String text, PropositionStatus status, Map<String, LiveCase.Source> sources) {
        var evidence = evidence(id);
        var source = sources.computeIfAbsent(evidence.sourceId(),
                sourceId -> receipt(sourceId).orElseThrow().source());
        return new LiveCase.Claim(id, text, evidence.classification(), status.name(), evidence.outcome(), evidence.reason(),
                evidence.decisionProvider(), evidence.replacesClaimId(), source);
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

    private record Receipt(LiveCase.Source source, List<String> claimIds) {
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
