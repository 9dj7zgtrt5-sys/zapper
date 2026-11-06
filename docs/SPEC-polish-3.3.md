# Zapper 3.3 — polish spec

Goal: everything already works. This release makes it feel finished for Mum and Dad. That means
nothing confusing, nothing empty-looking, nothing that needs Yash to fix it remotely.
Constraints: TV remote only (Hisense over HDMI-CEC: arrows, OK, Back, Home, maybe numbers),
large readable text, no new settings on the TV. Admin options live on the phone settings page.

## A. Fixes itself (reliability)

A1. **Re-sign-in after a network change.** Portal stream links are tied to the IP that signed in.
    When the VPN location changes or the router restarts, every channel shows "Reconnecting…" forever.
    - Watch connectivity changes (VPN up/down, Wi-Fi back). On a change, drop the portal session.
    - In the player, if a portal channel fails twice, sign in again before the third try.
    - Done when: switching Surfshark location while watching recovers by itself within ~15 s.

A2. **Say why, in plain words.** Replace the bare "Reconnecting…" with a message for each cause:
    - VPN off → "Surfshark isn't connected. Connecting…" (and ask Surfshark to start).
    - No internet → "No internet. Waiting for Wi-Fi…"
    - Channel down after retries → "This channel isn't working right now. Press ▲ or ▼ for another." (exists)
    - Otherwise → "Connecting…" with a spinner, not a stuck pill.

A3. **Diagnostics.** The phone Test page checks 2 real channel streams. Playback failures go to logcat
    under the tag `Zapper`. (Already in the 3.3 commit.)

## B. Looks finished (visual)

B1. **No empty logo boxes.** If a channel has no logo, or it fails to load, show a monogram tile:
    1–2 letters ("GT" for Global Toronto) on a muted colour picked from the name. Never a blank box.

B2. **Quality as a badge.** Show "4K / FHD / HD" as a small pill after the name instead of in the
    text, and strip provider tags like `CA|`, `[VIP]`, `ᴴᴰ`. The name becomes "Global Toronto [4K]".

B3. **Focus contrast.** The focused row must read clearly on any TV picture mode: solid marigold
    fill, near-black text, bold weight. Counts on the focused row stay legible (no dim grey on marigold).

B4. **Programme line.** Show "Now: <show> · ends 9:30" when the portal has guide data. If a channel
    has none, show nothing instead of an empty line. Check portal EPG actually loads.

B5. **Category list readability.** Live categories get a small divider heading "Categories" under
    Help, so it's obvious the list continues below.

## C. Family-safe content

C1. **Adult content hidden by default.** Live categories, Movies and Series categories whose names
    match adult keywords (xxx, adult, 18+, porn, erotic…) are hidden unless "Show adult" is ticked
    on the phone page.
C2. **Hide Movies/Series categories** individually on the phone page, like live categories.

## E. Simpler list

E1. Remove the Apps section from Zapper's category list and the phone page. The home screen already has YouTube and Surfshark.

## D. Device (no code, done once over adb)

D1. Push the instant-start home config (no 2–3 min wait after boot).
D2. Surfshark: auto-connect on, location fixed to Canada – Montreal; Android always-on VPN.
D3. Chromecast time zone → India (clock currently shows Ottawa time).

## Out of scope
Recording, multi-user profiles, new launcher features.

## Release
versionCode 17 / 3.3. CI builds zapper.apk and tvhome.apk. Install with `adb install -r`. Settings and favourites are kept.
