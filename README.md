# AdShield

Lightweight Android ad blocker (Android 10+).

## Features
- **System-wide DNS blocking** via a local VPN that only routes DNS traffic — blocks Google, Meta, game ad SDKs (Unity Ads, AppLovin, ironSource, Pangle…) and major ad exchanges in all apps. Very low memory/battery use.
- **Stays running**: foreground service, auto-restart, starts on boot, supports Android's *Always-on VPN*.
- **Built-in ad-free browser** (Brave-style): EasyList/EasyPrivacy network filtering, cosmetic filtering, and YouTube ad removal by pruning ad data from the player response. Share a video from the YouTube app to *AdShield Browser* to watch it ad-free. Runs in its own process so it never weighs on the blocker.

## Limits
In-app ads in the YouTube/Facebook/Instagram apps and stream-stitched ads (JioHotstar, Prime Video, Spotify audio) cannot be blocked by DNS on a non-rooted phone — use the built-in browser for YouTube/Facebook/Instagram.

## Build
```
./gradlew assembleRelease
```
APK: `app/build/outputs/apk/release/app-release.apk`. Edit `app/src/main/assets/blocklist.txt` (DNS) and `filters.txt` (browser) to customise.

## Setup on the phone
1. Open AdShield → **Turn on** → accept the VPN prompt.
2. **Disable battery optimisation** (and enable Autostart on Xiaomi/Oppo/Vivo/Samsung).
3. VPN settings → AdShield → enable **Always-on VPN**.
4. Set Private DNS to *Off* or *Automatic*.
