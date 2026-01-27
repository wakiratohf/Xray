package com.blankj.utilcode.util;

import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;

import androidx.core.content.FileProvider;

/**
 * <pre>
 *     author: Blankj
 *     blog  : http://blankj.com
 *     time  : 2018/04/20
 *     desc  : utils about uri
 * </pre>
 */
public final class UriUtils {

    private UriUtils() {
        throw new UnsupportedOperationException("u can't instantiate me...");
    }

    /**
     * Resource to uri.
     * <p>res2Uri([res type]/[res name]) -> res2Uri(drawable/icon), res2Uri(raw/icon)</p>
     * <p>res2Uri([resource_id]) -> res2Uri(R.drawable.icon)</p>
     *
     * @param resPath The path of res.
     * @return uri
     */
    public static Uri res2Uri(String resPath) {
        return Uri.parse("android.resource://" + Utils.getApp().getPackageName() + "/" + resPath);
    }

    /**
     * File to uri.
     *
     * @param file The file.
     * @return uri
     */
    public static Uri file2Uri(final File file) {
        if (!UtilsBridge.isFileExists(file)) return null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            String authority = Utils.getApp().getPackageName() + ".utilcode.fileprovider";
            return FileProvider.getUriForFile(Utils.getApp(), authority, file);
        } else {
            return Uri.fromFile(file);
        }
    }
    /**
     * uri to input stream.
     *
     * @param uri The uri.
     * @return the input stream
     */
    public static byte[] uri2Bytes(Uri uri) {
        if (uri == null) return null;
        InputStream is = null;
        try {
            is = Utils.getApp().getContentResolver().openInputStream(uri);
            return UtilsBridge.inputStream2Bytes(is);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            Log.d("UriUtils", "uri to bytes failed.");
            return null;
        } finally {
            if (is != null) {
                try {
                    is.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
