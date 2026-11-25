package example.dice.commitments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectWriter;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

/** The AG-UI HTTP/SSE binding for the commitments agent. */
final class CommitmentsAgUi {

    private static final int MAX_MESSAGES = 32;
    private static final int MAX_MESSAGE_LENGTH = 8_000;
    private static final int MAX_CONVERSATION_LENGTH = 48_000;

    private final BiFunction<List<LiveCommitmentsAgent.Turn>, LiveCommitmentsAgent.EvidenceView,
            LiveCommitmentsAgent.Answer> answer;
    private final ObjectWriter events;

    CommitmentsAgUi(BiFunction<List<LiveCommitmentsAgent.Turn>, LiveCommitmentsAgent.EvidenceView,
            LiveCommitmentsAgent.Answer> answer, JsonMapper json) {
        this.answer = answer;
        this.events = json.writer().without(SerializationFeature.INDENT_OUTPUT);
    }

    static Run validate(RunAgentInput input) {
        if (input == null || !validId(input.threadId()) || !validId(input.runId())) {
            throw badRequest("Supply a thread ID and a run ID of at most 128 characters.");
        }
        if (input.tools() == null || !input.tools().isEmpty()
                || input.context() == null || !input.context().isEmpty()) {
            throw badRequest("Send empty tools and context arrays. The server loads agreement evidence.");
        }
        if (input.messages() == null || input.messages().isEmpty() || input.messages().size() > MAX_MESSAGES) {
            throw badRequest("Supply between 1 and 32 conversation messages.");
        }

        var turns = new ArrayList<LiveCommitmentsAgent.Turn>();
        var ids = new HashSet<String>();
        var length = 0;
        for (var message : input.messages()) {
            if (message == null || !validId(message.id()) || !ids.add(message.id())) {
                throw badRequest("Supply a unique ID for each message.");
            }
            if (!"user".equals(message.role()) && !"assistant".equals(message.role())) {
                throw badRequest("Only user and assistant messages are supported.");
            }
            if (message.content() == null || message.content().isBlank()
                    || message.content().length() > MAX_MESSAGE_LENGTH) {
                throw badRequest("Each message must contain between 1 and 8000 characters of text.");
            }
            length += message.content().length();
            turns.add(new LiveCommitmentsAgent.Turn(message.role(), message.content()));
        }
        if (length > MAX_CONVERSATION_LENGTH || !"user".equals(turns.getLast().role())) {
            throw badRequest("Use at most 48000 characters and end the conversation with a user message.");
        }

        return new Run(input.threadId(), input.runId(), selectedInstant(input.state()), List.copyOf(turns));
    }

    private static Instant selectedInstant(JsonNode state) {
        if (state == null || state.isNull()) {
            return null;
        }
        if (!state.isObject()) {
            throw badRequest("Agreement state must be an object.");
        }
        var historical = state.get("historical");
        if (historical != null && !historical.isBoolean()) {
            throw badRequest("Historical agreement state must be a boolean.");
        }
        var at = state.get("at");
        if (at != null) {
            return at.isNull() ? null : requiredInstant(at);
        }
        // AG-UI clients feed the emitted snapshot back as state on follow-up runs.
        return historical != null && historical.booleanValue() ? requiredInstant(state.get("asOf")) : null;
    }

    private static Instant requiredInstant(JsonNode value) {
        if (value == null || !value.isTextual()) {
            throw badRequest("Select a recorded time using an ISO-8601 instant.");
        }
        return parseInstant(value.stringValue());
    }

    static Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw badRequest("Select a recorded time using an ISO-8601 instant.");
        }
    }

    private static boolean validId(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.chars().noneMatch(Character::isISOControl);
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }

    StreamingResponseBody stream(Run run, LiveCase.Snapshot snapshot) {
        return output -> {
            write(output, Map.of("type", "RUN_STARTED", "threadId", run.threadId(), "runId", run.runId()));
            write(output, Map.of("type", "STATE_SNAPSHOT", "snapshot", snapshot));

            LiveCommitmentsAgent.Answer result;
            try {
                var view = new LiveCommitmentsAgent.EvidenceView(snapshot.asOf(), snapshot.historical(), snapshot.claims()
                        .stream()
                        .map(claim -> new LiveCommitmentsAgent.Evidence(claim.id(), claim.text(), claim.classification(),
                                claim.status(), claim.outcome(), claim.source().title(), claim.source().text()))
                        .toList());
                result = answer.apply(run.turns(), view);
                var evidenceIds = view.evidence().stream().map(LiveCommitmentsAgent.Evidence::id)
                        .collect(Collectors.toSet());
                if (result.text().isBlank() || !evidenceIds.containsAll(result.citationIds())) {
                    throw new IllegalStateException("Answer does not match the selected agreement.");
                }
            } catch (RuntimeException error) {
                write(output, Map.of("type", "RUN_ERROR", "code", "AGENT_UNAVAILABLE",
                        "message", "The agent could not answer from this agreement. Please try again."));
                return;
            }

            var messageId = UUID.randomUUID().toString();
            write(output, Map.of("type", "TEXT_MESSAGE_START", "messageId", messageId, "role", "assistant"));
            write(output, Map.of("type", "TEXT_MESSAGE_CONTENT", "messageId", messageId, "delta", result.text()));
            write(output, Map.of("type", "TEXT_MESSAGE_END", "messageId", messageId));
            write(output, Map.of("type", "CUSTOM", "name", "commitments.citations",
                    "value", Map.of("messageId", messageId, "ids", result.citationIds())));
            write(output, Map.of("type", "RUN_FINISHED", "threadId", run.threadId(), "runId", run.runId()));
        };
    }

    private void write(OutputStream output, Map<String, ?> event) throws IOException {
        output.write(("data: " + events.writeValueAsString(event) + "\n\n").getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record RunAgentInput(String threadId, String runId, JsonNode state, List<Message> messages,
                         List<JsonNode> tools, List<JsonNode> context, JsonNode forwardedProps) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String id, String role, String content) {
    }

    record Run(String threadId, String runId, Instant at, List<LiveCommitmentsAgent.Turn> turns) {
    }
}
