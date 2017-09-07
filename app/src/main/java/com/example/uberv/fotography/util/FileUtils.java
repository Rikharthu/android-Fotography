package com.example.uberv.fotography.util;

import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import timber.log.Timber;

public class FileUtils {
    public static final String MOVIES_FOLDER_NAME = "Fotography";

    public static File getVideoOutputDirectory() {
        File moviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
        File folder = new File(moviesDir, MOVIES_FOLDER_NAME);
        if (!folder.exists()) {
            if (!folder.mkdirs()) {
                Timber.e("Could not create movie directory: " + folder.getAbsolutePath());
            }
        }
        return folder;
    }

    public static File createVideoFileName(File storage) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "VIDEO_" + timestamp + "_";
        File videoFile = File.createTempFile(prepend, ".mp4", storage);
        return videoFile;
    }

}
