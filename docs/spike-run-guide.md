# Spike run guide (Poco X7 Pro, two copies on one phone)

Goal: find out whether two Tor-connected copies of the app can exchange small test messages every few minutes in the background, and what it costs in battery. Test data only. The app has no real data, no location, and no contacts.

## About the test signing key
Spike builds are signed with a public test key committed in the repo (`app/spike-test.keystore`, password `android`). It has no security value and exists only so updates install over older builds and keep your data. Never use it for anything real. Real releases will use a private key that is never committed.

## 1. Install
1. Open https://github.com/BarksAndBytes/Thornberry/releases/tag/spike-latest on the phone and download `thornberry-spike-debug.apk`.
2. **One-time:** if the earlier "pipeline check" build is installed, uninstall it first (it was signed with a different key). After this, updates install over the top.
3. Install and open **Thornberry spike**. Allow notifications when asked.

## 2. Make the second copy
1. Settings > Apps > Dual apps (name may vary), turn on **Thornberry spike**.
2. The copy appears with a small badge on its icon. It has its own storage and its own onion address.

## 3. Background settings (do this for BOTH copies)
Xiaomi's system closes background apps aggressively, so these matter. Names and paths vary a little by version; look for the closest match.
1. Open the app, tap **Battery optimization exemption**, and allow.
2. Tap **Open app settings**, then:
   - **Autostart**: on.
   - **Battery saver** (or "App battery saver"): **No restrictions**.
   - **Notifications**: allowed.
3. Open the recent apps screen, and **lock** the app (long-press its card, or pull the card down, and tap the lock icon). Do this for both copies.
4. Turn the phone's own Battery saver off for the test.
5. Do not swipe the apps away or force-stop them during the test.

## 4. Pair the copies
1. In the original copy, tap **Start**. Wait until "My address" shows a long `.onion` address (can take a minute or two).
2. Tap **Copy my onion address**.
3. Switch to the second copy, tap **Start**, wait for its address.
4. In the second copy, paste the original's address into **Peer onion address**, set the interval (start with 2 minutes), tap **Save peer and interval**.
5. Tap **Copy my onion address** in the second copy, return to the original copy, paste it into its **Peer** box, set the same interval, and save.
6. Wait. Each copy sends its first ping about a minute after Tor is ready, then every interval. The live log at the bottom shows `PING_OUT ok=1` and `PING_IN` lines.

If you change the interval later, tap **Stop** then **Start** to apply it.

## 5. The test runs
| Run | What to do | How long |
|-----|------------|----------|
| A | Screen off, phone resting on Wi-Fi, not charging, interval 2 min | At least 8 hours, ideally 24 |
| B | Same, but move to mobile data only (turn Wi-Fi off) | 8 hours |
| C | Carry the phone normally through a day | 8 to 12 hours |
| D | Same as A but with battery saver on | 8 hours |

Do run A first. Note the battery percentage at the start.

## 6. Send me the results
After each run, open either copy and tap **Share summary and recent log**. Paste it into this chat. Do this in both copies if both were running. The summary reports:
- ping success rate and round-trip times
- longest gap between successful messages and how many gaps exceeded 10 minutes
- Tor reconnects, service restarts after Android killed it
- battery drop, Doze samples, and whether the battery exemption took effect

## Reading the results
- The one-way delay is exact because both copies share the phone's clock.
- Battery drop includes both copies running Tor, so it overstates the cost of one. A later run with one copy talking to a server will give the clean number.
- The app holds a wake lock while Tor is on (the library does this deliberately), which keeps the CPU awake. Expect a real battery cost; measuring it is the point.
- If many gaps exceed 10 minutes while the screen is off, Xiaomi's background limits or Doze are winning, and we will try the fallbacks in the spike plan.

## Known simplifications (test only)
- The local listener is on loopback with no authentication and the ping messages carry nothing sensitive.
- The onion service key is kept in the app's private storage so the address stays stable.
- Foreground service type is "specialUse" (no 6 hour limit). The real location app will need the "location" type, so this must be re-tested then.
- Only arm64 phones are supported by this build.
