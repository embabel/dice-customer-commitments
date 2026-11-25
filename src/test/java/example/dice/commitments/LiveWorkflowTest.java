package example.dice.commitments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * R1: Source intake runs the production extractor and model-backed decision service.
 * E1: A live agent follow-up uses previous conversation turns and cites the selected agreement.
 * I1: A customer request leaves the signed date intact. A signed amendment changes one obligation.
 * L1: Current and as-of citations resolve to their original sources across restart.
 */
class LiveWorkflowTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PATH = "/api/live/commitments";
    private static final String ADDENDUM = "This addendum is signed by Shoreline Labs and Cedar Transit. "
            + "Shoreline Labs commits to migrate Cedar Transit's dispatcher accounts by January 15, 2027. "
            + "Cedar Transit commits to deliver its security checklist by January 12, 2027.";
    private static final String REQUEST = "Could Shoreline Labs move our dispatcher account migration to January 9, 2027? "
            + "Please confirm whether you can commit to that date.";
    private static final String AMENDMENT = "This amendment is signed by Shoreline Labs and Cedar Transit. "
            + "It replaces the January 15, 2027 dispatcher-account migration deadline with January 22, 2027. "
            + "All other obligations in the addendum are unchanged.";

    @TempDir
    Path directory;

    @Test
    void r1E1I1L1IngestsDiscussesAndReplaysAnAgreementAcrossRestart() throws Exception {
        String original;
        String replacement;
        String security;
        String checkpoint;
        try (var app = start()) {
            var mvc = mvc(app);
            var opening = upload(mvc, "Migration addendum", "SIGNED_AGREEMENT", ADDENDUM, true, null);
            var migrationClaim = dated(opening, "2027-01-15");
            original = migrationClaim.path("id").asText();
            security = dated(opening, "2027-01-12").path("id").asText();
            checkpoint = migrationClaim.path("source").path("receivedAt").asText();
            assertThat(migrationClaim.path("outcome").asText()).isEqualTo("AGREED");
            assertThat(migrationClaim.path("classification").asText()).isEqualTo("COMMITMENT");
            assertThat(migrationClaim.path("decisionProvider").asText()).isEqualTo("test");

            var importedRequest = upload(mvc, "Earlier cutover request", "EMAIL", REQUEST, false, null);
            var proposed = dated(importedRequest, "2027-01-09");
            assertThat(proposed.path("classification").asText()).isEqualTo("REQUEST");
            assertThat(proposed.path("outcome").asText()).isEqualTo("RECORDED");
            assertThat(byId(importedRequest.path("snapshot"), original).path("status").asText()).isEqualTo("ACTIVE");

            var question = turn("m1", "user", "Did we agree to cut over on January 9?");
            var first = run(mvc, null, question);
            assertThat(first.text()).isEqualTo("January 9 was a request. The signed date is January 15, 2027.");
            assertThat(first.citations()).containsExactly(proposed.path("id").asText(), original);

            var second = run(mvc, null, question, turn("m2", "assistant", first.text()),
                    turn("m3", "user", "What date should I tell them?"));
            assertThat(second.text()).isEqualTo("Tell Cedar Transit January 15, 2027.");
            assertThat(second.citations()).containsExactly(original);

            var amended = upload(mvc, "Revised migration schedule", "SIGNED_AMENDMENT", AMENDMENT, true, original);
            var replacementClaim = dated(amended, "2027-01-22");
            replacement = replacementClaim.path("id").asText();
            assertThat(replacementClaim.path("classification").asText()).isEqualTo("AMENDMENT");
            assertThat(replacementClaim.path("outcome").asText()).isEqualTo("REVISED");
            assertThat(replacementClaim.path("replacesClaimId").asText()).isEqualTo(original);
            assertThat(byId(amended.path("snapshot"), original).path("status").asText()).isEqualTo("SUPERSEDED");
        }

        try (var app = start()) {
            var mvc = mvc(app);
            var current = run(mvc, null, turn("m1", "user", "Which deadline stayed in place when the migration moved?"));
            assertThat(current.text()).contains("January 22, 2027", "January 12, 2027");
            assertThat(current.citations()).containsExactly(replacement, security);
            assertThat(byId(current.snapshot(), replacement).path("source").path("text").asText()).isEqualTo(AMENDMENT);

            var loaded = mvc.perform(get(PATH).queryParam("at", checkpoint)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            var replay = run(mvc, checkpoint, turn("m1", "user", "What was the migration date in this record?"));
            assertThat(replay.snapshot()).isEqualTo(JSON.readTree(loaded));
            assertThat(replay.snapshot().path("historical").asBoolean()).isTrue();
            assertThat(replay.text()).contains("January 15, 2027");
            assertThat(replay.citations()).containsExactly(original);
            assertThat(byId(replay.snapshot(), original).path("source").path("text").asText()).isEqualTo(ADDENDUM);
        }
    }

    private ConfigurableApplicationContext start() {
        return LiveBrowserFixture.start(WebApplicationType.NONE,
                "--commitments.db=" + directory.resolve("agreement.sqlite"),
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    private static MockMvc mvc(ConfigurableApplicationContext app) {
        return MockMvcBuilders.standaloneSetup(app.getBean(LiveCommitmentsHttp.class)).build();
    }

    private static JsonNode upload(MockMvc mvc, String title, String kind, String text,
                                   boolean signedAttested, String prior) throws Exception {
        var body = JSON.createObjectNode().put("title", title).put("kind", kind).put("text", text)
                .put("signedAttested", signedAttested);
        if (prior != null) {
            body.put("replacesClaimId", prior);
        }
        var response = mvc.perform(post(PATH + "/sources").contentType(MediaType.APPLICATION_JSON)
                        .content(body.toString())).andReturn().getResponse();
        var result = response.getContentAsString(StandardCharsets.UTF_8);
        assertThat(response.getStatus()).as("Source intake response: %s", result).isEqualTo(200);
        return JSON.readTree(result);
    }

    private static JsonNode dated(JsonNode result, String date) {
        return result.path("claims").valueStream()
                .filter(claim -> claim.path("text").asText().contains(date)).findFirst().orElseThrow();
    }

    private static JsonNode byId(JsonNode snapshot, String id) {
        return snapshot.path("claims").valueStream()
                .filter(claim -> id.equals(claim.path("id").asText())).findFirst().orElseThrow();
    }

    private static Map<String, String> turn(String id, String role, String content) {
        return Map.of("id", id, "role", role, "content", content);
    }

    @SafeVarargs
    private static Reply run(MockMvc mvc, String at, Map<String, String>... messages) throws Exception {
        var state = at == null ? Map.of() : Map.of("at", at);
        var input = JSON.writeValueAsString(Map.of("threadId", "test-thread", "runId", "test-run", "state", state,
                "messages", List.of(messages), "tools", List.of(), "context", List.of(), "forwardedProps", Map.of()));
        var pending = mvc.perform(post(PATH + "/agent").contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM).content(input))
                .andExpect(request().asyncStarted()).andReturn();
        var response = mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        var events = response.getContentAsString(StandardCharsets.UTF_8).lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> JSON.readTree(line.substring(5).stripLeading())).toList();

        assertThat(events).extracting(event -> event.path("type").asText()).containsExactly(
                "RUN_STARTED", "STATE_SNAPSHOT", "TEXT_MESSAGE_START", "TEXT_MESSAGE_CONTENT",
                "TEXT_MESSAGE_END", "CUSTOM", "RUN_FINISHED");
        assertThat(events.get(5).path("name").asText()).isEqualTo("commitments.citations");
        return new Reply(events.get(1).path("snapshot"), events.get(3).path("delta").asText(),
                events.get(5).path("value").path("ids").valueStream().map(JsonNode::asText).toList());
    }

    private record Reply(JsonNode snapshot, String text, List<String> citations) {
    }
}
