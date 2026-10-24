# Threat model (draft 1, 2026-10-23)

Status: draft for the project owner's review. Nothing here has had an independent security review.

## Product in one line
An Android-only, serverless family chat app with location sharing. Phones talk to each other directly over Tor, relay for each other inside a group, and can optionally use a self-hosted mailbox for offline delivery.

## Assets worth protecting
1. Location history of each member (most sensitive: reveals home, work, routines).
2. Group membership and who talks to whom.
3. Message content.
4. Long-term identity keys and group keys.
5. The on-device database (messages, locations, contacts).
6. Mailbox contents and the mailbox owner's server.

## Adversaries
| ID | Adversary | Capability we assume |
|----|-----------|----------------------|
| A1 | Network observer (ISP, Wi-Fi owner, state-level) | Sees and can block or alter internet traffic. Cannot break standard crypto. |
| A2 | Curious or malicious group member | Holds valid keys, runs modified software, relays messages, replays old ones. |
| A3 | Removed ex-member | Had keys in the past, still has a copy of old data, may still reach other members' phones. |
| A4 | Mailbox operator or someone who compromises the mailbox server | Reads everything stored there and all connection timing. |
| A5 | Thief or someone who seizes a phone | Physical access, locked or unlocked, possibly forensic tools. |
| A6 | Coercive person inside the group (abusive partner, controlling relative) | Legitimately in the group; wants to track someone who does not want to be tracked. |
| A7 | Malicious app or malware on the same phone | Normal Android app privileges, possibly accessibility or overlay abuse. |
| A8 | Supply-chain attacker | Compromised dependency, build, or update. |

## Assumptions
- Standard cryptographic primitives hold; we use only well-reviewed libraries and never hand-rolled crypto.
- The user's phone is not already fully compromised by an attacker with root.
- Contacts are added in person (QR code) or via an equivalent verified exchange.
- Tor remains available and reasonably well-behaved. If Tor is blocked, the app degrades to Wi-Fi/Bluetooth direct sync and relaying, and says so.

## Out of scope for the first version
- Anonymity from contacts (contacts can learn network details of each other; we aim for unlinkability from outsiders, not anonymity).
- Protection against a fully rooted or backdoored device.
- iOS, multi-device accounts, cloud backup.
- Traffic-volume analysis by a global passive adversary.

## Threats and design response
| # | Threat | Design response | Status |
|---|--------|-----------------|--------|
| T1 | A1 reads or alters messages | End-to-end encryption inside Tor; authenticated encryption on every record | Designed (spec 01) |
| T2 | A1 learns who talks to whom | Per-contact onion addresses; Tor hides IPs; no central server | Designed |
| T3 | A2 forges another member's location | Every location record is signed by the sender's identity key; group key alone is not enough to impersonate | Designed (spec 05) |
| T4 | A2 or relay replays old locations | Signed timestamp plus per-sender sequence number; receivers reject old or duplicate values | Designed |
| T5 | A3 keeps reading after removal | New group key epoch on every removal, distributed only to remaining members over pairwise channels; old key never reused | Designed (spec 04); needs test and review |
| T6 | A3 reads history they already had | Cannot be prevented (they saw it). Reduce impact with short retention and automatic expiry | Accepted risk, mitigated |
| T7 | A4 reads mailbox content | Mailbox stores ciphertext only; keys never leave devices | Designed (spec 06) |
| T8 | A4 learns metadata (timing, sizes, who connects) | Mailbox is onion-only, per-contact random tokens, padded records, minimal logs; residual leakage documented | Partly mitigated |
| T9 | A4 fills or abuses the mailbox | Per-token quotas, rate limits, expiry of undelivered items | Designed |
| T10 | A5 reads data from a locked phone | Database encrypted at rest with a key protected by Android Keystore; app lock; no plaintext in logs or notifications | Designed; verify on device |
| T11 | A5 with unlocked phone | Optional app lock timeout; quick "panic wipe"; short retention limits what exists | Designed |
| T12 | A6 covertly tracks someone | **No covert mode.** A persistent, non-dismissable notification shows when sharing is on. Sharing is opt-in per person and per group, and the sharee can pause or stop at any time without notifying the group. Periodic reminders show who can currently see you. Exact precision is a setting the sharer controls. | Designed; this is a product rule, not optional |
| T13 | A6 forces someone to join or stay | Leaving a group is one tap and takes effect immediately on the leaver's side (they stop sending) | Designed |
| T14 | A7 reads screen or overlays prompts | Block screenshots in sensitive views; touch-filtering against overlays; known issue from upstream audit | Designed; verify |
| T15 | A8 poisons a dependency | Pinned versions, license and vulnerability scan in CI, reproducible build goal, signed releases | Planned (M0/M6) |
| T16 | Malformed input from a peer crashes or exploits the parser | Strict length limits, fuzzing, memory-safe language for parsers | Planned (M1) |
| T17 | Location leaks via logs, crash reports or analytics | No analytics, no third-party crash reporting, no location in logs | Rule |
| T18 | Battery-saver kills the app so safety updates stop silently | Always show "last updated N min ago"; warn the user when background limits are blocking updates | Designed |

## Rules that follow from this model
1. No third-party servers required, ever. The mailbox is optional and owner-run.
2. No secret, key, or personal data in the repository.
3. Every new feature gets the security and serverless checklist before merge.
4. Anything touching crypto or group membership needs an independent review before release.
5. Public release waits for an external security audit.

## Open questions
- Do we want a duress mode (looks like normal sharing but shows a fake location)? It helps in coercion cases but raises its own risks and design questions. Decide before M4.
- Which contacts can be introduced by other contacts, if any? Introductions weaken in-person verification.
