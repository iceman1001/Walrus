# Prompt: modernize Walrus so it builds and runs on a Pixel 9a

Modernize this Android app (Project Walrus, RFID/NFC card manager, last touched
June 2019) so it compiles with a current toolchain and runs correctly on my
Pixel 9a (Android 15/16, arm64) attached over USB. Work incrementally, keep the
app building at every step, and commit after each milestone on a branch
`modernize` (do not commit to master).

## Ground truth about the current state (already verified — don't re-derive)

- Gradle wrapper 6.7.1, AGP `com.android.tools.build:gradle:4.2.1`, `jcenter()`
  in both `build.gradle` repo blocks (jcenter is dead/read-only).
- `compileSdkVersion 27`, `minSdkVersion 19`, `targetSdkVersion 27`,
  `sourceCompatibility/targetCompatibility 1.7`.
- Whole app is on the old support library: ~200 `android.support.*` imports
  across 80 Java files (annotation, v4.app, v4.content, v7.app, v7.widget,
  v7.preference, v7.recyclerview, v7.util). No AndroidX anywhere.
- `dataBinding { enabled = true }`, 4 layouts use `<layout>` wrappers.
- `debug` build type sets `minifyEnabled true` **and** `useProguard true` —
  `useProguard` was removed in AGP 7 and will break the build outright.
- Dependencies (all pinned to 2018 versions): support-* 27.1.1,
  constraint-layout 1.1.2, `android.arch.lifecycle:extensions/compiler:1.1.1`,
  `com.afollestad.material-dialogs:core:0.9.6.0`,
  `io.github.yavski:fab-speed-dial:1.0.6` (abandoned, jcenter-only),
  `com.github.PhilJay:MPAndroidChart:v3.0.3` (jitpack),
  `com.github.felHR85:UsbSerial:4.5.1` (jitpack, core USB-serial transport),
  `play-services-location/maps:15.0.1`, `ormlite-android/core:5.0`,
  `org.parceler:parceler(-api):1.1.6` (annotation processor),
  `org.reflections:reflections:0.9.11` (declared but **never used in source** —
  delete it), `pub.devrel:easypermissions:1.1.3`, leakcanary 1.5.4,
  `com.android.support.test.espresso:espresso-core:2.2.2` +
  `android.support.test.runner.AndroidJUnitRunner`.
- Manifest: no `android:exported` on the launcher activity or the USB
  `USB_DEVICE_ATTACHED` receiver (hard requirement from API 31).
- `CardDeviceManager` calls `usbManager.requestPermission(...)` with a
  `PendingIntent.getBroadcast(...)` that has **no mutability flag**, and
  broadcasts an implicit-ish action `.device.CardDeviceManager.ACTION_USB_PERMISSION_RESULT`.
- `BulkReadCardsService` calls `startForeground()` and builds a
  `NotificationCompat` notification with `PendingIntent.FLAG_UPDATE_CURRENT`
  only. No `FOREGROUND_SERVICE*` permissions, no `foregroundServiceType`, no
  `POST_NOTIFICATIONS` permission.
- Permissions declared: only `ACCESS_FINE_LOCATION` and `VIBRATE`.
- Google Maps: `play-services-maps 15.0.1`; the Maps API key is **committed in
  plaintext in `gradle.properties`** (`GOOGLE_MAPS_API_KEY=...`).
- Local env: only Java 25 on PATH (`/usr/bin/java`, no `/usr/lib/jvm`), SDK at
  `/home/osboxes/Android/Sdk` with platforms android-27/30/35 and build-tools
  28.0.3/30.0.2/30.0.3/34.0.0/35.0.0 installed.
- `adb devices` shows `55271JEBF01489  device  model:Pixel_9a device:tegu` —
  **authorized and ready**, no prompt needed. Install and drive it directly.
- Working tree already has uncommitted edits to `build.gradle`,
  `gradle-wrapper.properties`, two Proxmark3 files and `.idea/*`. Review those
  first and either keep or revert them deliberately; say which you did.

## Milestones (do them in this order, build after each)

