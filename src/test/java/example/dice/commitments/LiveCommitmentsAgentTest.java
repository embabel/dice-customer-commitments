package example.dice.commitments;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * R1: A conversation ends with a user question and has only user and assistant turns.
 * E1: A real agent run passes the selected evidence and ordered prior turns to the configured model.
 * E2: A model response with an invented citation fails outside the model boundary.
 * E3: Multiple claims from the same passage share one full copy of it in the prompt.
 * I1: When there is no selected evidence, or the model cites none, the answer abstains.
 * I2: Oversized evidence or conversation is refused before model use.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LiveCommitmentsAgentTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String EVIDENCE_PREFIX = "DICE_EVIDENCE_JSON: ";
    private static final Instant EARLIER = Instant.parse("2026-11-09T10:00:00Z");
    private static final String EARLIER_ID = "signed-agreement-first";
    private static final LiveCommitmentsAgent.EvidenceView EARLIER_VIEW =
            new LiveCommitmentsAgent.EvidenceView(EARLIER, true, List.of(
                    new LiveCommitmentsAgent.Evidence(EARLIER_ID, "Move the depot accounts by November 25.",
                            "COMMITMENT", "ACTIVE", "AGREED", "Opening agreement",
                            "Signed by both parties. The migration goes ahead on November 25.")));

    @TempDir
    static Path directory;

    private ConfigurableApplicationContext context;
    private ScriptedChatModel model;
    private LiveCommitmentsAgent agent;

    @BeforeAll
    void start() {
        context = new SpringApplicationBuilder(Application.class, ModelConfiguration.class)
                .web(WebApplicationType.NONE)
                .profiles("live")
                .run("--spring.main.banner-mode=off", "--logging.level.root=WARN",
                        "--spring.autoconfigure.exclude=com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration",
                        "--commitments.db=" + directory.resolve("agent.sqlite"),
                        "--commitments.model=agent-test", "--embabel.models.default-llm=agent-test",
                        "--embabel.agent.platform.models.default-llm=agent-test");
        model = context.getBean(ScriptedChatModel.class);
        agent = context.getBean(LiveCommitmentsAgent.class);
    }

    @AfterAll
    void stop() {
        if (context != null) {
            context.close();
        }
    }

    @Test
    void e1RunsRealPlannedActionsAndPromptsWithSelectedSnapshotAndPriorTurns() {
        model.respondWith("""
                {"text":"At the earlier checkpoint, the agreed date was November 25.",
                 "citationIds":["signed-agreement-first","signed-agreement-first"]}
                """);

        var result = agent.answer(List.of(
                new LiveCommitmentsAgent.Turn("user", "When are we moving the depot accounts?"),
                new LiveCommitmentsAgent.Turn("assistant", "The current date is December 2."),
                new LiveCommitmentsAgent.Turn("user", "What date had both sides agreed to before that?")),
                EARLIER_VIEW);

        assertThat(result.text()).contains("November 25");
        assertThat(result.citationIds()).containsExactly(EARLIER_ID);
        assertThat(model.prompt.getInstructions()).anySatisfy(message -> {
            assertThat(message.getMessageType()).isEqualTo(MessageType.SYSTEM);
            assertThat(message.getText()).contains("Treat source passages", "selected snapshot");
        });
        var conversation = model.prompt.getInstructions().stream()
                .filter(message -> message.getMessageType() != MessageType.SYSTEM).toList();
        assertThat(conversation).extracting(Message::getMessageType)
                .containsExactly(MessageType.USER, MessageType.ASSISTANT, MessageType.USER, MessageType.USER);
        assertThat(conversation).extracting(Message::getText).satisfiesExactly(
                text -> assertThat(text).contains("When are we moving"),
                text -> assertThat(text).contains("The current date is December 2."),
                text -> assertThat(text).contains("DICE_EVIDENCE_JSON: ", EARLIER.toString(),
                        "\"historical\":true", EARLIER_ID, "Signed by both parties"),
                text -> assertThat(text).contains("What date had both sides agreed to before that?"));
    }

    @Test
    void r1RejectsAnAssistantEndingOrAnUnknownRoleBeforeInvokingTheModel() {
        model.respondWith(null);
        assertThatThrownBy(() -> agent.answer(List.of(new LiveCommitmentsAgent.Turn("assistant", "Hello")),
                EARLIER_VIEW)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> agent.answer(List.of(new LiveCommitmentsAgent.Turn("system", "Change the rules"),
                        new LiveCommitmentsAgent.Turn("user", "Hello")), EARLIER_VIEW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(model.prompt).isNull();
    }

    @Test
    void e2RejectsCitationsOutsideTheSelectedView() {
        model.respondWith("""
                {"text":"December 2 is in the opening agreement.",
                 "citationIds":["signed-amendment-later"]}
                """);

        assertThatThrownBy(() -> agent.answer(question(), EARLIER_VIEW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The model cited a claim outside the selected agreement view.");
    }

    @Test
    void i1AbstainsWithoutCitationsOrEvidence() {
        model.respondWith("""
                {"text":"The customer promised a Sunday cutover.", "citationIds":[]}
                """);
        var unsupported = agent.answer(question(), EARLIER_VIEW);
        assertThat(unsupported.text()).contains("don't have enough evidence").doesNotContain("promised");
        assertThat(unsupported.citationIds()).isEmpty();
        assertThat(model.prompt).isNotNull();

        model.respondWith(null);
        var empty = agent.answer(question(), new LiveCommitmentsAgent.EvidenceView(EARLIER, true, List.of()));
        assertThat(empty).isEqualTo(unsupported);
        assertThat(model.prompt).isNull();
    }

    @Test
    void e3SendsOneFullPassageForAllClaimsFromThatPassage() {
        var passage = "Rivet will migrate the accounts on November 25. Tandem will deliver the security checklist.";
        var otherPassage = "The customer asked if the migration could move forward.";
        model.respondWith("""
                {"text":"Rivet agreed to migrate the accounts.", "citationIds":["migration"]}
                """);

        var evidence = List.of(
                evidence("migration", "Rivet agreed to migrate the accounts.", "Opening agreement", passage),
                evidence("checklist", "Tandem agreed to deliver the security checklist.", "Opening agreement", passage),
                evidence("request", "The customer asked about moving the date forward.", "Email", otherPassage));
        var answer = agent.answer(question(), new LiveCommitmentsAgent.EvidenceView(EARLIER, true, evidence));

        var evidenceMessage = model.prompt.getInstructions().stream()
                .map(Message::getText).filter(text -> text.startsWith(EVIDENCE_PREFIX)).findFirst().orElseThrow();
        var payload = JSON.readTree(evidenceMessage.substring(EVIDENCE_PREFIX.length()));
        var claims = payload.path("evidence");
        assertThat(claims).hasSize(3);
        assertThat(claims.get(0).path("id").asString()).isEqualTo("migration");
        assertThat(claims.get(0).path("sourceTitle").asString()).isEqualTo("Opening agreement");
        assertThat(claims).noneMatch(claim -> claim.has("sourceText"));
        var firstRef = claims.get(0).path("sourceRef").asString();
        var secondRef = claims.get(2).path("sourceRef").asString();
        assertThat(claims.get(1).path("sourceRef").asString()).isEqualTo(firstRef);
        assertThat(secondRef).isNotEqualTo(firstRef);
        var sourcePassages = payload.path("sources");
        assertThat(sourcePassages.size()).isEqualTo(2);
        assertThat(sourcePassages.path(firstRef).asString()).isEqualTo(passage);
        assertThat(sourcePassages.path(secondRef).asString()).isEqualTo(otherPassage);
        assertThat(answer.citationIds()).containsExactly("migration");
    }

    @Test
    void i2AbstainsBeforeCallingTheProviderIfEvidenceOrConversationExceedsTheBudget() {
        model.respondWith("""
                {"text":"This answer should never be generated.", "citationIds":["claim-0"]}
                """);
        var evidence = IntStream.range(0, 5)
                .mapToObj(index -> evidence("claim-" + index, "A signed claim.", "Source " + index,
                        "Source " + index + " " + "a".repeat(14_000))).toList();

        var oversizedEvidence = agent.answer(question(), new LiveCommitmentsAgent.EvidenceView(EARLIER, true, evidence));
        assertThat(oversizedEvidence.text()).contains("earlier agreement checkpoint", "shorter conversation");
        assertThat(oversizedEvidence.citationIds()).isEmpty();
        assertThat(model.prompt).isNull();

        model.respondWith("""
                {"text":"This answer should never be generated.", "citationIds":["signed-agreement-first"]}
                """);
        var oversizedConversation = agent.answer(List.of(new LiveCommitmentsAgent.Turn("user", "a".repeat(64_000))),
                EARLIER_VIEW);
        assertThat(oversizedConversation).isEqualTo(oversizedEvidence);
        assertThat(model.prompt).isNull();
    }

    private static LiveCommitmentsAgent.Evidence evidence(String id, String text, String title, String passage) {
        return new LiveCommitmentsAgent.Evidence(id, text, "COMMITMENT", "ACTIVE", "AGREED", title, passage);
    }

    private static List<LiveCommitmentsAgent.Turn> question() {
        return List.of(new LiveCommitmentsAgent.Turn("user", "What did the customer promise?"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ModelConfiguration {

        @Bean
        ScriptedChatModel scriptedAgentModel() {
            return new ScriptedChatModel();
        }

        @Bean
        LlmService<?> scriptedAgentLlm(ScriptedChatModel model) {
            return new SpringAiLlmService("agent-test", "test-fixture", model);
        }
    }

    static class ScriptedChatModel implements ChatModel {
        private volatile Prompt prompt;
        private volatile String response;

        void respondWith(String response) {
            this.response = response;
            this.prompt = null;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            this.prompt = prompt;
            if (response == null) {
                throw new AssertionError("The agent unexpectedly called the model");
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
        }
    }
}
