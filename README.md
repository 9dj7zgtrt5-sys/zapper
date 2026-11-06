# Zapper — a TiviMate-style IPTV player for Fire TV

A lightweight Android TV app for M3U playlists with XMLTV guide data. It is built for Fire OS (tested target: Fire TV Stick 4K 1st gen, Fire OS 6) and needs Fire OS 5 / Android 5.1 or newer.

## Features
- **Made for non-technical viewers.** Text is large and there are hint bars on every screen. A help screen shows on first launch and whenever you tap ☰. **Back never exits the app**, and menus close by themselves when idle. Errors are written in plain language ("This channel isn't working right now…").
- **Opens automatically** when the Fire TV boots or wakes from sleep. This can be turned off in settings.
- **VPN guard.** Zapper waits for the VPN to connect before playing. If the VPN drops, playback pauses and a full-screen card says "The VPN is off — press OK to open it". Playback resumes by itself once the VPN reconnects.
- **Phone settings page** at `http://<tv-ip>:8090`, available whenever Zapper is open. From it you can change the playlist and EPG links, **hide categories** your parents don't need, hide movies and series, choose the VPN app, and see status (channel count, guide coverage, VPN state).
- **MAC-based portals (MAG / Stalker)**: enter the portal URL and the activated MAC address. Zapper signs in like a MAG250 box, loads channels and categories, uses the portal's own TV guide, and gets a fresh stream link each time a channel opens.
- Live playback (Media3/ExoPlayer) for HLS and MPEG-TS, with automatic reconnects.
- Categories, a ★ Favorites list, and channel numbers. Numbers only cover visible channels, so they stay small.
- TV guide grid. Past programmes can be replayed when the provider supports catch-up (`default`, `append`, `shift`, `flussonic`, `xc`).
- Movies and series as their own categories, with seeking.
- The playlist and guide are cached on the device and refreshed every 12 hours.

## Remote controls
| Where | Button | Action |
|---|---|---|
| Watching | ▲ / ▼ | Next / previous channel |
| Watching | OK | All channels |
| Watching | ▶ | TV guide |
| Watching | ◀ | Previous channel |
| Watching | ☰ (tap) | Help screen |
| Watching | ☰ (**hold 3 s**) | Settings on the TV (hidden so it isn't opened by accident) |
| Watching | Back | Show channel info (never exits; use Home to leave) |
| Channel list | ◀ / ▶ | Categories / TV guide |
| Channel list, guide | ☰ | Add or remove from Favorites ★ |
| Guide | OK | Watch now, or replay a finished programme |
| Movie / replay | ◀ ▶ / OK / Back | Skip / pause / back to live TV |

## For the VPN
In the VPN app's own settings, turn on **"Connect on startup"** or **"Auto-connect"** (the name varies by app). With that on, a reboot goes: Fire TV starts → VPN connects → Zapper opens and plays, with no button presses.

**Open Zapper once by hand after installing.** Android doesn't send the "TV started" signal to an app that has never been opened.

## Building the APK (no Android Studio needed)
1. Create a new GitHub repository, for example `zapper`. It can be **public**: the code holds none of your playlist details, since those stay on the TV.
2. Upload the contents of this folder to the repository (Add file → Upload files works). Make sure the hidden `.github` folder is included. Alternatively, push it with git.
3. GitHub Actions builds the app automatically (Actions tab, about 5 minutes) and publishes `zapper.apk` as a Release.

You can also open the folder in Android Studio and run **Build → Build APK(s)**.

## Installing on the Fire TV
1. On the Fire TV, go to **Settings → My Fire TV → About**. Click the device name 7 times to unlock **Developer options**.
2. Go to **Settings → My Fire TV → Developer options** and turn on **Apps from Unknown Sources** (on some versions this is **Install unknown apps → Downloader**).
3. Install the **Downloader** app from the Amazon Appstore.
4. In Downloader, enter:
   `https://github.com/<your-username>/zapper/releases/latest/download/zapper.apk`
   Then choose Install.

To install from a computer instead: run `adb connect <tv-ip>` and then `adb install zapper.apk`. ADB debugging must be turned on in Developer options first.

## Troubleshooting
- **Guide times look shifted**: check **Settings → Preferences → Time Zone** on the Fire TV.
- **Catch-up starts at the wrong point**: in setup, set the catch-up time offset to your provider's UTC offset, for example `5.5` for IST.
- **Provider blocks the player**: set a custom user agent in setup.
