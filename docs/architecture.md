# Architecture and protocol draft (draft 1, 2026-10-23)

Status: design proposal, not implemented, not reviewed. Primitive choices are proposals and must be confirmed against currently maintained Android libraries (decision D2) and independently reviewed (D3). We do not copy Briar code; this is an original design informed by Briar's published goals. The Bramble transport and rendezvous specs were not available when this was drafted, so nothing here claims to match them.

## Goals and constraints
- Android only. Kotlin. Protocol core is a pure Kotlin/JVM module with no Android dependencies, shared by the app and the mailbox server.
- Serverless by default. Optional owner-run mailbox.
- Location freshness: usually a few minutes, 10 minutes maximum while a path exists.
- Small family groups (target up to about 20 members).

## Components
1. **Identity**: each person has a long-term signing key pair (Ed25519 proposed) and a key-agreement key pair (X25519 proposed).
2. **Contact pairing**: in person via QR code containing public keys, a per-contact onion address, and a random pairing secret. Both phones show a short verification code to compare. No server involved.
3. **Transport**: an authenticated, encrypted channel between two paired phones, carried over Tor onion services (one onion address per contact) and optionally Wi-Fi or Bluetooth when nearby. A Noise-framework handshake with the paired static keys is proposed, giving mutual authentication and forward secrecy. Records are length-prefixed, padded to fixed size classes, and capped at a maximum size.
4. **Sync**: store-and-forward of small immutable records grouped by group. Peers exchange what they have, what they offer, and what they want (summaries first, then bodies). Dependency ordering is only required for chat; location records are independent and last-writer-wins per sender.
5. **Groups**: a group has an admin set, a member list, and a current **key epoch**.
6. **Location records**: see below.
7. **Mailbox** (optional): see below.
8. **Local storage**: encrypted database with a key held in the Android Keystore.

## Group keys and removal (highest-risk part)
- Each epoch has a random symmetric group key.
- Messages and locations are encrypted with the epoch key (XChaCha20-Poly1305 proposed) and signed by the sender's identity key, so holding the group key is not enough to impersonate someone.
- **Adding a member**: send the current epoch key and member list to the newcomer over their pairwise channel. A new member does not receive history by default (optional, admin-chosen, off by default).
- **Removing a member or someone leaving**: an admin creates a new epoch, generates a fresh key, and sends it individually to each remaining member over pairwise channels, along with a signed membership update. Everyone switches immediately; the old key is never used again for sending.
- **Gaps**: members who are offline when rotation happens get the new key when they next sync. Until then they cannot decrypt new traffic. The UI shows "waiting for group update".
- **Race conditions** to test: simultaneous admin actions, a removed member relaying stale messages, a member who misses two rotations in a row.
- **Compromise recovery**: any member can request a rotation if they believe their phone was seized; admins approve. Rotation is also scheduled periodically (proposed: on any membership change, and every 30 days).

## Location record (payload, before encryption)
| Field | Notes |
|-------|-------|
| version | format version |
| sender id | public key hash |
| epoch | group key epoch used |
| sequence | per-sender counter, rejects replays |
| timestamp | sender clock, signed; receivers also record their own receive time |
| lat, lon | quantized to the precision the sender chose (exact, about 100 m, about 1 km, city, or paused) |
| accuracy | meters |
| expires_at | after this time receivers must delete it |
| optional | speed, battery percent (off by default) |

Records are padded to fixed size classes so length does not leak whether optional fields are present. Maximum size is small (a few hundred bytes).

**Update policy** (to be tuned by the spike): moving, about every 2 to 5 minutes; stationary, a heartbeat at most every 10 minutes; immediate update on entering or leaving a saved place; adaptive to battery level.

**Retention defaults** (proposed, user-configurable downward): keep the latest position and a 24 hour trail. Records carry their own `expires_at`; the app deletes expired data on a schedule and on app start. Relays hold records only until expiry or until acknowledged by all members they know of.

**Geofence alerts** ("arrived at school") are computed on the receiving device from the received locations. No server evaluates places.

## Relaying inside a group
- Every member's phone may forward group records to other members it is paired with.
- Relays see group identifier, record identifiers, sizes and timestamps for the groups they are in. They cannot read content from outside the group, and they cannot forge records because of sender signatures.
- Forwarding rule: forward only records not expired and not already acknowledged by the target. Per-record hop limit and per-peer rate limit prevent flooding.
- Staleness is always shown: "last updated N min ago" per member.

## Mailbox (optional, owner-run)
- A small Kotlin/JVM server in a container, reachable only as a Tor onion service. No inbound port opened on the host.
- API (proposed, minimal): `POST /drop` (store an opaque blob for a recipient token), `GET /pickup` (fetch blobs for my token), `POST /ack` (delete what I fetched). Requests are authenticated with per-contact random tokens issued at pairing time. The mailbox has no accounts, no names, no group IDs.
- Blobs are end-to-end encrypted to the recipient and padded. The mailbox sees sizes, timing and token activity only.
- Controls: per-token storage quota, rate limit, maximum blob age (undelivered blobs expire), minimal logging (off by default), no analytics.
- Deployment: Docker/Compose file, non-root user, read-only filesystem where possible, no other services reachable from it. Updates by pulling a new image.
- The app works fully without a mailbox; with one, offline delivery gaps shrink.

## Tor-on-Android candidates (input to D2, checked 2026-10-23, not yet tested)
| Option | What the sources say | Gaps |
|--------|----------------------|------|
| Onion Wrapper (Briar project) | Java library wrapping a Tor client for Android, Linux and Windows; can run hidden services; uses a wake-lock helper to cope with manufacturer background restrictions; Tor binaries come from separate Maven artifacts | License and development status not stated on the page we read; must verify before depending on it |
| tor-android (Guardian Project) | Native Android Tor service built on Tor's shared library; Maven coordinate `info.guardianproject:tor-android` at Tor 0.4.9.12 per its page | Page does not say whether hosting onion services is supported or give a license name; release date not shown |
| Arti-Mobile-Ex (Arti, Rust Tor) | Builds Arti into Android apps; documentation shows client-side proxy use | Page says nothing about hosting onion services or production readiness; treat as unproven for our need |

The spike should start with the first two, since we need to host an onion service on each phone. Licenses must be confirmed (D7) before any code depends on them.

## Things we are deliberately not doing
- No push notification service (it would leak metadata to a third party and require a server).
- No cloud backup, no analytics, no crash reporting that includes data.
- No account system, phone-number or email lookup.

## Open decisions
| ID | Question | Needed by |
|----|----------|-----------|
| D1 | Is the 10-minute target achievable with Tor on Android background limits? | End of M0 spike |
| D2 | Which Tor-on-Android and crypto libraries (must be maintained, licensed compatibly, buildable offline-reproducibly)? | Start of M1 |
| D3 | Independent review of key rotation and the handshake before location ships | End of M1 |
| D4 | External audit before any public release | Before release |
| D5 | Who can be a group admin, and can a non-admin trigger a rotation? | M3 |
| D6 | Duress or decoy-location mode: yes or no? | Before M4 |
| D7 | Licensing of Briar's published specs and our own license choice | M0 legal check |
