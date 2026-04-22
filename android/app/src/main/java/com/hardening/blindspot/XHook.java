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

public class XHook implements IXposedHookLoadPackage {
    private static final String TAG = "BLINDSPOT_X";
    private static final String X_PACKAGE = "com.twitter.android";
    private static final String HOME_ACTIVITY = "com.twitter.app.main.MainActivity";
    private static final Uri PREFS_URI = Uri.parse("content://com.hardening.blindspot.hooks/prefs");
    
    private float startY = 0f;
    private static final int SCROLL_THRESHOLD = 120;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals(X_PACKAGE)) return;

        Log.e(TAG, "!!! X/TWITTER SCROLL-LOCK & FEED BLOCKER INITIALIZED !!!");

        // 1. INTENT INTERCEPTION - Block feed access
        XposedHelpers.findAndHookMethod(Activity.class, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                
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
                
                if (!isHookEnabled(activity)) {
                    return;
                }
                
                processIncomingIntent(activity, (Intent) param.args[0], "ON_NEW_INTENT");
            }
        });

        // 2. CONTINUOUS SCROLL DETECTION - Only on main timeline
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                
                if (!isHookEnabled(activity)) {
                    return;
                }
                
                // Only block scrolling on main timeline activity
                String activityName = activity.getClass().getName();
                boolean isMainTimeline = activityName.contains(HOME_ACTIVITY) || activityName.contains("HomeActivity");
                
                // Check if we're viewing a specific tweet (safe link)
                Intent intent = activity.getIntent();
                Uri data = (intent != null) ? intent.getData() : null;
                boolean hasSafeLink = (data != null && isSafeLink(data.toString()));
                
                // Don't block scrolling if viewing specific content
                if (!isMainTimeline || hasSafeLink) {
                    return;
                }
                
                MotionEvent event = (MotionEvent) param.args[0];

                // Multi-touch protection
                if (event.getPointerCount() > 1) {
                    Log.e(TAG, "Multi-touch detected. Blocking.");
                    blockAndNuke(activity, "X blocked: Multi-touch scrolling not allowed");
                    return;
                }

                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        startY = event.getRawY();
                        break;

                    case MotionEvent.ACTION_MOVE:
                        float currentY = event.getRawY();
                        float deltaY = Math.abs(currentY - startY);

                        if (deltaY > SCROLL_THRESHOLD) {
                            Log.e(TAG, "Scroll detected: " + deltaY + "px. Blocking.");
                            blockAndNuke(activity, "X blocked: Timeline scrolling disabled");
                        }
                        break;
                }
            }
        });

        // 3. RESUME TRAP - Prevent returning to feed
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                Activity activity = (Activity) param.thisObject;
                
                if (!isHookEnabled(activity)) {
                    return;
                }
                
                String activityName = activity.getClass().getName();
                if (activityName.contains(HOME_ACTIVITY) || activityName.contains("HomeActivity")) {
                    Intent intent = activity.getIntent();
                    Uri data = (intent != null) ? intent.getData() : null;
                    String action = (intent != null) ? intent.getAction() : "";
                    boolean hasSafeLink = (data != null && isSafeLink(data.toString()));
                    boolean isViewAction = Intent.ACTION_VIEW.equals(action);

                    if (!hasSafeLink && !isViewAction) {
                        Log.e(TAG, "Feed resume detected. Blocking.");
                        blockAndNuke(activity, "X blocked: Timeline access restricted. Use direct links only.");
                    }
                }
            }
        });

        // 4. UI CLEANUP - Hide feed-related UI elements
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                View v = (View) param.thisObject;
                
                if (!isHookEnabled(v.getContext())) {
                    return;
                }
                
                CharSequence desc = v.getContentDescription();
                if (desc != null) {
                    String d = desc.toString().toLowerCase();
                    // Hide home timeline, explore, trending tabs
                    if (d.contains("home") || d.contains("timeline") || 
                        d.contains("for you") || d.contains("following") ||
                        d.contains("explore") || d.contains("trending")) {
                        v.setVisibility(View.GONE);
                        v.setClickable(false);
                        Log.e(TAG, "Hidden UI element: " + d);
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
                new String[]{"hook_x_enabled"},
                null
            );
            
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(cursor.getColumnIndex("value"));
                cursor.close();
                boolean enabled = "true".equals(value);
                Log.e(TAG, "Hook enabled check: " + enabled);
                return enabled;
            }
            
            if (cursor != null) cursor.close();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error reading preferences: " + e.getMessage());
            return true;
        }
    }

    private void processIncomingIntent(Activity activity, Intent intent, String trigger) {
        if (intent == null) return;
        
        Uri data = intent.getData();
        String action = intent.getAction();
        String url = (data != null) ? data.toString() : "";

        if (!url.isEmpty()) {
            Log.e(TAG, ">>> URL (" + trigger + "): " + url);
        }

        // Block main launcher intent without specific content
        if (Intent.ACTION_MAIN.equals(action) && url.isEmpty()) {
            String activityName = activity.getClass().getName();
            if (activityName.contains(HOME_ACTIVITY) || activityName.contains("HomeActivity")) {
                blockAndNuke(activity, "X blocked: Cannot open timeline. Share a tweet link to view it.");
            }
        }
    }

    private boolean isSafeLink(String url) {
        String lowerUrl = url.toLowerCase();
        // Allow direct links to tweets, profiles, DMs, notifications
        return lowerUrl.contains("/status/") ||           // Tweet links
               lowerUrl.contains("/i/user/") ||           // Profile links
               lowerUrl.contains("/messages") ||          // DM links
               lowerUrl.contains("/notifications") ||     // Notifications
               lowerUrl.contains("/compose/tweet") ||     // Compose tweet
               lowerUrl.startsWith("twitter://status/") ||
               lowerUrl.startsWith("twitter://user?") ||
               lowerUrl.startsWith("twitter://post?");
    }

    private void blockAndNuke(Activity activity, String msg) {
        try {
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
            activity.finishAffinity();
            android.os.Process.killProcess(android.os.Process.myPid());
        } catch (Exception e) {
            Log.e(TAG, "Error blocking app: " + e.getMessage());
        }
    }
}
