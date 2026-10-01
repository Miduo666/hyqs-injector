package com.hyqs.injector.update;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * 只读地把 cacheDir 里下载好的 update-*.apk 交给系统安装器（ACTION_VIEW + content://）。
 * 不导出，靠 FLAG_GRANT_READ_URI_PERMISSION 临时授权；没有 androidx 所以不用 FileProvider。
 * 小米/澎湃的安装器会直接拒绝 PackageInstaller 会话（"User rejected permissions"），这条路是国内 ROM 上自更新的通用做法。
 */
public class ApkProvider extends ContentProvider {

    public static final String AUTHORITY = "com.hyqs.injector.apk";
    private static final String MIME = "application/vnd.android.package-archive";

    public static Uri uriFor(File apk) {
        return Uri.parse("content://" + AUTHORITY + "/" + apk.getName());
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.matches("update-\\d+\\.apk")) throw new FileNotFoundException(String.valueOf(uri));
        File f = new File(getContext().getCacheDir(), name);
        if (!f.exists()) throw new FileNotFoundException(f.getPath());
        return f;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{"HyqsInjector.apk", f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public String getType(Uri uri) {
        return MIME;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        return 0;
    }
}
