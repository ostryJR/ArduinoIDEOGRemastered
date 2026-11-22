# Arduino IDE 1.8 Remastered

Remaster of Arduino IDE 1.8.19 (Java/Swing) for Apple Silicon by Mikolaj Ostrowski
(github.com/ostryJR). The user-facing name is "Arduino 1.8 Remastered", version `1.8.19-R1.1.1`.
The feature list is in README.md.

- Remotes: `origin` = github.com/ostryJR/ArduinoIDEOGRemastered (default branch `master`),
  `upstream` = arduino/Arduino. The clone is shallow; use `git fetch --shallow-since=...` when a merge base is needed.
- Goal: look identical to stock 1.8, but with sharper text and more speed. Settings that don't exist in
  stock go in the Preferences **Remastered** tab. The Settings tab layout must stay identical to upstream.

## Build / run
- `build/macosx/build-remastered.sh [--compile]` builds `dist/Arduino 1.8 Remastered.app`: arm64, jlink JDK 22 runtime,
  jpackage, ad-hoc signed, about 470 MB.
- Needs:
  - JDK 22 at `/Library/Java/JavaVirtualMachines/jdk-22.jdk`
  - the Xcode CLT
  - stock `/Applications/Arduino.app` 1.8.19. Hardware, toolchains, examples and libraries are copied from it; override the path with `ARDUINO_SOURCE_APP`.
