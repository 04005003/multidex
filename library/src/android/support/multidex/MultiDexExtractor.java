/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.support.multidex;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.util.Log;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Exposes application secondary dex files as files in the application data
 * directory.
 */
final class MultiDexExtractor {

    private static final String TAG = MultiDex.TAG;

    /**
     * We look for additional dex files named {@code classes2.dex},
     * {@code classes3.dex}, etc.
     */
    private static final String DEX_PREFIX = "classes";
    private static final String DEX_SUFFIX = ".dex";

    private static final String EXTRACTED_NAME_EXT = ".classes";
    private static final String EXTRACTED_SUFFIX = ".zip";
    private static final int MAX_EXTRACT_ATTEMPTS = 3;

    private static final int BUFFER_SIZE = 0x4000;

    private static final String PREFS_FILE = "multidex.version";
    private static final String KEY_NUM_DEX_FILES = "num_dex";
    private static final String KEY_PREFIX_DEX_CRC = "crc";

    /**
     * Extracts application secondary dexes into files in the application data
     * directory.
     *
     * @return a list of files that were created. The list may be empty if there
     *         are no secondary dex files.
     * @throws IOException if encounters a problem while reading or writing
     *         secondary dex files
     */
    static List<File> load(Context context, ApplicationInfo applicationInfo, File dexDir,
            boolean forceReload) throws IOException {
        Log.i(TAG, "load(" + applicationInfo.sourceDir + ", forceReload=" + forceReload + ")");
        final File sourceApk = new File(applicationInfo.sourceDir);
        final String extractedFilePrefix = sourceApk.getName() + EXTRACTED_NAME_EXT;

        // Ensure that whatever deletions happen in prepareDexDir only happen if the zip that
        // contains a secondary dex file in there is not consistent with the latest apk.  Otherwise,
        // multi-process race conditions can cause a crash loop where one process deletes the zip
        // while another had created it.
        prepareDexDir(dexDir, extractedFilePrefix);

        final List<File> files = new ArrayList<File>();
        final ZipFile apk = new ZipFile(applicationInfo.sourceDir);

        // If the CRC of any of the dex files is different than what we have stored or the number of
        // dex files are different, then force reload everything.
        ArrayList<Long> dexCrcs = getAllDexCrcs(apk);
        if (isAnyDexCrcDifferent(context, dexCrcs)) {
            forceReload = true;
        }
        try {

            int secondaryNumber = 2;

            ZipEntry dexFile = apk.getEntry(DEX_PREFIX + secondaryNumber + DEX_SUFFIX);
            while (dexFile != null) {
                String fileName = extractedFilePrefix + secondaryNumber + EXTRACTED_SUFFIX;
                File extractedFile = new File(dexDir, fileName);
                files.add(extractedFile);

                Log.i(TAG, "Need extracted file " + extractedFile);
                if (forceReload || !extractedFile.isFile()) {
                    Log.i(TAG, "Extraction is needed for file " + extractedFile);
                    int numAttempts = 0;
                    boolean isExtractionSuccessful = false;
                    while (numAttempts < MAX_EXTRACT_ATTEMPTS && !isExtractionSuccessful) {
                        numAttempts++;

                        // Create a zip file (extractedFile) containing only the secondary dex file
                        // (dexFile) from the apk.
                        extract(apk, dexFile, extractedFile, extractedFilePrefix);

                        // Verify that the extracted file is indeed a zip file.
                        isExtractionSuccessful = verifyZipFile(extractedFile);

                        // Log the sha1 of the extracted zip file
                        Log.i(TAG, "Extraction " + (isExtractionSuccessful ? "success" : "failed") +
                                " - length " + extractedFile.getAbsolutePath() + ": " +
                                extractedFile.length());
                        if (!isExtractionSuccessful) {
                            // Delete the extracted file
                            extractedFile.delete();
                        }
                    }
                    if (isExtractionSuccessful) {
                        // Write the dex crc's into the shared preferences
                        putStoredDexCrcs(context, dexCrcs);
                    } else {
                        throw new IOException("Could not create zip file " +
                                extractedFile.getAbsolutePath() + " for secondary dex (" +
                                secondaryNumber + ")");
                    }
                } else {
                    Log.i(TAG, "No extraction needed for " + extractedFile + " of size " +
                            extractedFile.length());
                }
                secondaryNumber++;
                dexFile = apk.getEntry(DEX_PREFIX + secondaryNumber + DEX_SUFFIX);
            }
        } finally {
            try {
                apk.close();
            } catch (IOException e) {
                Log.w(TAG, "Failed to close resource", e);
            }
        }

        return files;
    }

    /**
     * Iterate through the expected dex files, classes.dex, classes2.dex, classes3.dex, etc. and
     * return the CRC of each zip entry in a list.
     */
    private static ArrayList<Long> getAllDexCrcs(ZipFile apk) {
        ArrayList<Long> dexCrcs = new ArrayList<Long>();

        // Add the first one
        dexCrcs.add(apk.getEntry(DEX_PREFIX + DEX_SUFFIX).getCrc());

        // Get the number of dex files in the apk.
        int secondaryNumber = 2;
        while (true) {
            ZipEntry dexEntry = apk.getEntry(DEX_PREFIX + secondaryNumber + DEX_SUFFIX);
            if (dexEntry == null) {
                break;
            }

            dexCrcs.add(dexEntry.getCrc());
            secondaryNumber++;
        }
        return dexCrcs;
    }

