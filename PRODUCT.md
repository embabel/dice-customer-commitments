# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Stack

Java 21 and Spring Boot serve a same-origin HTML, CSS, and JavaScript application. DICE stores propositions and audit records in SQLite. Offline extraction replays recorded model responses.

## Users

Customer onboarding and account leads reconstructing an agreed delivery schedule before they give the customer a status update.

## Product Purpose

Help an operator distinguish what each party committed to from dates mentioned as an aspiration or request. The operator can inspect the passages behind the claims, take a signed amendment into account, and recover the opening agreement.

## Positioning

The application starts from a call transcript, email, and signed document excerpts. DICE extracts claims with source provenance. The example applies an explicit agreement rule to the extracted claims and replays the belief set at an earlier transaction time.

## Operating Context

Tandem Transit needs to migrate its depot accounts. A sales target and a customer request both name dates earlier than the signed schedule. An amendment changes Rivet Systems’ migration date while leaving Tandem’s security outputs due on the original date.

## Capabilities and Constraints

All documents and parties are synthetic. The recorded model covers the supplied passages. The agreement rule accepts only explicit signed commitments and authorized amendments. The tool displays a readout for an agreement owner. Copying excerpts into the application does not create an agreement.

## Product Principles

- Show the committing party, deliverable, and date in the first view.
- Let the user inspect the exact source passage.
- Preserve possibilities and requests beside commitments so the user can tell them apart.
- Keep historical replay within the captured evidence horizon.
