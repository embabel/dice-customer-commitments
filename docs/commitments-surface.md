# Commitments workbench

The live workbench shows the signed schedule, the agreement assistant, and the source evidence for the selected record. Use **Add source** to review a pasted agreement, email, call transcript or handoff. The source receipt keeps the passage and the decisions made about its claims.

Ask a question in your own words. An answer's citations open the passages on file for that turn. If the record cannot answer the question, the assistant offers an agreement question it can answer. Selecting another checkpoint starts a fresh conversation with only the evidence available at that time. Switch back to **Current record** to add a source.

To record a signed amendment, select the active commitment it replaces and attest that you reviewed the signed document. The current schedule shows any accepted replacement. The earlier record retains the prior commitment and its citations.

The recorded walkthrough uses a preloaded agreement and a separate SQLite file. It has guided questions, source citations and an **Opening record** checkpoint. [Run either workflow](../README.md)


The signed schedule is accompanied by an amendment comparison. It follows the signed replacement's link to the superseded claim and shows both original excerpts through the evidence reader. The source receipt is also a checkpoint. **Open record at this source** loads the corresponding historical agreement and starts a conversation scoped to that snapshot.

Maven packages the live and recorded TypeScript entry points with fingerprinted Vite assets. The live client uses the official AG-UI HTTP agent and the recorded walkthrough uses its fixture endpoints. Both modes serve from port 8083 by default.
