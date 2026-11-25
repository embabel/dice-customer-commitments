# DICE Customer Commitments

An account lead is about to give a customer a date. The contract, account email, and signed amendment each suggest a different answer. Which one can the lead stand behind, and what would they have said before the amendment arrived?

Customer Commitments is a standalone Java application that combines DICE extraction and durable memory, an Embabel conversational agent, a decision model, and an AG-UI web client. Paste a source, inspect how the application treated each claim, and ask follow-up questions with citations to the original passages.

## Run the live application

Use Java 21 and set `OPENAI_API_KEY` in your environment.

```bash
./mvnw -B verify
java -jar target/dice-customer-commitments.jar --spring.profiles.active=live
```

Open the [live workbench](http://127.0.0.1:8082/live.html). The application starts with an empty agreement. It binds to loopback and sends your source text, questions, and selected evidence to the configured model providers.

| Setting | Default | Purpose |
| --- | --- | --- |
| `COMMITMENTS_MODEL` | `gpt-4.1-mini` | Chat model for DICE extraction and the Embabel agent |
| `COMMITMENTS_DECISION_MODEL` | Same as the chat model | Decision provider for classification of intake claims |
| `COMMITMENTS_DB` | `./data/customer-commitments-live.sqlite` | SQLite file to reopen after restart |
| `COMMITMENTS_PORT` | `8082` | Local HTTP port |

### Try one agreement

1. Add a **Signed agreement** titled **Migration addendum**. Confirm that you reviewed the signed document. Paste the following sample as the source.

   > This addendum is signed by Shoreline Labs and Cedar Transit. Shoreline Labs commits to migrate Cedar Transit's dispatcher accounts by January 15, 2027. Cedar Transit commits to deliver its security checklist by January 12, 2027.

2. Add an **Email** titled **Earlier cutover request** with the following text. Review the classification and application decision, then ask **Did we agree to cut over on January 9?** Follow up with **What date should I tell them?** Open an answer citation to verify the source passage.

   > Could Shoreline Labs move our dispatcher account migration to January 9, 2027? Please confirm whether you can commit to that date.

3. Add a **Signed amendment** titled **Revised migration schedule**. Confirm that you reviewed the signed amendment and select the migration commitment it replaces. Paste the following text. Ask **What changed, and which deadline stayed in place?**

   > This amendment is signed by Shoreline Labs and Cedar Transit. It replaces the January 15, 2027 dispatcher-account migration deadline with January 22, 2027. All other obligations in the addendum are unchanged.

4. Choose the checkpoint for the original addendum in **Agreement record** and ask **What was the migration date in this record?** Open the citation. The earlier view still has the January 15 date and cites the original addendum.

### Use Jev for intake decisions

The default uses Embabel's prompted decision provider. To run the same classification through TypeSafe Jev, package the optional starter and set `TYPESAFE_API_KEY` in addition to `OPENAI_API_KEY`.

```bash
./mvnw -B -Pjev -DskipTests package
COMMITMENTS_DECISION_MODEL=jev-latest \
  java -jar target/dice-customer-commitments.jar --spring.profiles.active=live
```

Jev classifies claims. The chat model still extracts propositions and powers the conversational agent. The workbench shows the provider alongside the classification.

## Follow the application flow

| Concern | Integration |
| --- | --- |
| **Source intake** | [LiveIntake](src/main/java/example/dice/commitments/LiveIntake.java) runs the DICE proposition pipeline over the submitted passage and collects grounded propositions before opening a write transaction. |
| **Classification** | [CommitmentDecision](src/main/java/example/dice/commitments/CommitmentDecision.java) asks an Embabel `DecisionService` choice question to distinguish commitments, amendments, requests, targets and reminders. |
| **Authority and audit** | [LiveCaseStore](src/main/java/example/dice/commitments/LiveCaseStore.java) applies the app's authority rules and persists the propositions, decision results and immutable evidence together. Historical reads replay the DICE belief set at the selected transaction time. |
| **Grounded conversation** | [LiveCommitmentsAgent](src/main/java/example/dice/commitments/LiveCommitmentsAgent.java) uses an Embabel plan and the selected DICE snapshot. The prompt includes the conversation and the supporting record. The app validates citation IDs against that snapshot. |
| **AG-UI** | [LiveCommitmentsHttp](src/main/java/example/dice/commitments/LiveCommitmentsHttp.java) accepts an AG-UI `RunAgentInput`. The browser consumes the run, state and text events and connects answers to the evidence reader through a citation event. |

The attestation records the local operator’s review of a signed document. An amendment also needs a selected active commitment and a matching extracted amendment claim. An email carrying a proposed date remains useful evidence without acquiring authority to change the agreement. The application uses one scoped agreement and one local operator. An adopter serving multiple users should derive the agreement scope from access control. Reuse the SQLite file to retain claims and citations across restarts. The browser sends earlier chat turns with each follow-up.

DICE canonicalizes identical propositions. The intake result records the model and policy decisions for that receipt. When a new claim matches an existing proposition, the app preserves the original claim’s authority and supersession history.

## Run the recorded walkthrough

Launch the default build without a profile or model credentials.

```bash
java -jar target/dice-customer-commitments.jar
```

Open the [recorded case](http://127.0.0.1:8082). It uses its own database at `./data/customer-commitments.sqlite`, a fixed clock, and recorded model replies. **Import signed amendment** replaces the fixture's migration claim while preserving the earlier answer in **Opening record**.

![Recorded walkthrough showing the revised migration date beside the signed amendment](https://github.com/embabel/dice-customer-commitments/releases/download/readme-screenshots/amendment-desktop.png)

## Verify

```bash
./mvnw -B verify
bun install --frozen-lockfile
bunx playwright install chromium
bun run test:browser
COMMITMENTS_BROWSER_MODE=live bun run test:browser
```

The live workflow integration test uses MockMvc and a scripted test-classpath model to exercise the real Embabel agent, DecisionService, DICE and SQLite over AG-UI without opening a port. The live browser test uses the same fixture provider against the running app. It requires no provider keys. The recorded test launches the packaged application. Both require permission to listen on a local port. Set `COMMITMENTS_ARTIFACTS` to an output directory to capture screenshots.

The application uses the DICE 0.5.0 snapshot and Embabel Agent 1.5.4 from the repositories in [pom.xml](pom.xml).
