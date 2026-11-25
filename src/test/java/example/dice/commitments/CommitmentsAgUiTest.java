package example.dice.commitments;

import com.embabel.dice.audit.AuditCoverageException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * R1: Accept bounded AG-UI input with a final user turn and server-selected agreement state.
 * R2: Accept JSON sources within the public title and text limits.
 * E1: Emit AG-UI frames in order, or finish with one safe terminal error.
 * I1: Stream citations and evidence from one snapshot, even if the current record changes.
 * L1: Interpret read and run historical selectors consistently.
 */
class CommitmentsAgUiTest {

    private static final String AGREEMENT = "/api/live/commitments";
    private static final Instant OPENING = Instant.parse("2026-11-18T16:30:00Z");
    private static final Instant AMENDED = Instant.parse("2026-11-21T16:30:00Z");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void e1AndI1StreamOneSnapshotAndCitationsWhenCurrentAgreementChanges() throws Exception {
        var current = new AtomicReference<>(snapshot(OPENING, "claim-1", false));
        var reads = new AtomicInteger();
        var mvc = mvc(at -> {
            assertThat(at).isNull();
            reads.incrementAndGet();
            return current.get();
        }, upload -> { throw new AssertionError("The answer must not change evidence"); }, (turns, evidence) -> {
            current.set(snapshot(AMENDED, "claim-2", false));
            assertThat(turns).extracting(LiveCommitmentsAgent.Turn::content)
                    .containsExactly("When did we agree to migrate?", "November 25.", "Which source confirms that?");
            assertThat(evidence.evidence()).extracting(LiveCommitmentsAgent.Evidence::id)
                    .containsExactly("claim-1");
            return new LiveCommitmentsAgent.Answer("The signed addendum says November 25.", List.of("claim-1"));
        });

        var events = run(mvc, runInput(null));
        assertThat(reads).hasValue(1);
        assertThat(events).extracting(event -> event.get("type").asText()).containsExactly(
                "RUN_STARTED", "STATE_SNAPSHOT", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
                "TEXT_MESSAGE_END", "CUSTOM", "RUN_FINISHED");
        assertThat(events.getFirst().get("threadId").asText()).isEqualTo("thread-1");
        assertThat(events.getLast().get("runId").asText()).isEqualTo("run-1");
        var snapshot = events.get(1).get("snapshot");
        assertThat(snapshot.get("asOf").asText()).isEqualTo(OPENING.toString());
        var claim = snapshot.get("claims").get(0);
        assertThat(claim.get("id").asText()).isEqualTo("claim-1");
        assertThat(claim.get("source").get("text").asText()).isEqualTo("The signed source records the date.");
        assertThat(claim.get("source").get("receivedAt").asText()).isEqualTo(OPENING.toString());
        var messageId = events.get(2).get("messageId").asText();
        assertThat(events.get(2).get("role").asText()).isEqualTo("assistant");
        assertThat(events.get(3).get("messageId").asText()).isEqualTo(messageId);
        assertThat(events.get(3).get("delta").asText()).contains("November 25");
        assertThat(events.get(4).get("messageId").asText()).isEqualTo(messageId);
        var citations = events.get(5);
        assertThat(citations.get("name").asText()).isEqualTo("commitments.citations");
        assertThat(citations.get("value").get("messageId").asText()).isEqualTo(messageId);
        assertThat(citations.get("value").get("ids").get(0).asText()).isEqualTo("claim-1");
    }

    @Test
    void e1EndsWithASafeRunErrorForModelFailureOrForeignCitation() throws Exception {
        var failures = List.<BiFunction<List<LiveCommitmentsAgent.Turn>, LiveCommitmentsAgent.EvidenceView,
                LiveCommitmentsAgent.Answer>>of(
                (turns, view) -> { throw new IllegalStateException("private provider details"); },
                (turns, view) -> new LiveCommitmentsAgent.Answer("A later claim", List.of("foreign-id")));
        for (var failure : failures) {
            var events = run(mvc(at -> snapshot(OPENING, "claim-1", false), unusedIntake(), failure), runInput(null));
            assertThat(events).extracting(event -> event.get("type").asText()).containsExactly(
                    "RUN_STARTED", "STATE_SNAPSHOT", "RUN_ERROR");
            assertThat(events.getLast().toString()).doesNotContain("private provider details", "foreign-id");
            assertThat(events.getLast().get("message").asText()).contains("try again");
        }
    }