1. **Toolchain.** Get a JDK 17 or 21 available (report how — sdkman, apt, or
   Android Studio's bundled JBR — Java 25 alone will not work with AGP 8.x) and
   wire it in. Upgrade to Gradle 8.x + AGP 8.x, replace `jcenter()` with
   `mavenCentral()`, move repositories into `settings.gradle`
   (`dependencyResolutionManagement`), add `android.namespace`, move the
   `package` attribute out of the manifest, add `buildFeatures { dataBinding
   true }`, delete `useProguard`, and turn `minifyEnabled` **off for debug**.
   Java source/target 17. Add `gradle.properties` flags
   (`android.useAndroidX=true`, `android.enableJetifier=true` as a temporary
   crutch only).
2. **AndroidX migration.** Convert all `android.support.*` to `androidx.*`
   (appcompat, core, fragment, recyclerview, cardview, preference, constraint-
   layout, lifecycle, material components for `design`). Update
   `androidx.test` for the instrumentation runner and Espresso. Once source is
   clean, turn Jetifier back off and confirm it still builds.
3. **Dependency replacement.** Bump everything to current releases. For the
   three risky ones, evaluate and tell me the plan before ripping anything out:
   - `fab-speed-dial` (abandoned) → likely `com.leinardi.android:speed-dial` or
     a hand-rolled FAB menu; it is used on the wallet screen.
   - `material-dialogs 0.9.6.0` → either MD 3.x (breaking API) or plain
     `MaterialAlertDialogBuilder`. Prefer the smallest diff that removes the
     jcenter dependency.
   - `parceler 1.1.6` — an annotation processor that may not survive Java 17 /
     AGP 8. If it fails, migrate the affected model classes to `Parcelable` or
     `Serializable` by hand.
   Keep `UsbSerial` (felHR85) unless it is genuinely broken on API 35 — it is
   the transport for Proxmark3 and Chameleon Mini and is the riskiest thing to
   swap. Update `play-services-location`/`maps` and ORMLite. Drop the unused
   `reflections` dependency. Replace LeakCanary 1.5.4 with LeakCanary 2.x
   (`debugImplementation` only; delete the no-op release artifact).
4. **targetSdk 35 (or 36) behavior fixes.** compileSdk 35+ (install android-36
   if you target 36). Then fix, at minimum:
   - `android:exported` on every activity/service/receiver with an intent filter.
   - `PendingIntent.FLAG_IMMUTABLE`/`FLAG_MUTABLE` everywhere (the USB
     permission intent needs `FLAG_MUTABLE` on the receiver path — verify which
     it actually needs and explain your choice).
   - USB permission broadcast: make it explicit (`setPackage`) so Android 14+
     delivery still works, and confirm the attach/detach receiver still fires.
   - Foreground service: add `FOREGROUND_SERVICE` +
     `FOREGROUND_SERVICE_CONNECTED_DEVICE` permission,
     `android:foregroundServiceType="connectedDevice"`, and the
     `startForeground(id, notif, type)` overload for API 34+.
   - `POST_NOTIFICATIONS` runtime permission (API 33+) before bulk-read starts.
   - Location: request `ACCESS_COARSE_LOCATION` alongside fine, handle
     approximate-only grants, and check the easypermissions flow still works.
   - `allowBackup`/`fullBackupContent` → add `android:dataExtractionRules`.
   - Edge-to-edge is enforced at targetSdk 35: check every activity for content
     drawn under the status/navigation bars and apply window insets.
   - Predictive back: audit `onBackPressed()` overrides.
5. **Get it on the phone.** Have me accept the adb authorization prompt, then
   `./gradlew installDebug` and launch it. Report what you actually saw: use
   `adb shell am start`, `adb logcat` for crashes, and screenshots via
   `adb exec-out screencap -p`. Walk the main flows — wallet list, add/edit
   card, card detail with the map, settings, devices screen — and fix crashes
   until they are clean. State plainly which flows you verified and which you
   could not.
6. **Proxmark3 protocol drift.** See the dedicated section below — the wire
   protocol has moved on since 2019 and at least one command's reply shape
   changed. Do this as source work; it does not need hardware attached.
7. **Hardware verification (needs me in the loop).** The Proxmark3 and
   Chameleon Mini paths need real hardware on USB-OTG. Get the app to the point
   where a device attach is detected and permission is requested, then tell me
   exactly what to plug in and what you want me to watch for. Do not claim the
   RFID read/write paths work without a real device attached.


## Proxmark3 protocol drift (verified against `~/pm3/rdv48`)

You may read `~/pm3/rdv48` — a current checkout of the Iceman fork, HEAD dated
2026-08-22. **Read only. Never write to it, and never copy files out of it into
this repo except as small quoted constants.**

Walrus does *not* drive the PM3 CLI — it speaks the binary protocol directly,
in two flavours: `Proxmark3Command.java` (legacy 544-byte `PacketCommandOLD`)
and `Proxmark3CommandNG.java` (NG framing), with `Proxmark3Device.java` and
`Proxmark3DeviceIceman.java` as the two device implementations. Authoritative
references: `include/pm3_cmd.h` (opcodes, framing), `armsrc/appmain.c` (what
the firmware actually replies), `client/src/cmd*.c` (how the real client does it).

Already checked for you:

- **NG framing is unchanged.** `COMMANDNG_PREAMBLE_MAGIC 0x61334d50` ("PM3a"),
  `COMMANDNG_POSTAMBLE_MAGIC 0x3361` ("a3"), `PM3_CMD_DATA_SIZE 512` all still
  match Walrus's constants.
- **These opcodes are still correct:** `CMD_VERSION 0x0107`,
  `CMD_CAPABILITIES 0x0112`, `CMD_LF_HID_WATCH 0x020B` (Walrus calls it
  `HID_DEMOD_FSK`), `CMD_LF_HID_CLONE 0x0210`,
  `CMD_HF_ISO14443A_READER 0x0385`, `CMD_MEASURE_ANTENNA_TUNING 0x0400`
  (+ `_HF 0x0401`, `_LF 0x0402`), `CMD_HF_MIFARE_READBL 0x0620`.
- **Stale:** Walrus's `MEASURED_ANTENNA_TUNING = 0x410` no longer exists in
  `pm3_cmd.h`. The client now sends `CMD_MEASURE_ANTENNA_TUNING` and waits for
  an NG response on the *same* opcode carrying a payload struct — see
  `client/src/cmdhw.c`. Rework the tune path to match and drop 0x410.
- **Real behavior change — the HID read path is probably broken.** Firmware
  now does `reply_ng(CMD_LF_HID_WATCH, res, NULL, 0)` (`armsrc/appmain.c`
  ~line 1276): the reply carries a **status only, no card payload**. The
  decoded HID tag is emitted separately as a debug string
  (`DEBUG_PRINT_STRING 0x100`). Walrus's old code expects the tag in the reply
  args. Read `lf_hid_watch()` and the client's LF HID path, work out how the
  card data actually reaches the host today, and fix Walrus accordingly.
  Flag it clearly if this needs a design change rather than a constant bump.
- Audit the remaining opcodes and arg layouts the same way rather than
  assuming — several `CMD_*` names were renamed in the 2019→2026 window even
  where the numeric value held.
- Decide explicitly whether the **legacy 544-byte path is still worth keeping**.
  `PacketCommandOLD` still exists in `pm3_cmd.h`, but if current firmware
  effectively only speaks NG, say so and recommend collapsing
  `Proxmark3Device` / `Proxmark3DeviceIceman` into one implementation.

## Constraints

- **Stay inside `/home/osboxes/rfid/Walrus`.** Every file you create or modify
  lives in this repo. The single exception is **reading** `~/pm3/rdv48`.
- **Do not send anything from this machine to an external service.** No
  artifacts, no gists, no pastebins, no uploading logs or key material. Answer
  in the terminal or write files here.
- The Google Maps API key in `gradle.properties` is a committed secret: move it
  to `local.properties` (gitignored) and read it from there, and tell me it
  needs rotating. Do not print it back to me in full and do not put it in any
  commit.
- Keep `minSdkVersion` as low as is reasonably possible while still building —
  propose a number (21/23) and justify it rather than jumping to 26+ silently.
- Behavior must not change: this is a build/compat modernization, not a
  redesign. No new features, no UI restyling beyond what edge-to-edge and
  AndroidX components force.
- If something is genuinely blocked (a dead dependency with no replacement,
  hardware you cannot test), finish everything else and list what you left
  undone and why. Don't quietly shrink the scope.

## First step

Start by reporting the current build failure verbatim: run
`./gradlew :app:assembleDebug --stacktrace` and show me the real error before
changing anything.
