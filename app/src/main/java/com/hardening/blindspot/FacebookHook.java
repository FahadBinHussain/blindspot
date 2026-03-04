package com.hardening.blindspot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class FacebookHook implements IXposedHookLoadPackage {
    private static final String TAG = "BLINDSPOT_FB";
    private static final String FEED_ACTIVITY = "com.facebook.katana.activity.FbMainTabActivity";
    private static final Uri PREFS_URI = Uri.parse("content://com.hardening.blindspot.hooks/prefs");
    
    private float startY = 0f;
    private static final int SCROLL_THRESHOLD = 120; // Lowered for faster reaction

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.facebook.katana")) return;

        Log.e(TAG, "!!! FB CONTINUOUS SCROLL-LOCK INITIALIZED !!!");

        // 1. URL LOGGER & INITIAL CHECK
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                
                // Check if hook is enabled
                if (!isHookEnabled(activity)) {
                    return;
                }
                
                processIncomingIntent(activity, activity.getIntent(), "ON_CREATE");
            }
        });

        XposedHelpers.findAndHookMethod(Activity.class, "onNewIntent", Intent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                
                // Check if hook is enabled
                if (!isHookEnabled(activity)) {
                    return;
                }
                
                processIncomingIntent(activity, (Intent) param.args[0], "ON_NEW_INTENT");
            }
        });

        // 2. THE CONTINUOUS MOTION WATCHDOG
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                
                // Check if hook is enabled
                if (!isHookEnabled(activity)) {
                    return;
                }
                
                MotionEvent event = (MotionEvent) param.args[0];

                // --- MULTI-TOUCH PROTECTOR ---
                // If more than one finger touches the screen, it's a bypass attempt.
                if (event.getPointerCount() > 1) {
                    Log.e(TAG, "Multi-touch detected. Nuke triggered.");
                    blockAndNuke(activity, "Multi-touch restricted.");
                    return;
                }

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float currentY = event.getRawY();
                        float deltaY = Math.abs(currentY - startY);

                        // Check displacement CONTINUOUSLY during movement
                        if (deltaY > SCROLL_THRESHOLD) {
                            Log.e(TAG, "Live Scroll Detected: " + deltaY + "px. Nuking.");
                            blockAndNuke(activity, "Scrolling is disabled.");
                        }
                        break;
                }
            }
        });

        // 3. THE SMART RESUME TRAP
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                
                // Check if hook is enabled
                if (!isHookEnabled(activity)) {
                    return;
                }
                
                if (activity.getClass().getName().contains(FEED_ACTIVITY)) {
                    Intent intent = activity.getIntent();
                    Uri data = (intent != null) ? intent.getData() : null;
                    String action = (intent != null) ? intent.getAction() : "";
                    boolean hasSafeLink = (data != null && isSafeLink(data.toString()));
                    boolean isViewAction = Intent.ACTION_VIEW.equals(action);

                    if (!hasSafeLink && !isViewAction) {
                        Log.e(TAG, "Feed Resume Trap. Nuking.");
                        blockAndNuke(activity, "Closing Facebook...");
                    }
                }
            }
        });

        // 4. UI CLEANUP
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                View v = (View) param.thisObject;
                CharSequence desc = v.getContentDescription();
                if (desc != null) {
                    String d = desc.toString().toLowerCase();
                    if (d.equals("home") || d.contains("news feed") || d.contains("watch") || d.contains("video")) {
                        v.setVisibility(View.GONE);
                    }
                }
            }
        });
    }
    
    private boolean isHookEnabled(Context context) {
        try {
            Cursor cursor = context.getContentResolver().query(
                PREFS_URI,
                null,
                null,
                new String[]{"hook_facebook_enabled"},
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(cursor.getColumnIndex("value"));
                cursor.close();
                boolean enabled = "true".equals(value);
                Log.e(TAG, "Hook enabled check via ContentProvider: " + enabled);
                return enabled;
            }
            
            if (cursor != null) cursor.close();
            Log.e(TAG, "ContentProvider returned null/empty, defaulting to enabled");
            return true; // Default to enabled if error
        } catch (Exception e) {
            Log.e(TAG, "Error reading from ContentProvider: " + e.getMessage());
            return true; // Default to enabled if error
        }
    }

    private void processIncomingIntent(Activity activity, Intent intent, String trigger) {
        if (intent == null) return;
        Uri data = intent.getData();
        String action = intent.getAction();
        String url = (data != null) ? data.toString() : "";

        if (!url.isEmpty()) Log.e(TAG, ">>> URL (" + trigger + "): " + url);

        if (Intent.ACTION_MAIN.equals(action) && url.isEmpty()) {
            if (activity.getClass().getName().contains(FEED_ACTIVITY)) {
                blockAndNuke(activity, "Feed blocked.");
            }
        }
    }

    private boolean isSafeLink(String url) {
        String lowerUrl = url.toLowerCase();
        return lowerUrl.contains("story.php") || lowerUrl.contains("fbid=") || 
               lowerUrl.contains("/posts/") || lowerUrl.contains("/share/") || 
               lowerUrl.contains("/reels/") || lowerUrl.contains("fb_shorts") ||
               lowerUrl.startsWith("fb://native_post/");
    }

    private void blockAndNuke(Activity activity, String msg) {
        try {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            activity.finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Exception ignored) {}
    }
}