- Ant is not installed globally. Use `tools/ant/bin/ant` (`tools/` is gitignored; if it's missing, re-download Ant 1.10.14 from archive.apache.org).
- Native arm64 helpers, with outputs and work dirs gitignored:
  - `build/macosx/native-arm64/build.sh`: liblistSerialsj, libastylej.
  - `build/macosx/native-arm64/build-builder.sh`: arduino-builder 1.6.1 (Go 1.17), ctags 5.8-arduino11.
- The first launch after each rebuild takes 25–50 s while macOS scans the re-signed bundle. Warm launches take about 2.2 s.
- CLI check: `"dist/Arduino 1.8 Remastered.app/Contents/MacOS/Arduino 1.8 Remastered" --verify [--board fqbn] sketch.ino`.
  `--pref` without a sketch exits after startup (upstream behavior), so it's no use for GUI tests.
- The app's prefs live in `~/Library/Arduino15/preferences-remastered.txt` (seeded from `preferences.txt` on first run).
  Never modify the stock `preferences.txt`.
- Window-only screenshots: get window ids via CGWindowList (Swift), then `screencapture -l <id>`. Synthetic clicks
  do not reach Java tab bars.

## Decisions (don't redo)
- **Version:** `VERSION_NAME`/`REVISION` stay `1.8.19`/`10819`, because cores and libraries depend on them. The remaster
  release is `BaseNoGui.REMASTER_RELEASE` (`R<n>[.<m>[.<p>]]`, currently `R1.1.1`; the user picks the bump at /ship), and the build
  script reads it from there (only into the plist/display string; jpackage `--app-version` stays `1.8.19`). Upstream master's unreleased 1.8.20 bump was
  rejected when merging.
- **App-menu name:** set with jpackage `--java-options '-Dapple.awt.application.name="Arduino Remastered"'`, quoted. Setting it in
  code is too late, because AWT is already up.
- **Native ARM GCC:** `cc.arduino.remastered.NativeArmToolchain`, Arm GNU Toolchain 12.3.rel1, on by default.
  - It's downloaded and SHA-256 verified into `~/Library/Arduino15/remastered/toolchains/`.
  - `Compiler.java` overrides `runtime.tools.arm-none-eabi-gcc*.path` by adding `-prefs` last (the last one wins) and uses a separate core cache.
  - arduino-builder already wipes the build dir when `-prefs` change, so don't add custom wipe logic.
  - There's no official arm64 avr-gcc, so AVR still runs under Rosetta.
- **Java 9+ port:**
  - jSSC 2.9.6 plus native-lib-loader replaced 2.8.0-arduino4.
  - JTouchBar is skipped on arm64.
  - `com.apple.eawt`/`eio` were replaced with `java.awt.Desktop` and `user.home` paths.
- **Toolbar sprite:** `build/shared/lib/theme/buttons.svg` has 7 columns: Plotter added, New and Open redrawn. The PNG fallbacks are
  rendered from it with Batik.
- **Branding images:** the app icon `build/macosx/remastered.icns` has an orange R tag. The splash/about credit images are
  generated from the upstream originals (`git show 1.8.19:build/shared/lib/...`).
- **Retina:** the toolbar and sketch tabs paint directly, and theme SVGs become multi-resolution images (`Theme.hiDpiImage`).
- **Autocomplete:** `cc.arduino.remastered.autocomplete` (custom JWindow+JList popup, no fifesoft AutoComplete jar, so no
  RSTA version coupling). Sources: `PdeKeywords.getKeywordTypes()`/`getKeywordSource()`, a regex scan of all open tabs
  (comments/strings stripped), and `*.h` in library src folders + platform `cores/*`. Auto-opens after 3 chars + 250 ms,
  Ctrl+Space anytime; pref `editor.autocomplete` (Remastered tab). Keys are intercepted with a KeyListener (runs before
  RSTA key bindings), so Enter/Tab are untouched while it's closed. It doesn't auto-open when the typed word is already a
  complete candidate (so `int`+Enter stays a newline).
- **GUI testing without accessibility rights:** osascript/Robot keystrokes are silently dropped. Instead run a scratch
  harness JVM that builds a `SketchTextArea` from `app/pde.jar` (set `APP_DIR` to the bundle's `Contents/app`, init
  `PreferencesData`, reflect `Theme.init`), dispatches `KeyEvent`s in-process and screenshots its windows.
- **Plotter CSV header:** `PlotterFormat.headerLabels()`. A line with only text fields (no numbers, no `name:value`) is a
  header: it labels columns and doesn't advance `xCount`. After data has arrived, a header is ignored if it has fewer
  fields than the last data line (that's a debug message). Each field is its own column; `nan`/`inf`/`ovf`/text in a
  data line leaves a gap. Paint uses `g.translate`, not `setTransform`, because the latter dropped the Retina scale.
- **Board menu:** it vanishing from Tools while the window is unfocused is upstream behavior (`removeOnWindowDeactivation`), not a bug.

## Working with the user
- Do things end-to-end and verify them visually, capturing only the app's own windows, never the whole desktop.
- Use subagents for parallel, independent work when feasible.

## Open items
- **Untested:**
  - uploading native-GCC firmware to the Feather M0 (OpenDrop)
  - the Serial Plotter with live data in the new separator modes and with a CSV header (verified only in a harness that calls `message()`)
  - a visual check of the Preferences Remastered tab (now also has the autocomplete checkbox)
  - autocomplete typed for real in the bundled app (verified only in the harness above). In the harness's first run
    Enter once inserted a newline instead of accepting; not reproducible in 4 later runs — watch for it.
  - opening an `.ino` from Finder
- **Known limits:**
  - Autocomplete is text-based: no members after `.` beyond keywords.txt names, only the first name of `int a, b;`,
    and one-letter names are skipped.
  - Editor soft-wrap breaks mid-word inside comments (RSyntaxTextArea wraps by token).
  - GCC 12 prints a harmless newlib warning, "`_write is not implemented…`". Suppressing it was offered but not done.
- **Housekeeping:**
  - `origin/feat/remaster` and local `docs/session-notes` are merged and could be deleted.
  - `dist/` predates R1.1 and R1.1.1 (shows R1, old plotter): rebuild with `--compile`.
  - No release tag yet (R1.1 and R1.1.1 were shipped untagged).
  - native-lib-loader has no license file in `lib/`.