    /**
     * Returns true if the number of dex files is different than what is stored in the shared
     * preferences file or if any dex CRC value is different.
     */
    private static boolean isAnyDexCrcDifferent(Context context, ArrayList<Long> dexCrcs) {
        final ArrayList<Long> storedDexCrcs = getStoredDexCrcs(context);

        if (dexCrcs.size() != storedDexCrcs.size()) {
            return true;
        }

        // We know the length of storedDexCrcs and dexCrcs are the same.
        for (int i = 0; i < storedDexCrcs.size(); i++) {
            if (storedDexCrcs.get(i) != dexCrcs.get(i)) {
                return true;
            }
        }

        // All the same
        return false;
    }

    private static ArrayList<Long> getStoredDexCrcs(Context context) {
        SharedPreferences prefs = getMultiDexPreferences(context);
        int numDexFiles = prefs.getInt(KEY_NUM_DEX_FILES, 0);
        ArrayList<Long> dexCrcs = new ArrayList<Long>(numDexFiles);
        for (int i = 0; i < numDexFiles; i++) {
            dexCrcs.add(prefs.getLong(makeDexCrcKey(i), 0));
        }
        return dexCrcs;
    }

    private static void putStoredDexCrcs(Context context, ArrayList<Long> dexCrcs) {
        SharedPreferences prefs = getMultiDexPreferences(context);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putInt(KEY_NUM_DEX_FILES, dexCrcs.size());
        for (int i = 0; i < dexCrcs.size(); i++) {
            edit.putLong(makeDexCrcKey(i), dexCrcs.get(i));
        }
        apply(edit);
    }

    private static SharedPreferences getMultiDexPreferences(Context context) {
        return context.getSharedPreferences(PREFS_FILE,
                Build.VERSION.SDK_INT < Build.VERSION_CODES.HONEYCOMB
                        ? Context.MODE_PRIVATE
                        : Context.MODE_PRIVATE | Context.MODE_MULTI_PROCESS);
    }

    private static String makeDexCrcKey(int i) {
        return KEY_PREFIX_DEX_CRC + Integer.toString(i);
    }

    /**
     * This removes any files that do not have the correct prefix.
     */
    private static void prepareDexDir(File dexDir, final String extractedFilePrefix)
            throws IOException {
        dexDir.mkdir();
        if (!dexDir.isDirectory()) {
            throw new IOException("Failed to create dex directory " + dexDir.getPath());
        }

        // Clean possible old files
        FileFilter filter = new FileFilter() {

            @Override
            public boolean accept(File pathname) {
                return !pathname.getName().startsWith(extractedFilePrefix);
            }
        };
        File[] files = dexDir.listFiles(filter);
        if (files == null) {
            Log.w(TAG, "Failed to list secondary dex dir content (" + dexDir.getPath() + ").");
            return;
        }
        for (File oldFile : files) {
            Log.w(TAG, "Trying to delete old file " + oldFile.getPath() + " of size " +
                    oldFile.length());
            if (!oldFile.delete()) {
                Log.w(TAG, "Failed to delete old file " + oldFile.getPath());
            } else {
                Log.w(TAG, "Deleted old file " + oldFile.getPath());
            }
        }
    }

    private static void extract(ZipFile apk, ZipEntry dexFile, File extractTo,
            String extractedFilePrefix) throws IOException, FileNotFoundException {

        InputStream in = apk.getInputStream(dexFile);
        ZipOutputStream out = null;
        File tmp = File.createTempFile(extractedFilePrefix, EXTRACTED_SUFFIX,
                extractTo.getParentFile());
        Log.i(TAG, "Extracting " + tmp.getPath());
        try {
            out = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)));
            try {
                ZipEntry classesDex = new ZipEntry("classes.dex");
                // keep zip entry time since it is the criteria used by Dalvik
                classesDex.setTime(dexFile.getTime());
                out.putNextEntry(classesDex);

                byte[] buffer = new byte[BUFFER_SIZE];
                int length = in.read(buffer);
                while (length != -1) {
                    out.write(buffer, 0, length);
                    length = in.read(buffer);
                }
                out.closeEntry();
            } finally {
                out.close();
            }
            Log.i(TAG, "Renaming to " + extractTo.getPath());
            if (!tmp.renameTo(extractTo)) {
                throw new IOException("Failed to rename \"" + tmp.getAbsolutePath() +
                        "\" to \"" + extractTo.getAbsolutePath() + "\"");
            }
        } finally {
            closeQuietly(in);
            tmp.delete(); // return status ignored
        }
    }

    /**
     * Returns whether the file is a valid zip file.
     */
    static boolean verifyZipFile(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            try {
                zipFile.close();
                return true;
            } catch (IOException e) {
                Log.w(TAG, "Failed to close zip file: " + file.getAbsolutePath());
            }
        } catch (ZipException ex) {
            Log.w(TAG, "File " + file.getAbsolutePath() + " is not a valid zip file.", ex);
        } catch (IOException ex) {
            Log.w(TAG, "Got an IOException trying to open zip file: " + file.getAbsolutePath(), ex);
        }
        return false;
    }

    /**
     * Closes the given {@code Closeable}. Suppresses any IO exceptions.
     */
    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException e) {
            Log.w(TAG, "Failed to close resource", e);
        }
    }

    // The following is taken from SharedPreferencesCompat to avoid having a dependency of the
    // multidex support library on another support library.
    private static Method sApplyMethod;  // final
    static {
        try {
            Class cls = SharedPreferences.Editor.class;
            sApplyMethod = cls.getMethod("apply");
        } catch (NoSuchMethodException unused) {
            sApplyMethod = null;
        }
    }

    private static void apply(SharedPreferences.Editor editor) {
        if (sApplyMethod != null) {
            try {
                sApplyMethod.invoke(editor);
                return;
            } catch (InvocationTargetException unused) {
                // fall through
            } catch (IllegalAccessException unused) {
                // fall through
            }
        }
        editor.commit();
    }
}
