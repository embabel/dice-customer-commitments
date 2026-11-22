package example.dice.commitments;

import java.util.List;

final class CaseFile {

    static final String CONTEXT = "onboarding:tandem-transit";
    static final Passage SALES = new Passage("sales-call", "Call transcript", "Discovery call", "12 Nov · 10:24",
            "Sales call · transcript excerpt 03", Authority.SALES,
            """
            Maya, Rivet Systems: We are aiming to move the depot accounts by 20 November 2026, but that is not a commitment. The schedule depends on your SSO setup. We’ll put an agreed date in the addendum.
            """.strip());
    static final Passage ADDENDUM = new Passage("signed-addendum", "Signed addendum", "Onboarding addendum", "14 Nov · 16:05",
            "ADD-01 · signed by both parties", Authority.SIGNED,
            """
            Rivet Systems will migrate Tandem Transit’s depot accounts no later than 25 November 2026. Tandem Transit will deliver the SSO security checklist by 20 November 2026. Delivery of that checklist is a prerequisite for the migration.
            """.strip());
    static final Passage CUSTOMER = new Passage("customer-email", "Customer email", "Maintenance window request", "17 Nov · 08:31",
            "Email from Morgan Lee · onboarding thread", Authority.CUSTOMER,
            """
            We have a maintenance window on the 23rd. Could you bring the migration forward to 23 November 2026? Please let me know whether Rivet can commit to that date.
            """.strip());
    static final Passage ASSISTANT = new Passage("assistant-handoff", "Assistant handoff", "Maya’s account handoff", "18 Nov · 08:43",
            "Synthetic personal assistant · recorded account notes", Authority.ASSISTANT,
            """
            Maya told Tandem on the follow-up call: “I’ll send you the migration runbook by 21 November 2026.” Reminder for Maya: ask whether a future Saturday cutover might work. No Saturday cutover date was agreed.
            """.strip());
    static final Passage AMENDMENT = new Passage("signed-amendment", "Signed amendment", "Schedule amendment", "18 Nov · 15:22",
            "ADD-02 · signed by both parties", Authority.SIGNED,
            """
            The parties extend Rivet Systems’ deadline for the depot account migration to 2 December 2026. All other commitments and readiness dates in ADD-01 remain unchanged.
            """.strip());

    private CaseFile() {
    }

    static List<Passage> opening() {
        return List.of(SALES, ADDENDUM, CUSTOMER, ASSISTANT);
    }

    static List<Passage> passages() {
        return List.of(SALES, ADDENDUM, CUSTOMER, ASSISTANT, AMENDMENT);
    }

    enum Authority {
        SALES, SIGNED, CUSTOMER, ASSISTANT
    }

    record Passage(String id, String type, String title, String received, String reference, Authority authority,
                   String passage) {
    }
}
