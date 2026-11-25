package example.dice.commitments;

import com.embabel.dice.proposition.Proposition;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class LiveCase {

    static final String CONTEXT = "customer-commitments:live";
    static final Set<String> SOURCE_KINDS = Set.of(
            "SIGNED_AGREEMENT", "SIGNED_AMENDMENT", "EMAIL", "CALL", "ASSISTANT_HANDOFF");

    private LiveCase() {
    }

    static String sourceId(Upload upload) {
        // The title is a display label. The document and the operator's attestation identify a receipt.
        var identity = "%s:%d:%s:%s:%s".formatted(upload.kind(), upload.text().length(), upload.text(),
                upload.signedAttested(), Objects.toString(upload.replacesClaimId(), ""));
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return "source-" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    public record Upload(String title, String kind, String text, String replacesClaimId, boolean signedAttested) {
        public Upload(String title, String kind, String text, String replacesClaimId) {
            this(title, kind, text, replacesClaimId, false);
        }
    }

    public record Source(String id, String title, String kind, String text, Instant receivedAt) {
    }

    public record Claim(String id, String text, String classification, String status, String outcome, String reason,
                        String decisionProvider, String replacesClaimId, Source source) {
    }

    public record Snapshot(Instant asOf, Instant baseline, boolean historical, List<Claim> claims) {
        public Snapshot {
            claims = List.copyOf(claims);
        }
    }

    public record Intake(Snapshot snapshot, List<Claim> claims) {
        public Intake {
            claims = List.copyOf(claims);
        }
    }

    public record Candidate(Proposition proposition, String classification, String provider) {
    }
}
