# Decision D2: crypto libraries and message-layer approach

Status: APPROVED by the owner 2026-10-25. Wrapper and known-answer tests implemented (commit after a0e0892); all pass in CI. Independent review (D3) is still required before location sharing ships. The ratchet, group layer and storage are not yet implemented.

## What the design needs
1. Pairwise sessions set up in person (QR code), so mutual authentication comes from the pairing, not a server.
2. Messages that can be stored and forwarded (relays, mailbox), so encryption must work when the recipient is offline. A live handshake is not enough.
3. Forward secrecy for pairwise messages, and a way to change group keys when a member is removed.
4. Signed records (Ed25519 proposed) so a holder of a group key still cannot impersonate another member.
5. One crypto code base for the Android app and the optional mailbox server (pure JVM), working on Android 11 and later.
6. Licenses compatible with GPL-3.0 (we already depend on GPL-3.0 Onion Wrapper), actively maintained.

## Options compared
| Option | Findings (checked 2026-10-25) | Verdict |
|---|---|---|
| **libsignal** (Signal) | AGPL-3.0. The README says "Use outside of Signal is unsupported" and "All APIs and implementations are subject to change without notice." Android builds need JDK 21 and Rust native libraries; current builds are hosted on Signal's own Maven repository, and the copy on Maven Central is version 0.86.5 from 2025-11-17. Would also need native libraries on the mailbox server. | Do not use. No support path, unstable API, stale copy on Central |
| **Bouncy Castle** (lightweight API) | Version 1.86, 2026-09-11, "Bouncy Castle Licence" (MIT style). Pure Java. Jar inspected: contains X25519Agreement, Ed25519Signer, XChaCha20Poly1305, ChaCha20Poly1305, HKDFBytesGenerator, Blake2bDigest. 7.2 MB (shrinkable on Android). | **Recommended** |
| **Tink** (Google) | Version 1.24.0, 2026-10-16, Apache-2.0, pure Java, Android build available. Safe high-level AEAD and Ed25519. X25519 and HKDF are only in its low-level `subtle` classes (inspected: present). | Good alternative; no simpler than Bouncy Castle for our needs |
| **Lazysodium** (libsodium bindings) | Version 5.2.0, 2025-05, MPL-2.0. Uses JNA and native libraries per platform. | Pass: extra native surface, slower maintenance |
| **Android platform crypto** | Conscrypt's capability list shows AES-GCM and ChaCha20-Poly1305, but no Ed25519 or X25519 key agreement (X25519 only for TLS). Unclear below Android 13. | Use only for AES-GCM and the Keystore |
| **Noise libraries** | `noise-java` is not on Maven Central. No maintained Android-ready library evaluated. | Not needed (see below) |
| **signal-protocol-java** | Version 2.8.1, 2019, GPLv3. Abandoned. | No |

## Recommendation
1. **Primitives:** Bouncy Castle 1.86 lightweight API (`org.bouncycastle.crypto.*`) used directly, not through Android's built-in "BC" provider, which avoids name clashes with the older copy in the system.
2. **Thin internal wrapper:** the rest of the code only sees our own small interface (sign, verify, key agreement, AEAD, key derivation, random). This keeps the library swappable and gives reviewers one small file to audit.
3. **Message layer: our own, built on published designs.** Pairwise sessions use the published Double Ratchet algorithm, started from the keys exchanged in the QR pairing. Group traffic uses the per-epoch group key from architecture.md, with every record signed by its sender. This matches the earlier decision to reimplement the protocols for tuning. It is also the highest-risk component, so the independent review (D3) is mandatory.
4. **Drop the proposed Noise handshake over Tor.** Tor already authenticates the onion service endpoint, and the message layer gives end-to-end encryption that also works for store-and-forward. One less custom protocol to get wrong. The listener cannot tell who is connecting, so the message layer must reject anything that does not authenticate (dropped before any processing).
5. **Local storage:** wrap the database key with an Android Keystore key (AES-GCM is supported by the platform). Database approach to be chosen at the start of M1.
6. **Required tests:** known-answer tests from RFC 7748 (X25519), RFC 8032 (Ed25519), RFC 5869 (HKDF), RFC 8439 (ChaCha20-Poly1305), and the XChaCha20-Poly1305 draft vectors; ratchet tests for reordered, dropped, duplicated and replayed messages and for saving and restoring state; fuzzing of every parser.
7. **Licensing:** Bouncy Castle's licence is MIT style and compatible with GPL-3.0.

## Risks and caveats
- Bouncy Castle's lightweight API is low level, so misuse is possible. Mitigation: the wrapper, the known-answer tests, and review.
- A custom ratchet is the most likely place for a serious bug. Mitigation: follow the published specification closely, test heavily, and do not ship location until reviewed.
- Bouncy Castle has had security advisories before: track them and keep the version current.
- Not yet verified: that Bouncy Castle's XChaCha20-Poly1305 matches the draft vectors, and that its classes run on Android 11 and later in our build. Both are quick checks in CI at the start of M1.

## Idea for later hardening (not verified)
Tor onion services can require client authorization keys so that only paired contacts can even see a service's descriptor. If Onion Wrapper's control-port support allows it, this would hide each contact's onion address from everyone else. Needs checking.

## Sources
- libsignal README: https://github.com/SignalApp/libsignal-client
- Conscrypt capabilities: https://android.googlesource.com/platform/external/conscrypt/+/83dfc957/CAPABILITIES.md
- Tink supported key types: https://developers.google.com/tink/supported-key-types
- Versions, licenses and class lists: read from Maven Central by a CI workflow run on 2026-10-25.
