# Thornberry

An Android-only, serverless family chat and location-sharing app. Phones exchange end-to-end encrypted messages directly over Tor, relay for each other inside a group, and can optionally use a self-hosted mailbox for offline delivery. No company server, no accounts, no analytics.

Status: early design (milestone M0). Nothing here is implemented or security-reviewed yet. Do not rely on it for safety.

## Documents
- [Threat model](docs/threat-model.md)
- [Architecture and protocol draft](docs/architecture.md)
- [Tor and battery spike plan](docs/spike-plan.md)

## Rules for this repository
- No keys, tokens, or personal data are ever committed.
- No hand-rolled cryptography; vetted libraries only.
- Every change gets a security and serverless review (see the checklist in the project notes).
- No public release before an independent security audit.

License: not yet chosen (depends on a legal check of upstream specs and dependencies).
