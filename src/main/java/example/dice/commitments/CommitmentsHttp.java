package example.dice.commitments;

import com.embabel.dice.audit.AuditCoverageException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@RestController
@Profile("!live")
@RequestMapping("/api/commitments")
class CommitmentsHttp {

    private final Commitments commitments;

    CommitmentsHttp(Commitments commitments) {
        this.commitments = commitments;
    }

    @GetMapping
    Commitments.View view(@RequestParam(required = false) Instant at) {
        return commitments.view(at);
    }

    @PostMapping(path = "/amendment", consumes = MediaType.APPLICATION_JSON_VALUE)
    Commitments.View amend(@RequestBody Amendment amendment) {
        if (!CaseFile.AMENDMENT.id().equals(amendment.documentId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Select the recorded signed amendment.");
        }
        return commitments.amend();
    }

    @PostMapping("/ask")
    Commitments.Answer ask(@RequestBody Question question) {
        if (question.text() == null || question.text().isBlank() || question.text().length() > 360) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter a question between 1 and 360 characters.");
        }
        var previous = question.previousQuestions();
        if (previous != null && (previous.size() > 8 || previous.stream().anyMatch(text -> text == null || text.length() > 360))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Send at most eight earlier questions of up to 360 characters each.");
        }
        return commitments.ask(question.text(), question.at(), previous);
    }

    @ExceptionHandler(AuditCoverageException.class)
    ProblemDetail uncovered() {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,
                "DICE has no complete belief set for this time.");
    }

    record Amendment(String documentId) {
    }

    record Question(String text, Instant at, List<String> previousQuestions) {
    }
}
