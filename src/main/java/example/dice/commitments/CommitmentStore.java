package example.dice.commitments;

import com.embabel.dice.audit.AuditActorRef;
import com.embabel.dice.audit.AuditAttribution;
import com.embabel.dice.audit.AuditFailurePolicy;
import com.embabel.dice.audit.AuditingPropositionRepository;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionQuery;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static example.dice.commitments.CaseFile.CONTEXT;

final class CommitmentStore {

    private static final ReadBudget BUDGET = ReadBudget.ofRows(120);
    private final DrivinePropositionRepository raw;
    private final PropositionRepository audited;
    private final DrivineAuditLogStore audit;
    private final PersistenceManager manager;

    CommitmentStore(DrivinePropositionRepository raw, DrivineAuditLogStore audit, PersistenceManager manager) {
        this.raw = raw;
        this.audit = audit;
        this.manager = manager;
        this.audited = AuditingPropositionRepository.wrapping(raw, audit,
                context -> new AuditAttribution(new AuditActorRef("fixture:agreement-owner"), null, "signed-amendment"),
                AuditFailurePolicy.FAIL_WRITE, raw.auditCanonicalLookup());
    }

    void initialize(Supplier<List<Proposition>> openingFacts) {
        if (hasBaseline()) {
            return;
        }
        // Extract outside the write fence. A second concurrent opener may win the baseline.
        var opening = openingFacts.get();
        audit.duringWrite(CONTEXT, () -> {
            if (!hasBaseline()) {
                opening.forEach(raw::save);
                var complete = raw.query(PropositionQuery.againstContext(CONTEXT));
                audit.initializeBaseline(CONTEXT, StoreConfiguration.OPENING, complete);
                manager.execute(query("""
                        CREATE (:CommitmentsBaseline {id: $scope})
                        """));
            }
            return null;
        });
    }

    void amend(Proposition replacement, String originalId) {
        audit.duringWrite(CONTEXT, () -> {
            var facts = current();
            if (facts.stream().noneMatch(fact -> replacement.getId().equals(fact.getId()))) {
                var original = facts.stream().filter(fact -> originalId.equals(fact.getId()))
                        .findFirst().orElseThrow();
                if (original.getStatus() != PropositionStatus.ACTIVE) {
                    throw new IllegalStateException("The signed original is not active");
                }
                audited.save(original.withStatus(PropositionStatus.SUPERSEDED));
                var temporal = new TemporalMetadata(null, null, null, null, List.of(originalId));
                audited.save(replacement.toDraft().withTemporal(temporal).toProposition());
            }
            return null;
        });
    }

    List<Fact> read(Instant at) {
        if (at == null) {
            return current().stream().map(fact -> new Fact(fact.getId(), fact.getText(), fact.getStatus(),
                    fact.getConfidence())).toList();
        }
        return audit.beliefSetAsOf(CONTEXT, at).stream()
                .map(fact -> new Fact(fact.getId(), fact.getText(), fact.getStatus(), fact.getConfidence()))
                .toList();
    }

    private List<Proposition> current() {
        var scope = ScopedReadSpec.of(ContextReadScope.ofValues(CONTEXT));
        return raw.scopedReads().query(scope, ReadQuery.of(ReadCriteria.empty()), BUDGET);
    }

    private boolean hasBaseline() {
        return manager.optionalGetOne(query("""
                MATCH (b:CommitmentsBaseline {id: $scope}) RETURN b.id AS id
                """).transform(String.class)).isPresent();
    }

    private static QuerySpecification<Object> query(String cypher) {
        return QuerySpecification.withStatement(new CypherStatement(cypher)).bind(Map.of("scope", CONTEXT));
    }

    record Fact(String id, String text, PropositionStatus status, double confidence) {
    }
}
