# Jerby!

Jerby! is a free Android SNES app for two phones on the same Wi-Fi. It is a
modified build of [RetroArch](https://github.com/libretro/RetroArch) with a
simple launcher, LAN game transfer, a bundled
[bsnes-mercury Performance](https://github.com/libretro/bsnes-mercury) core,
and two original games: Jerby Dash and Jerby Tag.

## Play

1. Install the same Jerby! APK on both Android phones (64-bit ARM).
2. Open Jerby! and select a game. The two Jerby games are installed with the app.
3. On one phone, tap **Host LAN game**. Note the Wi-Fi IP shown.
4. On the other, tap **Join LAN game** and enter that IP. Jerby! checks the
   game's SHA-256. If the joining phone lacks it, the host copies it directly
   over Wi-Fi and the client verifies it before starting.
5. The host is Player 1 and the joining phone is Player 2. Both see the same
   SNES screen, just as two controllers on one console would.

You can add SNES ROMs through **Import ROM from phone** or **Download ROM from
link**. The download accepts a direct HTTPS link to an `.sfc`, `.smc`, `.fig`,
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

This release has been compiled locally. A real-device, two-phone LAN test is
still needed before treating it as a stable release.
