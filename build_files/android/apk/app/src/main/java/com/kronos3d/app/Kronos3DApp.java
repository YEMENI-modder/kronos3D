/* SPDX-FileCopyrightText: 2026 Kronos3D Contributors
 *
 * SPDX-License-Identifier: GPL-2.0-or-later */

package com.kronos3d.app;

import android.app.ActivityManager;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Writes this app's own logcat output (Java and native, including the
 * "[BlenderAndroid] ..." lines and any fatal signal line) to a text file named
 * kronos3d_log_<date>_<time>.txt, one file per launch.
 *
 * Runs in Application.onCreate, so it starts before any activity and before
 * BlenderActivity's static System.loadLibrary("blender").
 *
 * Where the file goes, in order:
 *   1. /storage/emulated/0/Download/   (works once "All files access" is granted)
 *   2. the public Download folder through MediaStore (no permission needed)
 *   3. Android/data/com.kronos3d.app/files/  (always works)
 * The chosen location is also written to logcat under the tag Kronos3DLog.
 */
public class Kronos3DApp extends Application {

  private static final String TAG = "Kronos3DLog";
  private static final String DOWNLOAD_DIR = "/storage/emulated/0/Download";

  @Override
  public void onCreate() {
    super.onCreate();
    try {
      startLogging();
    }
    catch (Throwable t) {
      /* Logging must never be the reason the app fails to start. */
      Log.e(TAG, "log file setup failed", t);
    }
  }

  private void startLogging() throws IOException {
    final String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
    final String name = "kronos3d_log_" + stamp + ".txt";
    final String header = buildHeader();

    /* 1. Direct path. A logcat child process writes straight into the file, so the
     *    last lines before a crash are still written after the Java side is gone. */
    File file = null;
    try {
      File dir = new File(DOWNLOAD_DIR);
      if (dir.isDirectory() || dir.mkdirs()) {
        File candidate = new File(dir, name);
        if (candidate.createNewFile()) {
          file = candidate;
        }
      }
    }
    catch (Exception ex) {
      Log.w(TAG, "Download folder not writable directly: " + ex);
    }

    if (file == null) {
      /* 2. MediaStore into the public Download folder; 3. app-private folder. */
      File external = getExternalFilesDir(null);
      OutputStream media = openMediaStoreStream(name);
      if (media != null) {
        Log.i(TAG, "logging to Download/" + name + " (via MediaStore)");
        pumpLogcat(media, header);
        return;
      }
      if (external != null) {
        file = new File(external, name);
      }
    }

    if (file == null) {
      Log.w(TAG, "no place to write a log file");
      return;
    }

    try (FileOutputStream out = new FileOutputStream(file, true)) {
      out.write(header.getBytes("UTF-8"));
    }
    Log.i(TAG, "logging to " + file.getAbsolutePath());

    try {
      ProcessBuilder pb = new ProcessBuilder(
          "logcat", "-v", "threadtime", "-b", "main", "-b", "crash");
      pb.redirectErrorStream(true);
      pb.redirectOutput(ProcessBuilder.Redirect.appendTo(file));
      pb.start();
    }
    catch (Exception ex) {
      Log.e(TAG, "cannot start logcat", ex);
    }
  }

  /* MediaStore route: no child process can be handed this stream, so a thread copies. */
  private OutputStream openMediaStoreStream(String name) {
    try {
      ContentResolver resolver = getContentResolver();
      ContentValues values = new ContentValues();
      values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
      values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
      values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
      Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
      if (uri == null) {
        return null;
      }
      return resolver.openOutputStream(uri, "w");
    }
    catch (Exception ex) {
      Log.w(TAG, "MediaStore log file failed: " + ex);
      return null;
    }
  }

  private void pumpLogcat(final OutputStream out, final String header) {
    Thread thread = new Thread(() -> {
      try {
        out.write(header.getBytes("UTF-8"));
        out.flush();
        ProcessBuilder pb = new ProcessBuilder(
            "logcat", "-v", "threadtime", "-b", "main", "-b", "crash");
        pb.redirectErrorStream(true);
        Process process = pb.start();
        InputStream in = process.getInputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
          out.write(buffer, 0, n);
          out.flush();
        }
      }
      catch (Exception ex) {
        Log.w(TAG, "log copy stopped: " + ex);
      }
    }, "kronos3d-log");
    thread.setDaemon(true);
    thread.start();
  }

  private String buildHeader() {
    StringBuilder sb = new StringBuilder();
    sb.append("=== Kronos3D log ===\n");
    sb.append("Time: ").append(new Date()).append('\n');
    sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
        .append(" (").append(Build.DEVICE).append(")\n");
    sb.append("SoC: ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n');
    sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ")
        .append(Build.VERSION.SDK_INT).append(")\n");
    sb.append("ABI: ").append(String.join(",", Build.SUPPORTED_ABIS)).append('\n');
    sb.append("Fingerprint: ").append(Build.FINGERPRINT).append('\n');
    sb.append("\n--- How the previous runs of this app ended ---\n");
    try {
      ActivityManager am = (ActivityManager)getSystemService(ACTIVITY_SERVICE);
      List<ApplicationExitInfo> exits = am.getHistoricalProcessExitReasons(null, 0, 5);
      if (exits.isEmpty()) {
        sb.append("(none recorded)\n");
      }
      for (ApplicationExitInfo info : exits) {
        sb.append(new Date(info.getTimestamp())).append("  reason=")
            .append(reasonName(info.getReason())).append("  status=")
            .append(info.getStatus()).append("  desc=")
            .append(info.getDescription()).append('\n');
      }
    }
    catch (Throwable t) {
      sb.append("(unavailable: ").append(t).append(")\n");
    }
    sb.append("\n--- logcat ---\n");
    return sb.toString();
  }

  private static String reasonName(int reason) {
    switch (reason) {
      case ApplicationExitInfo.REASON_CRASH:
        return "CRASH (Java exception)";
      case ApplicationExitInfo.REASON_CRASH_NATIVE:
        return "CRASH_NATIVE (C/C++ crash)";
      case ApplicationExitInfo.REASON_ANR:
        return "ANR (not responding)";
      case ApplicationExitInfo.REASON_SIGNALED:
        return "SIGNALED";
      case ApplicationExitInfo.REASON_LOW_MEMORY:
        return "LOW_MEMORY (killed by the system)";
      case ApplicationExitInfo.REASON_EXIT_SELF:
        return "EXIT_SELF";
      case ApplicationExitInfo.REASON_USER_REQUESTED:
        return "USER_REQUESTED";
      case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE:
        return "INITIALIZATION_FAILURE";
      default:
        return "code " + reason;
    }
  }
}
