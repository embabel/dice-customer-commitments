package example.dice.commitments;

import com.embabel.agent.api.common.Ai;
import com.embabel.agent.core.DataDictionary;
import com.embabel.agent.rag.service.support.InMemoryNamedEntityDataRepository;
import com.embabel.common.ai.model.LlmOptions;
import com.embabel.dice.common.SourceAnalysisContext;
import com.embabel.dice.common.resolver.EscalatingEntityResolver;
import com.embabel.dice.pipeline.ChunkPropositionResult;
import com.embabel.dice.pipeline.PropositionPipeline;
import com.embabel.dice.proposition.Proposition;
import com.embabel.dice.proposition.PropositionStatus;
import com.embabel.dice.proposition.extraction.LlmPropositionExtractor;
import com.embabel.dice.provenance.ConnectorRef;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static example.dice.commitments.CaseFile.CONTEXT;

@Service
class Commitments {

    private static final Pattern DATE = Pattern.compile("\\b(20[0-9]{2}-[0-9]{2}-[0-9]{2})\\b");
    private static final DataDictionary SCHEMA = DataDictionary.fromClasses("commitments", Organisation.class);
    private final CommitmentStore store;
    private final PropositionPipeline extraction;

    Commitments(CommitmentStore store, Ai ai) {
        this.store = store;
        this.extraction = PropositionPipeline.withExtractor(
                LlmPropositionExtractor.builder(LlmOptions.withDefaultLlm()).withAi(ai));
    }

    View view(Instant at) {
        initialize();
        var claims = store.read(at).stream().map(Commitments::claim).toList();
        var amended = claims.stream().anyMatch(claim -> claim.source().equals(CaseFile.AMENDMENT));
        var asOf = at == null ? (amended ? StoreConfiguration.AMENDMENT : StoreConfiguration.OPENING) : at;
        return new View(asOf, StoreConfiguration.OPENING, at != null, amended, claims);
    }

    View amend() {
        initialize();
        var replacement = extract(CaseFile.AMENDMENT);
        if (replacement.size() != 1 || kind(replacement.getFirst().getText()) != Kind.MIGRATION) {
            throw new IllegalStateException("The signed amendment did not yield exactly one migration commitment");
        }
        var original = store.read(null).stream().map(Commitments::claim)
                .filter(claim -> claim.kind() == Kind.MIGRATION && claim.source().equals(CaseFile.ADDENDUM))
                .findFirst().orElseThrow();
        store.amend(replacement.getFirst(), original.id());
        return view(null);
    }

    Answer ask(String question, Instant at) {
        var selected = view(at);
        var token = question.strip().toLowerCase(Locale.ROOT).replaceFirst("[?.!]$", "").strip();
        var migration = selected.active(Kind.MIGRATION);
        var security = selected.active(Kind.SECURITY);
        return switch (token) {
            case "what did we promise", "what is the current promise", "what is the current agreement",
                 "when is migration", "when is the migration", "current promise" ->
                    new Answer(true, "Rivet Systems promised to migrate Tandem’s depot accounts by "
                            + readable(migration.valueDate()) + ". Tandem Transit promised to deliver the SSO security checklist by "
                            + readable(security.valueDate()) + ".", List.of(migration.id(), security.id()), selected);
            case "what changed", "what did the amendment change" -> change(selected, migration, security);
            case "what's open", "what is open", "what’s open", "what is still open", "what needs following up" -> {
                var task = selected.active(Kind.TASK);
                var runbook = selected.active(Kind.SPOKEN);
                yield new Answer(true, "Maya promised the migration runbook by " + readable(runbook.valueDate())
                        + ". Her reminder to ask about a Saturday cutover is still a task. The handoff records no agreed Saturday date.",
                        List.of(runbook.id(), task.id()), selected);
            }
            default -> new Answer(false, "This demo can answer what was promised, what changed, or what’s open.",
                    List.of(), selected);
        };
    }

