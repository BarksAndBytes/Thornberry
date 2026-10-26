# Chat app test guide (first on-phone test of the real message layer)

This is a TEST build with TEST data only. It is a separate app from the spike ("Thornberry (test)", package app.thornberry.chat) and installs next to it, so a spike run in progress is not disturbed (but Tor in two apps at once will affect each other's battery numbers, so do not combine this with the overnight pair).

## Install
1. On the phone open https://github.com/BarksAndBytes/Thornberry/releases/tag/chat-latest and download `thornberry-chat-debug.apk`. Install it. Allow notifications and (when asked) the camera.
2. Open it. The home screen shows the Tor status, your address, and **Crypto self-test on this phone**. It should say OK. If it says FAILED, copy the text and send it to me: that is the most important result of this first test (it tells us whether the crypto library works on your Android version).
3. Tap **Allow running in background (battery)** and allow it. In app settings turn Autostart on and Battery saver to No restrictions, and lock the app in recent apps (same as the spike).

## Second copy
Settings > Apps > Dual apps > turn on **Thornberry (test)**. Open the copy and do the same background settings.

## Pair the two copies
1. Wait until "My address" shows on both copies (a minute or two).
2. Copy A: **Add contact**, type a name for the other copy. Copy B: same.
3. With two phones: on each, tap **Scan their code** and scan the other phone's QR code (both directions).
   With two copies on ONE phone the camera cannot scan its own screen, so use the test-only shortcut: on copy A tap **Copy my code as text**; switch to copy B, type A's name, tap **Paste their code**. Then on copy B tap **Copy my code as text**; switch to copy A and tap **Paste their code**. (The clipboard can be read by other apps, so this shortcut exists only in these test builds and will not be in a real release.)
4. Both copies show a 9-digit safety code. Confirm they match, tap **Codes match**.

## What to try once paired
- Send direct messages both ways (screen on, then screen off for 10 minutes, then send).
- Create a group on one copy, invite the other, send group messages.
- Stop the connection on one copy, send a message from the other, start the connection again: the message should arrive.
- Force a crash by swiping the app away, reopen: your contacts and chat should still be there.

## What to send me
- The self-test line from the home screen.
- Anything that crashes, freezes, or looks wrong (a short description is enough).
- Whether messages arrived, and roughly how long they took.

## Safety notes
- Screenshots are blocked in this app on purpose (QR codes contain secrets).
- Messages and keys are stored encrypted with a key held in the phone's Keystore. If you uninstall the app or clear its data, everything is lost permanently (no backup, by design).
- Do not use this build for anything real: it has not been reviewed.
