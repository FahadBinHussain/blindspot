package com.hardening.blindspot;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

import java.util.Map;

public class HooksPreferenceProvider extends ContentProvider {
    private static final String TAG = "HooksProvider";
    public static final String AUTHORITY = "com.hardening.blindspot.hooks";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/prefs");
    
    private SharedPreferences prefs;
    
    @Override
    public boolean onCreate() {
        Log.e(TAG, "HooksPreferenceProvider onCreate()");
        prefs = getContext().getSharedPreferences("BlindSpotRules", Context.MODE_PRIVATE);
        
        // Log all preferences
        Map<String, ?> allPrefs = prefs.getAll();
        Log.e(TAG, "All preferences in BlindSpotRules: " + allPrefs.toString());
        
        return true;
    }
    
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Log.e(TAG, "Query called with URI: " + uri);
        Log.e(TAG, "Selection args: " + (selectionArgs != null ? java.util.Arrays.toString(selectionArgs) : "null"));
        
        MatrixCursor cursor = new MatrixCursor(new String[]{"key", "value"});
        
        if (selectionArgs != null && selectionArgs.length > 0) {
            String key = selectionArgs[0];
            boolean value = prefs.getBoolean(key, true);
            Log.e(TAG, "Reading key: " + key + " = " + value);
            cursor.addRow(new Object[]{key, value ? "true" : "false"});
        } else {
            Log.e(TAG, "No selection args provided!");
        }
        
        Log.e(TAG, "Returning cursor with " + cursor.getCount() + " rows");
        return cursor;
    }
    
    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.blindspot.prefs";
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