    @Test
    void r1RejectsUntrustedRolesAndMalformedStateBeforeReadingTheAgreement() throws Exception {
        var mvc = mvc(at -> { throw new AssertionError("Invalid runs must not read the agreement"); },
                unusedIntake(), unusedAnswer());
        var normal = runInput(null);
        var rejected = List.of(
                normal.replace("\"role\":\"assistant\"", "\"role\":\"system\""),
                normal.replace("\"id\":\"m2\"", "\"id\":\"m1\""),
                runInput("Tuesday"),
                normal.replace("\"at\":null", "\"at\":500"),
                normal.replace("\"at\":null", "\"historical\":true"),
                normal.replace("\"at\":null", "\"historical\":true,\"asOf\":500"),
                normal.replace("\"at\":null", "\"historical\":true,\"asOf\":\"Tuesday\""),
                normal.replace("\"at\":null", "\"historical\":\"true\""),
                normal.replace("Which source confirms that?", "x".repeat(8001)),
                normal.replace("Which source confirms that?", " "),
                normal.replace("\"context\":[]", "\"context\":[{\"description\":\"client claim\"}]"));
        for (var body : rejected) {
            var result = mvc.perform(post(AGREEMENT + "/agent").contentType(MediaType.APPLICATION_JSON)
                            .accept(MediaType.TEXT_EVENT_STREAM).content(body))
                    .andExpect(status().isBadRequest()).andExpect(request().asyncNotStarted()).andReturn();
            assertThat(result.getResponse().getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            assertThat(result.getResponse().getContentAsString()).doesNotContain("RUN_STARTED");
        }
    }

    @Test
    void r2OpensTheLivePageAndAcceptsOnlyBoundedJsonSources() throws Exception {
        var imported = new AtomicReference<LiveCase.Upload>();
        var mvc = mvc(at -> snapshot(OPENING, "claim-1", false), upload -> {
            imported.set(upload);
            return new LiveCase.Intake(snapshot(OPENING, "claim-1", false), List.of());
        }, unusedAnswer());
        mvc.perform(get("/")).andExpect(status().isFound()).andExpect(header().string("Location", "/live.html"));
        var body = """
                {"title":"Signed launch","kind":"SIGNED_AGREEMENT","text":"Go live on November 25.",
                 "signedAttested":true}
                """;
        mvc.perform(post(AGREEMENT + "/sources").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        assertThat(imported.get().title()).isEqualTo("Signed launch");
        assertThat(imported.get().signedAttested()).isTrue();

        mvc.perform(post(AGREEMENT + "/sources").contentType(MediaType.TEXT_PLAIN).content(body))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post(AGREEMENT + "/sources").contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("Signed launch", "x".repeat(161))))
                .andExpect(status().isBadRequest());
        mvc.perform(post(AGREEMENT + "/sources").contentType(MediaType.APPLICATION_JSON)
                        .content(body.replace("Go live on November 25.", "x".repeat(16001))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void l1AppliesTheSameHistoricalSelectorAndCoverageToReadAndRun() throws Exception {
        var mvc = mvc(at -> {
            if (OPENING.equals(at)) {
                return snapshot(at, "claim-1", true);
            }
            throw new AuditCoverageException();
        }, unusedIntake(), (turns, view) -> {
            assertThat(view.historical()).isTrue();
            assertThat(view.asOf()).isEqualTo(OPENING);
            return new LiveCommitmentsAgent.Answer("The opening agreement.", List.of("claim-1"));
        });

        var record = mvc.perform(get(AGREEMENT).queryParam("at", OPENING.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var events = run(mvc, runInput(OPENING.toString()));
        assertThat(events.get(1).get("snapshot")).isEqualTo(JSON.readTree(record));
        mvc.perform(get(AGREEMENT).queryParam("at", AMENDED.toString()))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(post(AGREEMENT + "/agent").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(runInput(AMENDED.toString())))
                .andExpect(status().isUnprocessableContent()).andExpect(request().asyncNotStarted());
    }

    @Test
    void l1RoundTripsHistoricalAgUiStateAndLetsAnExplicitNullSelectTheCurrentRecord() throws Exception {
        var readSelectors = new ArrayList<Instant>();
        var mvc = mvc(at -> {
            readSelectors.add(at);
            return at == null ? snapshot(AMENDED, "claim-2", false) : snapshot(at, "claim-1", true);
        }, unusedIntake(), (turns, view) -> new LiveCommitmentsAgent.Answer(
                view.historical() ? "The opening agreement." : "The revised agreement.",
                List.of(view.evidence().getFirst().id())));

        var opening = run(mvc, runInput(OPENING.toString()));
        var emittedState = opening.get(1).get("snapshot");
        assertThat(emittedState.has("at")).isFalse();
        var followUp = (ObjectNode) JSON.readTree(runInput(null));
        followUp.put("runId", "run-2");
        followUp.set("state", emittedState);

        var historical = run(mvc, JSON.writeValueAsString(followUp));
        assertThat(historical.get(1).get("snapshot")).isEqualTo(emittedState);
        assertThat(historical.get(5).get("value").get("ids").get(0).asText()).isEqualTo("claim-1");

        var returnToCurrent = (ObjectNode) emittedState.deepCopy();
        returnToCurrent.putNull("at").put("asOf", "ignored when at is explicit");
        followUp.put("runId", "run-3");
        followUp.set("state", returnToCurrent);
        var current = run(mvc, JSON.writeValueAsString(followUp));
        assertThat(current.get(1).get("snapshot").get("asOf").asText()).isEqualTo(AMENDED.toString());
        assertThat(current.get(1).get("snapshot").get("historical").asBoolean()).isFalse();
        assertThat(current.get(5).get("value").get("ids").get(0).asText()).isEqualTo("claim-2");
        assertThat(readSelectors).containsExactly(OPENING, OPENING, null);
    }

    private static MockMvc mvc(Function<Instant, LiveCase.Snapshot> read,
                               Function<LiveCase.Upload, LiveCase.Intake> ingest,
                               BiFunction<List<LiveCommitmentsAgent.Turn>, LiveCommitmentsAgent.EvidenceView,
                                       LiveCommitmentsAgent.Answer> answer) {
        var mapper = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build();
        return MockMvcBuilders.standaloneSetup(new LiveCommitmentsHttp(read, ingest, new CommitmentsAgUi(answer, mapper)))
                .build();
    }

    private static Function<LiveCase.Upload, LiveCase.Intake> unusedIntake() {
        return upload -> { throw new AssertionError("Unexpected source intake"); };
    }

    private static BiFunction<List<LiveCommitmentsAgent.Turn>, LiveCommitmentsAgent.EvidenceView,
            LiveCommitmentsAgent.Answer> unusedAnswer() {
        return (turns, evidence) -> { throw new AssertionError("Unexpected agent invocation"); };
    }

    private static LiveCase.Snapshot snapshot(Instant at, String claimId, boolean historical) {
        var source = new LiveCase.Source("source-1", "Signed addendum", "SIGNED_AGREEMENT",
                "The signed source records the date.", at);
        var claim = new LiveCase.Claim(claimId, "We commit to migrate by November 25.", "COMMITMENT", "ACTIVE",
                "AGREED", "Signed by both parties.", "fixture", null, source);
        return new LiveCase.Snapshot(at, OPENING, historical, List.of(claim));
    }

    private static String runInput(String at) {
        return """
                {"threadId":"thread-1","runId":"run-1","parentRunId":"previous-run","state":{"at":%s,"claims":[{"id":"untrusted"}]},
                 "messages":[{"id":"m1","role":"user","content":"When did we agree to migrate?"},
                             {"id":"m2","role":"assistant","content":"November 25."},
                             {"id":"m3","role":"user","content":"Which source confirms that?"}],
                 "tools":[],"context":[],"forwardedProps":{}}
                """.formatted(JSON.writeValueAsString(at));
    }

    private static List<JsonNode> run(MockMvc mvc, String body) throws Exception {
        var pending = mvc.perform(post(AGREEMENT + "/agent").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(body))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-cache")).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        var stream = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).endsWith("\n\n");
        return Arrays.stream(stream.split("\n\n")).map(frame -> {
            assertThat(frame).startsWith("data: ").doesNotContain("\n");
            return JSON.readTree(frame.substring(6));
        }).toList();
    }
}
