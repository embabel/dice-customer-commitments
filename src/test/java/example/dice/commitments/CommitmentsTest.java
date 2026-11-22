package example.dice.commitments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class CommitmentsTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final String CASE = "/api/commitments";

    @TempDir
    Path directory;

    @Test
    void extractsPromisesAndProposedDatesThenReplaysTheSignedOpeningAgreement() throws Exception {
        try (var app = start()) {
            var opening = request(app, CASE, "GET", 200);
            assertThat(opening.path("claims").size()).isEqualTo(6);
            var target = claim(opening, "TARGET");
            assertThat(target.path("valueDate").asString()).isEqualTo("2026-11-20");
            assertThat(target.path("source").path("passage").asString()).contains("aiming", "not a commitment");
            var ask = claim(opening, "REQUEST");
            assertThat(ask.path("valueDate").asString()).isEqualTo("2026-11-23");
            assertThat(ask.path("source").path("passage").asString()).contains("bring the migration forward");
            var migration = claim(opening, "MIGRATION");
            assertThat(migration.path("valueDate").asString()).isEqualTo("2026-11-25");
            assertThat(migration.path("source").path("authority").asString()).isEqualTo("SIGNED");
            assertThat(migration.path("source").path("passage").asString()).contains("Rivet Systems will migrate");
            assertThat(claim(opening, "SECURITY").path("valueDate").asString()).isEqualTo("2026-11-20");

            assertThat(claim(opening, "SPOKEN").path("valueDate").asString()).isEqualTo("2026-11-21");
            assertThat(claim(opening, "TASK").path("source").path("authority").asString()).isEqualTo("ASSISTANT");
            assertThat(claim(opening, "TASK").path("statement").asString()).contains("reminder");

            var amended = request(app, CASE + "/amendment", "POST", 200);
            assertThat(claim(amended, "MIGRATION").path("valueDate").asString()).isEqualTo("2026-12-02");
            assertThat(claim(amended, "SECURITY").path("id").asString())
                    .isEqualTo(claim(opening, "SECURITY").path("id").asString());
            assertThat(byId(amended, migration.path("id").asString()).path("status").asString()).isEqualTo("SUPERSEDED");

            var replayed = request(app, CASE + "?at=" + opening.path("asOf").asString(), "GET", 200);
            assertThat(replayed.path("claims").size()).isEqualTo(6);
            assertThat(claim(replayed, "MIGRATION").path("valueDate").asString()).isEqualTo("2026-11-25");
            assertThat(replayed.path("historical").asBoolean()).isTrue();
        }
    }

    @Test
    void answersFromTheSelectedViewAndKeepsEvidenceAcrossRestart() throws Exception {
        String openingTime;
        try (var app = start()) {
            var opening = request(app, CASE, "GET", 200);
            openingTime = opening.path("baseline").asString();
            var first = ask(app, "What did we promise?", null);
            assertThat(first.path("text").asString()).contains("25 November 2026");
            assertThat(first.path("view").path("asOf").asString()).isEqualTo(openingTime);
            var citedMigration = first.path("citations").get(0).asString();
            assertThat(byId(opening, citedMigration).path("source").path("id").asString())
                    .isEqualTo("signed-addendum");
            var cause = ask(app, "What’s open?", null);
            assertThat(cause.path("text").asString()).contains("21 November", "still a task", "no agreed Saturday date");
            assertThat(cause.path("citations").size()).isEqualTo(2);
            var unsupported = ask(app, "Give me the renewal price", null);
            assertThat(unsupported.path("supported").asBoolean()).isFalse();
            assertThat(unsupported.path("citations").isEmpty()).isTrue();
            request(app, CASE + "/amendment", "POST", 200);
            assertThat(directory.resolve("agreements/commitments.sqlite")).isRegularFile();
            assertThat(request(app, CASE + "/amendment", "POST", 200).path("claims").size()).isEqualTo(7);
        }

        try (var app = start()) {
            var current = request(app, CASE, "GET", 200);
            assertThat(claim(current, "MIGRATION").path("source").path("id").asString())
                    .isEqualTo("signed-amendment");
            var change = ask(app, "What changed?", null);
            assertThat(change.path("text").asString()).contains("25 November 2026", "2 December 2026", "20 November 2026");
            assertThat(change.path("view").path("asOf").asString()).isEqualTo(current.path("asOf").asString());
            assertThat(byId(current, change.path("citations").get(0).asString()).path("source").path("passage").asString())
                    .contains("The parties extend", "All other commitments");
            assertThat(ask(app, "What did we promise?", openingTime).path("text").asString())
                    .contains("25 November 2026").doesNotContain("2 December 2026");
            assertThat(request(app, CASE + "?at=2026-11-18T14:59:59Z", "GET", 422).path("detail").asString())
                    .contains("no complete belief set");
            request(app, CASE + "?at=2026-11-18T15:22:01Z", "GET", 422);
        }
    }

    @Test
    void aFormStyleRequestCannotImportTheAmendment() throws Exception {
        try (var app = start()) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + CASE + "/amendment"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("signed-amendment"))
                    .build();
            assertThat(CLIENT.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(415);
            var opening = request(app, CASE, "GET", 200);
            assertThat(opening.path("amended").asBoolean()).isFalse();
            assertThat(claim(opening, "MIGRATION").path("valueDate").asString()).isEqualTo("2026-11-25");
        }
    }

    private static JsonNode ask(ConfigurableApplicationContext app, String text, String at) throws Exception {
        var builder = JSON.createObjectNode().put("text", text);
        if (at != null) {
            builder.put("at", at);
        }
        return request(app, CASE + "/ask", "POST", 200, JSON.writeValueAsString(builder));
    }

    private ConfigurableApplicationContext start() {
        return new SpringApplicationBuilder(Application.class)
                .run("--server.port=0", "--commitments.db=" + directory.resolve("agreements/commitments.sqlite"),
                        "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    private static JsonNode request(ConfigurableApplicationContext app, String path, String method, int status) throws Exception {
        var amendment = "POST".equals(method) && (CASE + "/amendment").equals(path);
        return request(app, path, method, status, amendment ? """
                {"documentId":"signed-amendment"}
                """ : null);
    }

    private static JsonNode request(ConfigurableApplicationContext app, String path, String method, int status,
                                    String json) throws Exception {
        int port = ((WebServerApplicationContext) app).getWebServer().getPort();
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(15));
        if (json != null) {
            builder.header("Content-Type", "application/json");
        }
        var request = builder.method(method, json == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json)).build();
        var response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("%s %s returned %s", method, path, response.body()).isEqualTo(status);
        return JSON.readTree(response.body());
    }

    private static JsonNode claim(JsonNode state, String kind) {
        return StreamSupport.stream(state.path("claims").spliterator(), false)
                .filter(claim -> kind.equals(claim.path("kind").asString()) && "ACTIVE".equals(claim.path("status").asString()))
                .findFirst().orElseThrow();
    }

    private static JsonNode byId(JsonNode state, String id) {
        return StreamSupport.stream(state.path("claims").spliterator(), false)
                .filter(claim -> id.equals(claim.path("id").asString())).findFirst().orElseThrow();
    }
}
