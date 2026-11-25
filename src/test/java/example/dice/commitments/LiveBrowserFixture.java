package example.dice.commitments;

import com.embabel.agent.spi.LlmService;
import com.embabel.agent.spi.support.springai.SpringAiLlmService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Starts the live application with a deterministic model on the test classpath. */
public final class LiveBrowserFixture {

    private static final String MODEL = "browser-fixture";

    public static void main(String[] args) {
        start(WebApplicationType.SERVLET, args);
    }

    static ConfigurableApplicationContext start(WebApplicationType web, String... args) {
        var settings = new ArrayList<>(List.of(
                "--commitments.model=" + MODEL,
                "--commitments.decision=" + MODEL,
                "--embabel.models.default-llm=" + MODEL,
                "--spring.autoconfigure.exclude=com.embabel.agent.autoconfigure.models.openai.AgentOpenAiAutoConfiguration"));
        settings.addAll(List.of(args));

        return new SpringApplicationBuilder(Application.class, FixtureConfiguration.class)
                .web(web)
                .profiles("live")
                .run(settings.toArray(String[]::new));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FixtureConfiguration {
        @Bean
        LlmService<?> browserFixtureModel() {
            return new SpringAiLlmService(MODEL, "test", new FixtureModel());
        }
    }

    private static final class FixtureModel implements ChatModel {

        private static final JsonMapper JSON = JsonMapper.builder().build();
        private static final Pattern SOURCE = Pattern.compile("<text>(.*?)</text>", Pattern.DOTALL);
        private static final Pattern CANDIDATE = Pattern.compile("Candidate claim: (.*)");
        private static final String EVIDENCE_PREFIX = "DICE_EVIDENCE_JSON: ";
        private static final String SIGNED = "Shoreline Labs commits to migrate Cedar Transit’s dispatcher accounts by 2027-01-15.";
        private static final String SECURITY = "Cedar Transit commits to deliver the security checklist by 2027-01-12.";
        private static final String REQUEST = "Cedar Transit requests a dispatcher account migration by 2027-01-09.";
        private static final String AMENDMENT = "The signed amendment changes the dispatcher account migration deadline to 2027-01-22.";

        @Override
        public ChatResponse call(Prompt prompt) {
            try {
                var latest = prompt.getUserMessage().getText();
                var evidence = prompt.getInstructions().stream()
                        .map(message -> message.getText())
                        .filter(text -> text != null && text.startsWith(EVIDENCE_PREFIX))
                        .findFirst();
                var payload = evidence.isPresent()
                        ? answer(prompt, latest, JSON.readTree(evidence.get().substring(EVIDENCE_PREFIX.length())))
                        : intake(latest);
                return new ChatResponse(List.of(new Generation(new AssistantMessage(JSON.writeValueAsString(payload)))));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not encode a browser fixture reply", e);
            }
        }

        private static Map<String, Object> intake(String userMessage) throws JsonProcessingException {
            if (userMessage.startsWith("{")) {
                var input = JSON.readTree(userMessage).path("input");
                if (input.isTextual()) {
                    return decision(input.textValue());
                }
            }
            var source = SOURCE.matcher(userMessage);
            if (!source.find()) {
                throw new IllegalArgumentException("Unexpected browser fixture model prompt");
            }
            var passage = source.group(1);
            if (passage.contains("All other obligations in the addendum are unchanged")) {
                return propositions(AMENDMENT);
            }
            if (passage.contains("Please confirm whether you can commit")) {
                return propositions(REQUEST);
            }
            if (passage.contains("security checklist by January 12, 2027")) {
                return propositions(SIGNED, SECURITY);
            }
            throw new IllegalArgumentException("Unexpected browser fixture source");
        }

        private static Map<String, Object> decision(String input) {
            var claim = CANDIDATE.matcher(input);
            if (!claim.find()) {
                throw new IllegalArgumentException("Expected a claim in the decision request");
            }
            String category = switch (claim.group(1)) {
                case SIGNED, SECURITY -> "COMMITMENT";
                case REQUEST -> "REQUEST";
                case AMENDMENT -> {
                    if (!input.contains("Prior obligation: " + SIGNED)) {
                        throw new IllegalArgumentException("Amendment decision is missing the signed prior obligation");
                    }
                    yield "AMENDMENT";
                }
                default -> throw new IllegalArgumentException("Unexpected browser fixture claim");
            };
            return Map.of("answers", List.of(Map.of("question", "q1", "verdict", "SELECTED", "categoryId", category)));
        }

        private static Map<String, Object> propositions(String... texts) {
            var claims = new ArrayList<Map<String, Object>>();
            for (var text : texts) {
                claims.add(Map.of("text", text, "confidence", 0.97, "importance", 0.9, "decay", 0,
                        "mentions", List.of(), "reasoning", "The supplied passage supports this statement."));
            }
            return Map.of("propositions", claims);
        }

        private static Map<String, Object> answer(Prompt prompt, String latest, JsonNode snapshot) {
            var claims = snapshot.path("evidence");
            var migration = claim(claims, "ACTIVE", SIGNED, AMENDMENT);
            var date = migration.path("text").asText().equals(AMENDMENT) ? "January 22, 2027" : "January 15, 2027";
            if (latest.contains("January 9")) {
                var request = claim(claims, "ACTIVE", REQUEST);
                return reply("January 9 was a request. The signed date is " + date + ".", request, migration);
            }
            if (latest.contains("What date should I tell them?")) {
                var priorAnswer = prompt.getInstructions().stream()
                        .anyMatch(message -> message.getMessageType() == MessageType.ASSISTANT
                                && message.getText().contains("January 9 was a request"));
                if (!priorAnswer) {
                    throw new IllegalArgumentException("Follow-up is missing the earlier assistant response");
                }
                return reply("Tell Cedar Transit " + date + ".", migration);
            }
            if (latest.contains("Which deadline stayed")) {
                return reply("Migration moved to " + date + ". The security checklist stayed due January 12, 2027.",
                        migration, claim(claims, "ACTIVE", SECURITY));
            }
            if (latest.contains("in this record") && snapshot.path("historical").asBoolean()) {
                return reply("The migration date in this record is " + date + ".", migration);
            }
            throw new IllegalArgumentException("Unexpected browser fixture question");
        }

        private static JsonNode claim(JsonNode evidence, String status, String... matchingTexts) {
            for (var claim : evidence) {
                if (status.equals(claim.path("status").asText())
                        && List.of(matchingTexts).contains(claim.path("text").asText())) {
                    return claim;
                }
            }
            throw new IllegalArgumentException("The selected evidence is missing a required claim");
        }

        private static Map<String, Object> reply(String text, JsonNode... claims) {
            var citations = new ArrayList<String>();
            for (var claim : claims) {
                citations.add(claim.path("id").asText());
            }
            return Map.of("text", text, "citationIds", citations);
        }
    }
}
