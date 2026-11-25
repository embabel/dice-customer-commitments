package example.dice.commitments;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/*
 * R1: The submitted title, text, kind, and signed authority fields must be valid before any model call.
 * E1: DICE extracts from unfamiliar source text, classifies each claim, and persists its exact source.
 * E2: Even an amendment classification cannot supersede an agreement without attestation.
 * I1: An extraction with no propositions writes no claims.
 * L1: A committed receipt can be returned after restart without a provider call.
 */
class LiveIntakeTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ORIGINAL = "On November 25, Juniper will deliver the scanned depot records.";
    private static final String EXTENSION = "Juniper will deliver the scanned depot records on December 2.";
    private static final String AGREEMENT_TEXT = "We both signed today. Juniper will deliver the scanned depot records "
            + "on November 25.\nThe data transfer is part of our onboarding.";
    private static final String EXTENSION_TEXT = "Both parties have signed this revision. "
            + "Move the November 25 records delivery to December 2.";

    @TempDir
    Path directory;

    @Test
    void e1AndE2RealPipelinePersistsOriginalTextAndRequiresAttestationBeforeSuperseding() {
        String originalId;
        String revisedId;
        try (var context = start()) {
            var intake = context.getBean(LiveIntake.class);
            var model = context.getBean(ScriptedChatModel.class);
            model.claim(ORIGINAL, "COMMITMENT");
            var agreement = new LiveCase.Upload("Juniper onboarding", "SIGNED_AGREEMENT", AGREEMENT_TEXT, null, true);
            var agreed = intake.ingest(agreement).claims().getFirst();
            originalId = agreed.id();

            assertThat(agreed.outcome()).isEqualTo("AGREED");
            assertThat(agreed.classification()).isEqualTo("COMMITMENT");
            assertThat(agreed.decisionProvider()).isEqualTo("scripted-intake");
            assertThat(agreed.source().id()).isEqualTo(LiveCase.sourceId(agreement));
            assertThat(agreed.source().text()).isEqualTo(AGREEMENT_TEXT);
            assertThat(model.prompts.get(0)).contains(AGREEMENT_TEXT, "Focus on customer commitments and schedule changes.");
            assertThat(decisionInput(model.prompts.get(1))).contains(AGREEMENT_TEXT, ORIGINAL, "SIGNED_AGREEMENT");

            String unsignedText = "The parties are reviewing a revision for delivery on December 1.";
            model.claim("A draft would move delivery of the scanned depot records to December 1.", "AMENDMENT");
            var draft = intake.ingest(new LiveCase.Upload("Draft change", "SIGNED_AMENDMENT", unsignedText, originalId));
            assertThat(draft.claims()).singleElement().satisfies(claim -> {
                assertThat(claim.classification()).isEqualTo("AMENDMENT");
                assertThat(claim.outcome()).isEqualTo("REVIEW_NEEDED");
                assertThat(claim.source().text()).isEqualTo(unsignedText);
            });
            assertThat(find(draft.snapshot(), originalId).status()).isEqualTo("ACTIVE");
            assertThat(decisionInput(model.prompts.get(3))).contains("Prior obligation: " + ORIGINAL);

            model.claim(EXTENSION, "AMENDMENT");
            var revised = intake.ingest(new LiveCase.Upload("Juniper extension", "SIGNED_AMENDMENT",
                    EXTENSION_TEXT, originalId, true));
            var replacement = revised.claims().getFirst();
            revisedId = replacement.id();
            assertThat(replacement.outcome()).isEqualTo("REVISED");
            assertThat(replacement.replacesClaimId()).isEqualTo(originalId);
            assertThat(find(revised.snapshot(), originalId).status()).isEqualTo("SUPERSEDED");
            assertThat(model.responses).isEmpty();
        }

        try (var restarted = start()) {
            var snapshot = restarted.getBean(LiveCaseStore.class).read(null);
            assertThat(find(snapshot, originalId).source().text()).isEqualTo(AGREEMENT_TEXT);
            assertThat(find(snapshot, revisedId).source().text()).isEqualTo(EXTENSION_TEXT);
        }
    }

    @Test
    void r1RejectsInvalidUploadsBeforeCallingTheModel() {
        try (var context = start()) {
            var intake = context.getBean(LiveIntake.class);
            var invalid = List.of(
                    new LiveCase.Upload("Unknown type", "CONTRACT", "Text", null),
                    new LiveCase.Upload("   ", "EMAIL", "Text", null),
                    new LiveCase.Upload("x".repeat(161), "EMAIL", "Text", null),
                    new LiveCase.Upload("Oversized text", "EMAIL", "x".repeat(16_001), null),
                    new LiveCase.Upload("Blank text", "EMAIL", "   ", null),
                    new LiveCase.Upload("Invalid predecessor", "EMAIL", "Text", "prior"),
                    new LiveCase.Upload("Invalid authority", "CALL", "Text", null, true),
                    new LiveCase.Upload("Missing active selection", "SIGNED_AMENDMENT", "Text", " "));
            for (var upload : invalid) {
                assertThatThrownBy(() -> intake.ingest(upload)).isInstanceOf(IllegalArgumentException.class);
            }
            assertThat(context.getBean(ScriptedChatModel.class).prompts).isEmpty();
        }
    }

    @Test
    void i1FallsBackCleanlyWhenTheSourceContainsNoClaims() {
        try (var context = start()) {
            var model = context.getBean(ScriptedChatModel.class);
            model.responses.add("{\"propositions\":[]}");
            var intake = context.getBean(LiveIntake.class);

            assertThatThrownBy(() -> intake.ingest(new LiveCase.Upload("No content", "EMAIL", "Thanks!", null)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("DICE did not find any claims in this source.");
            assertThat(model.prompts).hasSize(1);
            assertThat(context.getBean(LiveCaseStore.class).read(null).claims()).isEmpty();
        }
    }

    @Test
    void l1RetryReturnsACommittedReceiptWhenTheProviderHasNoResponse() {
        var upload = new LiveCase.Upload("Juniper onboarding", "SIGNED_AGREEMENT", AGREEMENT_TEXT, null, true);
        LiveCase.Intake saved;
        try (var context = start()) {
            var model = context.getBean(ScriptedChatModel.class);
            model.claim(ORIGINAL, "COMMITMENT");
            saved = context.getBean(LiveIntake.class).ingest(upload);
            assertThat(model.responses).isEmpty();
        }
        try (var restarted = start()) {
            var model = restarted.getBean(ScriptedChatModel.class);
            assertThat(model.responses).isEmpty();
            var retried = restarted.getBean(LiveIntake.class).ingest(upload);
            assertThat(retried).isEqualTo(saved);
            assertThat(model.prompts).isEmpty();
        }
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(Application.class, Models.class)
                .web(WebApplicationType.NONE)
                .profiles("live")
                .run("--commitments.db=" + directory.resolve("intake.sqlite"), "--commitments.model=intake-fixture",
                        "--spring.main.banner-mode=off", "--logging.level.root=WARN",
                        "--spring.autoconfigure.exclude=com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration");
    }

    private static String decisionInput(String prompt) {
        return JSON.readTree(prompt).get("input").asString();
    }

    private static LiveCase.Claim find(LiveCase.Snapshot snapshot, String id) {
        return snapshot.claims().stream().filter(claim -> claim.id().equals(id)).findFirst().orElseThrow();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Models {

        @Bean
        ScriptedChatModel intakeModel() {
            return new ScriptedChatModel();
        }

        @Bean
        LlmService<?> liveIntakeTestModel(ScriptedChatModel model) {
            return new SpringAiLlmService("intake-fixture", "scripted-intake", model);
        }
    }

    static class ScriptedChatModel implements ChatModel {
        private final ArrayDeque<String> responses = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        void claim(String text, String classification) {
            responses.add("""
                    {"propositions":[{"text":"%s","confidence":0.97,"importance":0.9,"decay":0,"mentions":[],
                                      "reasoning":"The submitted source states the claim."}]}
                    """.formatted(text));
            responses.add("""
                    {"answers":[{"question":"q1","verdict":"SELECTED","categoryId":"%s"}]}
                    """.formatted(classification));
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            prompts.add(prompt.getUserMessage().getText());
            assertThat(responses).as("A response for each provider call").isNotEmpty();
            return new ChatResponse(List.of(new Generation(new AssistantMessage(responses.removeFirst()))));
        }
    }
}
