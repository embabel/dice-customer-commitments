package example.dice.commitments;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.embabel.common.ai.classification.ClassificationResult;
import com.embabel.common.ai.classification.FailureReason;
import com.embabel.common.ai.classification.ModelProvenance;
import com.embabel.common.ai.decision.support.StubDecisionService;
import com.embabel.common.ai.model.DecisionServiceRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class CommitmentDecisionTest {

    // E1. The configured live decision model classifies content through the Embabel choice spec.
    // I1. An amendment requires a prior obligation. Unavailable decisions require review.
    // I2. The default profile starts with the recorded workflow and no configured model provider.
    private static final String QUESTION = "commitment-kind";
    private static final ModelProvenance MODEL = new ModelProvenance("decision-fixture", "test-provider");

    @TempDir
    Path directory;

    @Test
    void e1LiveProfileRunsThePromptedDecisionAgainstTheSelectedModel() {
        var builder = new SpringApplicationBuilder(Application.class, Models.class).web(WebApplicationType.NONE);
        try (var context = builder.run(
                "--spring.profiles.active=live",
                "--spring.autoconfigure.exclude=com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration",
                "--commitments.model=decision-fixture",
                "--commitments.db=" + directory.resolve("live.sqlite"),
                "--spring.main.banner-mode=off")) {
            var models = context.getBean(LiveModelConfiguration.class);
            assertThat(models.name()).isEqualTo("decision-fixture");
            assertThat(models.decision()).isEqualTo("decision-fixture");
            assertThat(context.getBeansOfType(CommitmentsHttp.class)).isEmpty();

            var result = context.getBean(CommitmentDecision.class).assess(
                    "CUSTOMER_EMAIL", "Could we move the handoff to Friday?", "The customer asked for Friday.", null);

            assertThat(result).isEqualTo(new CommitmentDecision.Assessment("REQUEST", "test-provider"));
            assertThat(context.getBean(Models.class).prompt.get().getUserMessage().getText())
                    .contains("CUSTOMER_EMAIL", "The customer asked for Friday.", "Could we move the handoff to Friday?");
        }
    }

    @Test
    void i1AnAmendmentClassificationRequiresAPriorClaim() {
        var decision = decisionWith(new ClassificationResult.Selected("AMENDMENT", MODEL));

        assertThat(decision.assess("SIGNED", "Move the delivery to Friday.", "Delivery now due Friday.", "Delivery due Thursday."))
                .isEqualTo(new CommitmentDecision.Assessment("AMENDMENT", "test-provider"));
        assertThat(decision.assess("SIGNED", "Move the delivery to Friday.", "Delivery now due Friday.", null))
                .isEqualTo(new CommitmentDecision.Assessment("NEEDS_REVIEW", "test-provider"));
    }

    @Test
    void i1MissingOrUnavailableDecisionsRequireReview() {
        for (var outcome : List.of(
                new ClassificationResult.NoMatch(MODEL),
                new ClassificationResult.Inconclusive(MODEL),
                new ClassificationResult.Failure(FailureReason.UNAVAILABLE))) {
            var assessment = decisionWith(outcome).assess(
                    "CUSTOMER_EMAIL", "Friday?", "The customer mentioned Friday.", null);

            assertThat(assessment.classification()).isEqualTo("NEEDS_REVIEW");
        }
    }

    @Test
    void i2DefaultProfileRunsWithoutALiveDecisionProvider() {
        var builder = new SpringApplicationBuilder(Application.class).web(WebApplicationType.NONE);
        try (var context = builder.run(
                "--commitments.db=" + directory.resolve("recorded.sqlite"),
                "--spring.main.banner-mode=off")) {
            assertThat(context.getBean(CommitmentsHttp.class)).isNotNull();
            assertThat(context.getBeansOfType(CommitmentDecision.class)).isEmpty();
            assertThat(context.getBeansOfType(LlmService.class)).containsOnlyKeys("recordedExtractionModel");
        }
    }

    private static CommitmentDecision decisionWith(ClassificationResult outcome) {
        var service = StubDecisionService.builder("intake-test").choice(QUESTION, outcome).build();
        var registry = DecisionServiceRegistry.builder().register("intake-test", service)
                .decisionRole("commitment-intake", "intake-test").build();
        return new CommitmentDecision(registry);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Models {
        private final AtomicReference<Prompt> prompt = new AtomicReference<>();

        @Bean
        LlmService<?> deterministicDecisionModel() {
            return new SpringAiLlmService("decision-fixture", "test-provider", request -> {
                prompt.set(request);
                var response = new AssistantMessage("""
                        {"answers":[{"question":"q1","verdict":"SELECTED","categoryId":"REQUEST"}]}
                        """);
                return new ChatResponse(List.of(new Generation(response)));
            });
        }
    }
}
