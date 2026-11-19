/*
 * This file is part of Arduino IDE 1.8 Remastered.
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License version 2
 * as published by the Free Software Foundation.
 */

package cc.arduino.remastered;

import processing.app.BaseNoGui;
import processing.app.PreferencesData;
import processing.app.helpers.FileUtils;
import processing.app.helpers.OSUtils;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Remastered: native Apple Silicon build of arm-none-eabi-gcc (Arm GNU
 * Toolchain), used instead of the x86_64 GCC that ARM board packages ship,
 * which would otherwise run under Rosetta 2.
 * <p>
 * The toolchain lives outside the app in
 * <code>~/Library/Arduino15/remastered/toolchains/</code> and is downloaded
 * (and SHA-256 verified) on first use. If anything goes wrong the stock
 * compiler is used, so a build never fails because of this feature.
 */
public class NativeArmToolchain {

  /** Preference key, see Tools &gt; Native ARM Compiler. */
  public static final String PREF = "remastered.native_arm_gcc";

  public static final String VERSION = "12.3.rel1";
  public static final String DISPLAY_NAME = "Arm GNU Toolchain " + VERSION;
  private static final String DIR_NAME = "arm-gnu-toolchain-" + VERSION + "-darwin-arm64-arm-none-eabi";
  private static final String URL =
    "https://developer.arm.com/-/media/Files/downloads/gnu/" + VERSION + "/binrel/" + DIR_NAME + ".tar.xz";
  /** From Arm's published <code>.sha256asc</code> for the file above. */
  private static final String SHA256 = "3b2eee0bdf71c1bbeb3c3b7424fbf7bd9d5c3f0f5a3a4a78159c9e3ad219e7bd";

  /**
   * Board-preference keys the cores use for the compiler location, e.g.
   * <code>runtime.tools.arm-none-eabi-gcc.path</code> (Adafruit) or
   * <code>runtime.tools.arm-none-eabi-gcc-7-2017q4.path</code> (Arduino SAMD).
   */
  private static final Pattern TOOL_KEY = Pattern.compile("runtime\\.tools\\.arm-none-eabi-gcc(-.+)?\\.path");

  /** Remembered per session so a failed download isn't retried on every build. */
  private static boolean installFailed = false;

  public static boolean isSupportedHost() {
    String arch = System.getProperty("os.arch");
    return OSUtils.isMacOS() && ("aarch64".equals(arch) || "arm64".equals(arch));
  }

  public static boolean isEnabled() {
    return isSupportedHost() && PreferencesData.getBoolean(PREF, true);
  }

  public static boolean isToolKey(String key) {
    return TOOL_KEY.matcher(key).matches();
  }

  /** @return true if the selected board's core compiles with arm-none-eabi-gcc */
  public static boolean appliesTo(Map<String, String> boardPreferences) {
    return boardPreferences.keySet().stream().anyMatch(NativeArmToolchain::isToolKey);
  }

  public static File installDir() throws Exception {
    return new File(new File(BaseNoGui.getSettingsFolder(), "remastered/toolchains"), DIR_NAME);
  }

  private static boolean isInstalled(File dir) {
    return new File(dir, "bin/arm-none-eabi-gcc").canExecute()
      && new File(dir, "bin/arm-none-eabi-g++").canExecute();
  }

  /**
   * Returns the toolchain folder, downloading it first if needed.
   *
   * @param log receives progress messages for the console
   * @return the toolchain folder, or null if it isn't available (use stock)
   */
  public static synchronized File ensureInstalled(Consumer<String> log) {
    try {
      File dir = installDir();
      if (isInstalled(dir)) {
        return dir;
      }
      if (installFailed) {
        return null;
      }
      install(dir, log);
      return isInstalled(dir) ? dir : null;
    } catch (Exception e) {
      installFailed = true;
      log.accept("Native ARM compiler unavailable (" + e.getMessage() + "), using the standard compiler.\n");
      return null;
    }
  }

  private static void install(File dir, Consumer<String> log) throws Exception {
    File parent = dir.getParentFile();
    Files.createDirectories(parent.toPath());
    File archive = new File(parent, DIR_NAME + ".tar.xz.part");
    File staging = new File(parent, DIR_NAME + ".extracting");
    try {
      log.accept("Downloading the native Apple Silicon ARM compiler (" + DISPLAY_NAME
        + ", about 110 MB, one time only)...\n");
      download(archive, log);

      log.accept("Unpacking...\n");
      FileUtils.recursiveDelete(staging);
      Files.createDirectories(staging.toPath());
      Process tar = new ProcessBuilder("/usr/bin/tar", "-xJf", archive.getAbsolutePath(),
                                       "-C", staging.getAbsolutePath())
        .redirectErrorStream(true).start();
      tar.getInputStream().transferTo(OutputStream.nullOutputStream());
      if (tar.waitFor() != 0) {
        throw new IOException("could not unpack the toolchain");
      }
      File unpacked = new File(staging, DIR_NAME);
      if (!isInstalled(unpacked)) {
        throw new IOException("unexpected archive layout");
      }
      FileUtils.recursiveDelete(dir);
      Files.move(unpacked.toPath(), dir.toPath(), StandardCopyOption.ATOMIC_MOVE);
      log.accept("Native ARM compiler installed in " + dir + "\n");
    } finally {
      archive.delete();
      FileUtils.recursiveDelete(staging);
    }
  }

  private static void download(File target, Consumer<String> log) throws Exception {
    HttpClient client = HttpClient.newBuilder()
      .followRedirects(HttpClient.Redirect.ALWAYS)
      .proxy(ProxySelector.getDefault())
      .connectTimeout(Duration.ofSeconds(30))
      .build();
    HttpRequest request = HttpRequest.newBuilder(URI.create(URL))
      .header("User-Agent", "ArduinoIDE/" + BaseNoGui.REMASTER_VERSION)
      .build();
    HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    if (response.statusCode() != 200) {
      throw new IOException("download failed, HTTP " + response.statusCode());
    }
    long total = response.headers().firstValueAsLong("Content-Length").orElse(-1);
    MessageDigest sha = MessageDigest.getInstance("SHA-256");
    try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target.toPath())) {
      byte[] buf = new byte[1 << 16];
      long done = 0;
      int lastPercent = -1;
      int n;
      while ((n = in.read(buf)) != -1) {
        out.write(buf, 0, n);
        sha.update(buf, 0, n);
        done += n;
        if (total > 0) {
          int percent = (int) (done * 100 / total);
          if (percent / 10 != lastPercent / 10) {
            log.accept("  " + percent + "%\n");
            lastPercent = percent;
          }
        }
      }
    }
    StringBuilder hex = new StringBuilder();
    for (byte b : sha.digest()) {
      hex.append(String.format("%02x", b));
    }
    if (!SHA256.equals(hex.toString())) {
      throw new IOException("checksum mismatch, download discarded");
    }
  }
}
