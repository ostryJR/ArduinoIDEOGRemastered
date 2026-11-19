# Arduino IDE 1.8 Remastered

> **This is a remaster of the classic Arduino IDE 1.8.19, not an official
> Arduino release.** Remastered by **Mikolaj Ostrowski**
> ([github.com/ostryJR](https://github.com/ostryJR)).
> The original IDE is by Arduino and its community
> (see [arduino.cc/credits](https://www.arduino.cc/credits)).

**Current release: 1.8.19-R1.** That's upstream 1.8.19 plus remaster release R1.

The remaster keeps everything you know from 1.8: the same look, menus,
sketches, boards and libraries. It rebuilds the IDE to run natively on Apple
Silicon Macs, with sharper text and a faster start. You can install it next to
the original IDE; the remastered app's icon has an orange **R** tag in the
style of the splash screen.

## What the remaster changes

Compared with upstream 1.8.19:

- **Native arm64.** It runs on a bundled, trimmed JDK 22 runtime instead of the
  x86_64 Java 8 runtime under Rosetta, so it starts faster and the editor is
  more responsive.
- **Better text.** It uses Retina-quality font rendering from the modern JDK,
  with fractional glyph metrics.
- **Edit → Editor Font** lets you choose JetBrains Mono (bundled, SIL OFL),
  SF Mono (loaded from macOS) or Monaco. The change applies live to every
  window.
- **Code completion.** While you type, a list drawn like the editor itself (same
  font, each word colored as the syntax highlighting would color it) suggests
  Arduino functions and constants, keywords of your installed libraries, the
  sketch's own variables and functions, and header names after `#include <`.
  It opens after 3 letters and a short pause, or any time with **Ctrl+Space**.
  ↑/↓ choose, Enter or Tab insert (functions get `()` with the cursor inside),
  Esc closes. It never opens inside comments or strings, and it can be turned
  off in **Preferences → Remastered**.
- **Toolbar.** A Serial Plotter button sits next to Serial Monitor. The New and
  Open icons are clearer: a page with "+" and a folder (the old Open arrow looked
  like Upload).
- **Settings.** The Preferences window now has an **Editor font** choice and a
  **Serial Plotter values** format: Auto (comma, space or tab, as in 1.8.19),
  comma, semicolon, space, tab or a custom separator. Labels can be written as
  `name:value` or `name=value`.
- **arm64 native helpers.** Serial port discovery (libserialport), the Serial
  Monitor (jSSC 2.9.6) and Auto Format (Artistic Style) are rebuilt for arm64
  (`build/macosx/native-arm64/build.sh`).
- **Native compiling.** `arduino-builder` 1.6.1 and `ctags` are rebuilt for arm64
  at the same versions, so their output is byte-identical to the stock tools
  (`build/macosx/native-arm64/build-builder.sh`).
- **Native ARM compiler (on by default).** ARM boards (SAMD, e.g. Feather M0,
  MKR, Zero) compile with Arm's official native Apple Silicon GCC 12.3 (Arm GNU
  Toolchain 12.3.rel1) instead of the x86_64 GCC 7/9 that board packages ship.
  Builds are about 1.8x faster. The toolchain (110 MB download, SHA-256
  verified) is fetched from developer.arm.com on the first ARM compile and kept
  in `~/Library/Arduino15/remastered/toolchains/`. Turn it off with
  **Tools → Native ARM Compiler (Apple Silicon)** to use the board package's
  own compiler again. Firmware can differ slightly in size, because it's a
  newer GCC than the cores were validated with. AVR boards are unaffected.
- **Board Manager on Apple Silicon.** Native `arm64-apple-darwin` tools are
  preferred when a package offers them; otherwise x86_64 tools run through
  Rosetta 2.
- **Separate settings file.** Settings live in
  `~/Library/Arduino15/preferences-remastered.txt`, copied from your stock
  `preferences.txt` on first launch. Board packages, libraries and the
  sketchbook are shared with the original IDE.
- **Java 9+ port.** `com.apple.eawt` / `com.apple.eio` were replaced with
  `java.awt.Desktop`, and the Touch Bar support (x86_64-only library) is skipped
  on arm64.

- **Remastered branding.** The app is named "Arduino Remastered" and the window
  title reads "Arduino 1.8.19-R1 Remastered". The splash and About screens
  credit the remaster, and the icon carries an orange R tag.

## Build (macOS arm64)

```sh
build/macosx/build-remastered.sh --compile
open "dist/Arduino 1.8 Remastered.app"
```

The build needs JDK 22 or newer and the Xcode command line tools. The board
toolchains, examples and libraries are copied from an installed
`/Applications/Arduino.app` (1.8.19), so nothing large is downloaded. Override
that path with `ARDUINO_SOURCE_APP`.

## Versioning

Releases are named `<upstream version>-R<n>`, for example `1.8.19-R1`. The IDE
still reports `1.8.19` (`runtime.ide.version=10819`) to board cores and
libraries so that they keep working exactly as they do in upstream.

---

## Original Arduino README

<p align="center">
    <img src="http://content.arduino.cc/brand/arduino-color.svg" width="50%" />
</p>

**Important Notice**: This repository contains the legacy Arduino IDE 1.x, which is no longer in active development. For the latest features and updates, please visit the [Arduino IDE 2.x](https://github.com/arduino/arduino-ide) repository. If you encounter issues related to the newer IDE, please report them there.

Arduino is an open-source physical computing platform based on a simple I/O board and a development environment that implements the Processing/Wiring language. Arduino can be used to develop stand-alone interactive objects or can be connected to software on your computer (e.g. Flash, Processing and MaxMSP). The boards can be assembled by hand or purchased preassembled; the open-source IDE can be downloaded for free at [https://arduino.cc](https://www.arduino.cc/en/Main/Software).

![Github](https://img.shields.io/github/v/release/arduino/Arduino)

## More info at

- [Our website](https://www.arduino.cc/)
- [The forums](https://forum.arduino.cc/)
- Follow us on [Twitter](https://twitter.com/arduino)
- And like us at [Facebook](https://www.facebook.com/official.arduino)

## Bug reports and technical discussions

- To report a *bug* in the software or to request *a simple enhancement*, go to [Github Issues](https://github.com/arduino/Arduino/issues).
- More complex requests and technical discussions should go on the [Arduino Developers mailing list](https://groups.google.com/a/arduino.cc/forum/#!forum/developers).
- If you're interested in modifying or extending the Arduino software, we strongly suggest discussing your ideas on the [Developers mailing list](https://groups.google.com/a/arduino.cc/forum/#!forum/developers) *before* starting to work on them. That way you can coordinate with the Arduino Team and others, giving your work a higher chance of being integrated into the official release.

### Security

If you think you found a vulnerability or other security-related bug in this project, please read our [security policy](https://github.com/arduino/Arduino/security/policy) and report the bug to our Security Team 🛡️. Thank you!

e-mail contact: security@arduino.cc

## Installation

Detailed instructions for installation on popular operating systems can be found at:

- [Linux](https://www.arduino.cc/en/Guide/Linux) (see also the [Arduino playground](https://playground.arduino.cc/Learning/Linux))
- [macOS](https://www.arduino.cc/en/Guide/macOS)
- [Windows](https://www.arduino.cc/en/Guide/Windows)

## Contents of this repository

This repository contains just the code for the Arduino IDE itself. Originally, it also contained the AVR and SAM Arduino core and libraries (i.e. the code that is compiled as part of a sketch and runs on the actual Arduino device), but those have been moved into their own repositories. They are still automatically downloaded as part of the build process and included in built releases, though.

The repositories for these extra parts can be found here:
- Non-core specific Libraries are listed under: [Arduino Libraries](https://github.com/arduino-libraries/) (and also a few other places, see `build/build.xml`).
- The AVR core can be found at: [ArduinoCore-avr](https://github.com/arduino/ArduinoCore-avr).
- Other cores are not included by default but can be installed through the board manager. Their repositories can also be found under [Arduino GitHub organization](https://github.com/arduino/).

## Building and testing

Instructions for building the IDE and running unit tests can be found on the wiki:
- [Building Arduino](https://github.com/arduino/Arduino/wiki/Building-Arduino)
- [Testing Arduino](https://github.com/arduino/Arduino/wiki/Testing-Arduino)

## Credits

Arduino is an open-source project, supported by many. The Arduino team is composed of Massimo Banzi, David Cuartielles, Tom Igoe, and David A. Mellis.

Arduino uses [GNU avr-gcc toolchain](https://gcc.gnu.org/wiki/avr-gcc), [GCC ARM Embedded toolchain](https://launchpad.net/gcc-arm-embedded), [avr-libc](https://www.nongnu.org/avr-libc/), [avrdude](https://www.nongnu.org/avrdude/), [bossac](http://www.shumatech.com/web/products/bossa), [openOCD](http://openocd.org/), and code from [Processing](https://www.processing.org) and [Wiring](http://wiring.org.co).

Icon and about image designed by [ToDo](https://www.todo.to.it/).
