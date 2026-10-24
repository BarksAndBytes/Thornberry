# Spike plan: Tor and battery feasibility (M0)

Purpose: decide D1, whether a phone can keep a Tor onion service reachable and deliver location updates within 10 minutes without ruinous battery use. Everything else in the schedule depends on this.

## Question to answer
On real Android phones, with the screen off and the app in the background for 24 hours, can two phones exchange a small encrypted message every few minutes over Tor, and how much battery does it cost?

## What the test app does (minimum)
- Foreground service with a persistent notification.
- Starts Tor and publishes one onion service.
- Every N minutes, sends a small timestamped message to the paired phone and logs the round-trip and one-way delay.
- Logs battery level, whether the device was in Doze, and any Tor reconnects, to a local file the user can export.
- No real location, no personal data. Test messages only.

## Test matrix
| Run | Setup | Duration |
|-----|-------|----------|
| 1 | Phone A and B on Wi-Fi, screens off, plugged out, app exempt from battery optimization | 24 h |
| 2 | One phone on mobile data, one on Wi-Fi | 24 h |
| 3 | One phone in a pocket and moving during the day | 8 h |
| 4 | Same as run 1 with battery saver on | 8 h |
| 5 | Same as run 1 without the battery-optimization exemption | 8 h |

If only one phone is available, run the second endpoint on a computer or the mailbox container to still measure phone-side behavior.

## Measurements
- Message delay percentiles (median, 95th, maximum) and number of messages that missed 10 minutes.
- Battery drop per hour, compared with a control phone with the app off.
- Number of Tor circuit rebuilds and how long reconnection takes after Doze windows and network changes.
- Whether the OS or manufacturer layer killed the service, and when.

## Proposed pass criteria (to be confirmed with the project owner)
- 95 percent of messages arrive within 10 minutes during runs 1 and 2.
- Battery cost attributable to the app no more than 5 percent per day beyond the control.
- Service survives 24 hours without manual restart on the phones tested.

## Outcomes
- **Pass**: proceed to M1 as planned.
- **Marginal**: adopt a hybrid (location sent as short-lived records through relay and mailbox, Tor connection opened on a schedule rather than held).
- **Fail**: reduce to a longer interval, or make the mailbox required, and record the revised target in the schedule.

## How to build and run without GitHub
Claude's workspace cannot download Android dependencies or compile Android apps, and GitHub pushing is not working. Two ways forward:
1. **Local Android Studio**: the owner installs Android Studio on a computer, opens the project folder Claude provides, and builds the debug APK. Claude writes the code and checklists; the owner builds and installs.
2. **Fix GitHub later**: once the Claude GitHub app has access to the repo, automated builds produce the APK.

Because nothing here can be compiled in Claude's workspace, spike code will be written carefully but will be untested until built on the owner's computer. Expect a compile-fix round.

## What the owner needs
- Phone models and Android versions (two phones ideally).
- A computer able to run Android Studio, or a decision to wait for GitHub.
- Willingness to carry the phones for the runs and send back the exported logs.

## Unknowns to resolve first (before writing spike code)
- Which Tor library for Android is currently maintained, and its license. (D2) Candidates found so far: Onion Wrapper, tor-android, Arti-Mobile-Ex; see the table in architecture.md. Start the spike with Onion Wrapper and tor-android because we must host an onion service.
- Whether those options support publishing onion services on current Android versions (the pages read did not say for tor-android or Arti).
- Current Android background execution rules for foreground services on the owner's Android versions.
