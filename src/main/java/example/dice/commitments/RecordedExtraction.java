package example.dice.commitments;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Exact-passage playback. An unfamiliar source must not silently produce a fixture claim. */
final class RecordedExtraction implements ChatModel {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern SOURCE = Pattern.compile("<text>(.*?)</text>", Pattern.DOTALL);
    private static final Map<String, List<Map<String, Object>>> REPLIES = Map.of(
            "89f4d7d8a2a1c277ae1e1a9d8dec5e4417e3b55de5c0d3945dda63bc9b675503", List.of(proposition(
                    "Rivet Systems targets depot migration by 2026-11-20.", 0.94)),
            "7a10c1af789dc728ccca207bf6a630fbee699d9482bd61d823c16e2248cbd124", List.of(proposition(
                    "Tandem Transit commits to deliver the SSO security checklist by 2026-11-20.", 0.99),
                    proposition("Rivet Systems commits to migrate the depot accounts by 2026-11-25.", 0.99)),
            "51c2aeb06e19e250e4d3a13366f4f280a9e0fb85084b3412fe7ffd4f7859f157", List.of(proposition(
                    "Tandem Transit requests depot migration on 2026-11-23.", 0.97)),
            "33fce42b6e1e2bbdf0e23d431e80887fd7c31b557476485adfc02c0b7a88139d", List.of(proposition(
                    "Maya Chen commits to send the migration runbook by 2026-11-21.", 0.96),
                    proposition("Maya Chen has a reminder to ask about a Saturday cutover.", 0.95)),
            "ebc6a1d878c8bdd3e1c18e16ee2326bf729f059682f3dc286a03dd13febefe28", List.of(proposition(
                    "Rivet Systems commits to migrate the depot accounts by 2026-12-02.", 0.99)));

    @Override
    public ChatResponse call(Prompt prompt) {
        var source = SOURCE.matcher(prompt.getUserMessage().getText());
        if (!source.find()) {
            throw new IllegalArgumentException("The offline model expects a DICE extraction prompt");
        }
        var propositions = REPLIES.get(digest(source.group(1).strip()));
        if (propositions == null) {
            throw new IllegalArgumentException("No recorded extraction exists for this passage");
        }
        try {
            var text = JSON.writeValueAsString(Map.of("propositions", propositions));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("Could not encode the recorded extraction", failure);
        }
    }

    private static String digest(String source) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static Map<String, Object> proposition(String text, double confidence) {
        return Map.of("text", text, "confidence", confidence, "importance", 0.9, "decay", 0,
                "mentions", List.of(), "reasoning", "The recorded source states this claim.");
    }
}
