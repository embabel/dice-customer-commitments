# DICE Customer Commitments

A customer asks to move a depot migration forward. Sales mentioned an earlier target. The signed onboarding addendum gives Rivet Systems until 25 November. Later, a signed amendment changes that date to 2 December and leaves Tandem Transit’s security checklist due on 20 November.

Customer Commitments turns the original passages into DICE propositions. Ask the bot what was promised, follow its citations, and replay the answers from the opening agreement. The bot can also separate Maya’s spoken runbook commitment from the reminder her personal assistant recorded.

## What you’ll see

After you import the signed amendment, ask **What changed?** The bot shows that migration moved from 25 November to 2 December and the security checklist kept its 20 November deadline. Open a citation to read the signed passage beside the answer.

![Commitments bot showing the revised migration date beside the signed amendment](https://github.com/embabel/dice-customer-commitments/releases/download/readme-screenshots/amendment-desktop.png)

Select **Opening record** to return to the original agreement. The source panel can also show the assistant handoff behind the runbook promise and the Saturday cutover reminder.

<details>
<summary>Mobile view</summary>

![Mobile opening record showing the original signed dates and the handoff citation](https://github.com/embabel/dice-customer-commitments/releases/download/readme-screenshots/opening-record-mobile.png)

</details>

## Run the demo

Use Java 21.

```bash
./mvnw -B verify
java -jar target/dice-customer-commitments.jar
```

Open [Customer Commitments](http://127.0.0.1:8082). The app runs on loopback, stores the illustrative agreement in `data/customer-commitments.sqlite`, and uses recorded extraction replies. It needs no model credentials. Set `COMMITMENTS_PORT` to change the port or `COMMITMENTS_DB` to point at a fresh SQLite file for another walkthrough.

## Walk through one agreement

1. Ask **What did we promise?** The bot gives the signed dates and cites the original onboarding addendum.
2. Open the **Call transcript** and **Customer email**. In the claims list, the 20 November sales target and 23 November request are separate from the 25 November agreement.
3. Ask **What’s open?** The personal assistant handoff supports a 21 November runbook promise and a separate task to ask about a Saturday cutover. It records no agreed Saturday date.
4. Select **Import signed amendment**. Ask **What changed?** The bot cites the prior and replacement migration claims and the unchanged security deadline.
5. Select **Opening record** and ask **What did we promise?** again. The earlier answer still cites 25 November and the original passage.

## How it works

`CaseFile.java` contains the free-text passages and their source classes. `RecordedExtraction.java` plays back the corresponding model responses. A recorded response is keyed by the hash of its source passage, so changing the passage requires a fresh recording. The Embabel model adapter and DICE `PropositionPipeline` parse the responses into propositions. DICE grounds each proposition in the supplied passage, and the example checks that grounding before storing the claim.

`Commitments.java` owns the agreement rule. A target and a request can be true descriptions of what was discussed while carrying no authority to revise the signed schedule. The amendment can supersede the migration proposition because its source is signed and its claim concerns that specific obligation. The replacement also records the original proposition as a superseded predecessor.

`CommitmentStore.java` uses a complete transaction-time baseline, exact-context reads, and DICE’s audited proposition repository with the Drivine SQLite store. The initial source register is immutable. Replay joins tracked proposition IDs back to that register for citations. A request outside the recorded audit horizon returns HTTP 422. The demo uses a fixed scenario clock so the same evidence produces the same walkthrough.

The bot supports three case questions. It answers from the propositions in the chosen view and returns the same snapshot with their citation IDs. A bot answer and its evidence stay aligned if another tab imports the amendment. It tells the user which questions it supports when the request falls outside that set.

## Verify

`./mvnw -B verify` starts the real HTTP service against temporary SQLite files. It tests the extraction, amendment, idempotent import, restart, replay coverage, and bot citations.

To test the browser against the packaged application, use Bun and install Chromium once.

```bash
bun install --frozen-lockfile
bunx playwright install chromium
bun run test:browser
```

The browser test asks the bot, follows both signed citations, and switches to the archived agreement at a mobile viewport. Set `COMMITMENTS_ARTIFACTS` to a directory outside the repository to capture desktop and mobile screenshots.

The application uses the DICE 0.5.0 snapshot and Embabel Agent 1.5.4. The Maven wrapper resolves them from the repositories declared in `pom.xml`.