    private static Answer change(View selected, Claim migration, Claim security) {
        if (!selected.amended()) {
            return new Answer(true, "The signed opening agreement sets migration for "
                    + readable(migration.valueDate()) + ". The customer’s earlier date is a request. The agreement in this view has no signed amendment.",
                    List.of(migration.id(), selected.active(Kind.REQUEST).id()), selected);
        }
        var original = selected.claims().stream()
                .filter(claim -> claim.kind() == Kind.MIGRATION && claim.status() == PropositionStatus.SUPERSEDED)
                .findFirst().orElseThrow();
        return new Answer(true, "The signed amendment moved migration from " + readable(original.valueDate())
                + " to " + readable(migration.valueDate()) + ". Tandem’s security checklist is still due "
                + readable(security.valueDate()) + ".", List.of(migration.id(), original.id(), security.id()), selected);
    }

    private static String readable(LocalDate date) {
        return date.format(java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH));
    }

    private void initialize() {
        store.initialize(() -> CaseFile.opening().stream().flatMap(passage -> extract(passage).stream()).toList());
    }

    private List<Proposition> extract(CaseFile.Passage passage) {
        var scope = SourceAnalysisContext.withContextId(CONTEXT)
                .withEntityResolver(EscalatingEntityResolver.withoutVector(new InMemoryNamedEntityDataRepository(SCHEMA)))
                .withSchema(SCHEMA)
                .withSourceLocator(new ConnectorRef("customer-commitments", passage.id()));
        var result = extraction.processText(passage.passage(), passage.id(), scope);
        if (result instanceof ChunkPropositionResult.Failed failed) {
            throw new IllegalStateException("DICE could not extract " + passage.id(), failed.getCause());
        }

        var grounded = result.getPropositions();
        if (grounded.isEmpty()) {
            throw new IllegalStateException("The recorded passage yielded no propositions");
        }
        var stable = new ArrayList<Proposition>();
        for (int i = 0; i < grounded.size(); i++) {
            var proposition = grounded.get(i);
            if (!proposition.getGrounding().contains(passage.id())) {
                throw new IllegalStateException("DICE did not ground this proposition in its exact passage");
            }
            var type = kind(proposition.getText());
            if (type.authority != passage.authority()) {
                throw new IllegalStateException("The extracted claim does not match its source authority");
            }
            stable.add(proposition.toDraft().withId(id(passage, i)).toProposition());
        }
        return List.copyOf(stable);
    }

    private static Claim claim(CommitmentStore.Fact fact) {
        // Audit snapshots retain tracked proposition fields. IDs point back to the immutable fixture.
        var source = CaseFile.passages().stream()
                .filter(passage -> fact.id().startsWith(CONTEXT + ":" + passage.id() + ":"))
                .findFirst().orElseThrow();
        var kind = kind(fact.text());
        var found = DATE.matcher(fact.text());
        var date = found.find() ? LocalDate.parse(found.group()) : null;
        return new Claim(fact.id(), kind, fact.status(), fact.text(), date, source);
    }

    private static String id(CaseFile.Passage passage, int index) {
        return CONTEXT + ":" + passage.id() + ":" + index;
    }

    private static Kind kind(String statement) {
        return java.util.Arrays.stream(Kind.values()).filter(kind -> statement.startsWith(kind.prefix))
                .findFirst().orElseThrow(() -> new IllegalStateException("Unrecognized recorded proposition"));
    }

    enum Kind {
        MIGRATION("Rivet Systems commits to migrate", CaseFile.Authority.SIGNED),
        SECURITY("Tandem Transit commits to deliver", CaseFile.Authority.SIGNED),
        TARGET("Rivet Systems targets", CaseFile.Authority.SALES),
        REQUEST("Tandem Transit requests", CaseFile.Authority.CUSTOMER),
        SPOKEN("Maya Chen commits", CaseFile.Authority.ASSISTANT),
        TASK("Maya Chen has a reminder", CaseFile.Authority.ASSISTANT);

        private final String prefix;
        private final CaseFile.Authority authority;

        Kind(String prefix, CaseFile.Authority authority) {
            this.prefix = prefix;
            this.authority = authority;
        }
    }

    record View(Instant asOf, Instant baseline, boolean historical, boolean amended, List<Claim> claims) {
        Claim active(Kind kind) {
            return claims.stream().filter(claim -> claim.kind() == kind && claim.status() == PropositionStatus.ACTIVE)
                    .findFirst().orElseThrow();
        }
    }

    record Claim(String id, Kind kind, PropositionStatus status, String statement, LocalDate valueDate,
                 CaseFile.Passage source) {
    }

    record Organisation(String name) {
    }

    record Answer(boolean supported, String text, List<String> citations, View view) {
    }
}
