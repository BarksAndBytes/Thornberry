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
2. You only need ONE scan or paste. On copy A tap **Add contact** (you can leave the name as is) and keep that screen open. On copy B tap **Add contact**, type a name for A, then:
   - with two phones: tap **Scan their code** and scan A's QR code;
   - with two copies on ONE phone (a camera cannot scan its own screen): on copy A tap **Copy my code as text**, switch to copy B and tap **Paste their code**. (The clipboard can be read by other apps, so this shortcut exists only in these test builds and will not be in a real release.)
3. Copy B shows a 9-digit safety code. A few seconds later copy A switches to its own safety code screen by itself (it needs the connection to be up). The two codes must be identical: this is how a stranger who also saw A's code would be caught. Type a name for the contact on A, then tap **Codes match** on both.

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

## Location sharing test (version 0.2.0 and later)
1. Open a group chat. Tap **Share my location: Off** and choose how precisely to share (Exact, About 100 m, About 1 km, City level). The first time, Android asks for location permission: choose **While using the app** (the app does not ask for or need "all the time"). A persistent notification then says the app is sharing location; it cannot be hidden.
2. Do the same on the other copy (two copies on one phone report the same position, which is fine for checking that updates flow; real tests need two phones, or one person walking while the other watches).
3. Tap **Where is everyone**. Each member shows coordinates, accuracy and how long ago. Positions shared at a coarse precision look rounded (for example About 1 km snaps to a 0.01 degree grid). "(old)" means older than 15 minutes; "sharing paused" means the person turned it off, which is different from lost signal.
4. Tap a line to open that position in your map app (that app may contact its own servers; Thornberry itself has no map and sends nothing to any map service).
5. Turn sharing Off on one copy: the other should show "sharing paused".
6. What to report: whether updates appear, how often, whether the notification stays during screen-off, and any battery drop that looks too high. Save the diagnostic log (home screen) if something looks wrong; it records when location was sent, never where.

## File transfer test (0.10.0)
How it works: a file is split into 256 KB pieces, each encrypted with a key that only the chat's members receive, and each checked against a list of piece fingerprints before it is kept. Pieces come from anyone in the chat who has them; in a group, members you are paired with fetch pieces for you if they do not have them yet. Nothing is downloaded until you tap.
1. Both copies on 0.10.0. In a direct chat, tap 📎 and pick a small photo (under 1 MB). The bubble shows "Preparing…", then "Shared".
2. On the other copy the bubble says "Tap to download". Tap, confirm, watch the percentage. When done, tap again, "Save to phone…", pick a folder, then open the saved file and compare it with the original.
3. Repeat with a larger file (50 to 200 MB). Stop Tor ("Stop connection" in Settings) halfway on either copy, start it again: the download continues where it stopped.
4. In a group, send a file and download it on the other copy.
5. Stop sharing: on the sender, tap your own file bubble and "Stop sharing"; a new download on the other copy should no longer progress.
Report: speed (MB per minute), anything stuck, and save the diagnostic log (it lists PIECES_OUT, FILE_DONE, FILE_PIECE_BAD and similar, never file names or contents).
