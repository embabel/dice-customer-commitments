package example.dice.commitments;

import com.embabel.dice.audit.AuditCoverageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.time.Instant;
import java.util.function.Function;

@RestController
@Profile("live")
class LiveCommitmentsHttp {

    private static final String AGREEMENT = "/api/live/commitments";
    private final Function<Instant, LiveCase.Snapshot> read;
    private final Function<LiveCase.Upload, LiveCase.Intake> ingest;
    private final CommitmentsAgUi agUi;

    @Autowired
    LiveCommitmentsHttp(LiveCaseStore store, LiveIntake intake, LiveCommitmentsAgent agent, JsonMapper json) {
        this(store::read, intake::ingest, new CommitmentsAgUi(agent::answer, json));
    }

    LiveCommitmentsHttp(Function<Instant, LiveCase.Snapshot> read,
                        Function<LiveCase.Upload, LiveCase.Intake> ingest, CommitmentsAgUi agUi) {
        this.read = read;
        this.ingest = ingest;
        this.agUi = agUi;
    }

    @GetMapping("/")
    ResponseEntity<Void> home() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/live.html")).build();
    }

    @GetMapping(AGREEMENT)
    LiveCase.Snapshot view(@RequestParam(name = "at", required = false) String at) {
        return read.apply(CommitmentsAgUi.parseInstant(at));
    }

    @PostMapping(path = AGREEMENT + "/sources", consumes = MediaType.APPLICATION_JSON_VALUE)
    LiveCase.Intake source(@RequestBody LiveCase.Upload upload) {
        if (upload.title() == null || upload.title().isBlank() || upload.title().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a source title of at most 160 characters.");
        }
        if (upload.text() == null || upload.text().isBlank() || upload.text().length() > 16_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter source text of at most 16000 characters.");
        }
        if (upload.kind() == null || !LiveCase.SOURCE_KINDS.contains(upload.kind())
                || upload.replacesClaimId() != null && upload.replacesClaimId().length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check the source type and selected commitment.");
        }
        try {
            return ingest.apply(upload);
        } catch (ResponseStatusException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "DICE could not extract claims. Check the source type, text, and selected commitment.");
        } catch (RuntimeException failure) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "The source could not be reviewed. Check the source and your model configuration, then try again.");
        }
    }

    @PostMapping(path = AGREEMENT + "/agent", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    ResponseEntity<StreamingResponseBody> run(@RequestBody CommitmentsAgUi.RunAgentInput input) {
        var run = CommitmentsAgUi.validate(input);
        var snapshot = read.apply(run.at());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .body(agUi.stream(run, snapshot));
    }

    @ExceptionHandler(AuditCoverageException.class)
    ResponseEntity<ProblemDetail> uncovered() {
        var body = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "DICE has no complete belief set for this time.");
        return problemResponse(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<ProblemDetail> problem(ResponseStatusException failure) {
        return problemResponse(failure.getBody());
    }

    private static ResponseEntity<ProblemDetail> problemResponse(ProblemDetail body) {
        return ResponseEntity.status(body.getStatus()).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(body);
    }
}
