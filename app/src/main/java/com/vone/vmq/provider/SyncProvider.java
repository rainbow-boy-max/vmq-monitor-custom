package com.vone.vmq.provider;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

/**
 * 虚拟 ContentProvider：配合 SyncAdapter 使用，不提供实际数据存储。
 *
 * 设计参考 shinian-a/Vmq-App（Apache-2.0）。
 */
public class SyncProvider extends ContentProvider {

    private static final String TAG = "SyncProvider";

    @Override
    public boolean onCreate() {
        Log.d(TAG, "SyncProvider 已创建");
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
