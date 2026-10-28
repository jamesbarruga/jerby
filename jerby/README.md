# Jerby!

Jerby! is a free Android SNES app for two phones on the same Wi-Fi. It is a
modified build of [RetroArch](https://github.com/libretro/RetroArch) with a
landscape game catalog, automatic LAN discovery and game transfer, a bundled
[bsnes-mercury Performance](https://github.com/libretro/bsnes-mercury) core,
and two original games: Jerby Dash and Jerby Tag.

## Play

1. Install the same Jerby! APK on both Android phones (64-bit ARM).
2. Open Jerby! on both phones on the same Wi-Fi. The two Jerby games are installed
   with the app. The catalog can switch between grid and list view.
3. Tap a game on the first phone. It plays immediately and quietly hosts a LAN
   session. The other open phone discovers the session and joins automatically.
4. If the joining phone lacks the game, Jerby! transfers it over Wi-Fi and
   checks its SHA-256 before playing. The host is Player 1 and the joining
   phone is Player 2.

The catalog has a mute toggle and **Controls**. The control editor lets you drag
the D-pad, face buttons, shoulder buttons, Start, and Select to comfortable
positions. Changes are used when the next game starts. The game view stays in
landscape and shows the game and touch controls. Android Back returns to the
catalog.

You can add SNES ROMs through the catalog's **Add game** button. The download
accepts a direct HTTPS link to an `.sfc`, `.smc`, `.fig`,
`.swc`, or `.zip` file. Use files you have the right to use.

## Source and licenses

The Android emulator and netplay code derives from RetroArch, licensed GPLv3.
The SNES core is bsnes-mercury Performance, licensed GPLv3. The two included
games are original Jerby! games under MIT. Their SNES toolkit and sprite asset
come from OpenSNES under MIT; the notice is in `OPENSNES_LICENSE.txt`.

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

The previous manual LAN flow was played successfully on two real phones. This
new automatic discovery and control editor have been compiled locally but still
need a real-device test before a stable release.
