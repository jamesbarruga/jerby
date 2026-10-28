# Jerby!

Jerby! is a free Android SNES app for two phones on the same Wi-Fi. It is a
modified build of [RetroArch](https://github.com/libretro/RetroArch) with a
landscape game catalog, automatic LAN discovery and game transfer, a bundled
[bsnes-mercury Performance](https://github.com/libretro/bsnes-mercury) core,
and four original games: Jerby Dash, Jerby Tag, Jerby Catch, and Jerby Pong.

## Play

1. Install the same Jerby! APK on both Android phones (64-bit ARM).
2. Open Jerby! on both phones on the same Wi-Fi. The two Jerby games are installed
   with the app. The catalog can switch between grid and list view.
3. Tap a game on the first phone. It plays immediately and quietly hosts a LAN
   session. The other open phone discovers the session and joins automatically.
4. If the joining phone lacks the game, Jerby! transfers it over Wi-Fi and
   checks its SHA-256 before playing. The host is Player 1 and the joining
   phone is Player 2.

## Discover and share

**My Games** shows installed games. **Discover** shows a curated catalog of
freely redistributable SNES games. Dash and Tag are already installed; Catch
and Pong have **Download & Play** buttons. Downloads are checked against both
the ZIP and ROM SHA-256 values in `jerby/store/catalog.json`. Jerby! extracts
SNES ROMs from ZIPs automatically. The catalog is read from this repository's
`main` branch, with an included offline copy as fallback. Additional catalog
games require a redistributable ROM, creator permission, and checksum entries.

Discover also loads SNES titles and search results from RomsGames.net as a
separate online source. Selecting a title opens that site's download form in
Jerby! and starts its Save Game action. The site may show a CAPTCHA that the
player must complete. When the site sends a file, Jerby! imports a SNES ROM or
ZIP automatically and starts it. Jerby! does not host these third-party games,
and the online source requires an internet connection. The site's terms require
downloaders to hold a license for each ROM. This integration depends on the
site's page and download flow and needs testing on Android phones.

**Share Jerby!** offers a QR code and local web page that serves the APK from
the installed phone. The other phone can use the same Wi-Fi or connect to the
first phone's hotspot; internet access is not needed. Android's share sheet
can also send the APK directly using nearby or Bluetooth sharing where
available. Android may ask the receiving phone to allow installation from its
browser or file receiver.

The catalog has a mute toggle and **Controls**. The control editor lets you drag
the D-pad, face buttons, shoulder buttons, Start, and Select to comfortable
positions. Changes are used when the next game starts. The game view stays in
landscape and shows the game and touch controls. Android Back returns to the
catalog.

You can add SNES ROMs through the catalog's **Add game** button. The download
accepts a direct HTTPS link to an `.sfc`, `.smc`, `.fig`, `.swc`, or `.zip`
file, and ZIPs are extracted automatically. Use files you have the right to use.

## Source and licenses

The Android emulator and netplay code derives from RetroArch, licensed GPLv3.
The SNES core is bsnes-mercury Performance, licensed GPLv3. The four Jerby!
games are original and under MIT. Their SNES toolkit and sprite asset
come from OpenSNES under MIT; the notice is in `OPENSNES_LICENSE.txt`.
QR generation uses ZXing Core under Apache 2.0.

Jerby-specific Android code is in
`pkg/android/phoenix/src/com/retroarch/browser/jerby/`. The small native
changes that launch the correct netplay role are in `retroarch_types.h`,
`frontend/drivers/platform_unix.c`, and `tasks/task_content.c`.

## Build

The Android project is `pkg/android/phoenix`. Install Android SDK platform 36,
build tools 36.0.0, NDK 29.0.14206865, and JDK 21. Point `local.properties`
to the SDK, then run `./gradlew assembleNormalDebug` in that directory.

The bundled SNES core is built from the vendored source in
`jerby/third_party/bsnes-mercury` (upstream commit
`79d7f9de218b6ffa65a80bbdc5828532bc239232`) with:

```sh
ndk-build -C target-libretro/jni APP_ABI=arm64-v8a PROFILE=performance
```

Copy the resulting `libretro.so` to
`pkg/android/phoenix/assets/cores/bsnes_mercury_performance_libretro_android.so`.
The APK extracts this core into its private app data on first launch.

The games' source is in `jerby/games/`. Build them with
[OpenSNES](https://github.com/k0b3n4irb/opensnes) commit
`aa8b441504172078ba136a94c20a41dd1c156b4c` and copy their `.sfc`
outputs to `pkg/android/phoenix/assets/`.

The previous manual LAN flow was played successfully on two real phones. The
new automatic discovery, game catalog, and app sharing have been compiled
locally but still need real-device testing before a stable release.
