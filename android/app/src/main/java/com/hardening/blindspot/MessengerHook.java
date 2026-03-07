package com.hardening.blindspot;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MessengerHook implements IXposedHookLoadPackage {
    private static final String TAG = "BLINDSPOT";
    private static final Uri PREFS_URI = Uri.parse("content://com.hardening.blindspot.hooks/prefs");

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.facebook.orca")) return;

        Log.e(TAG, "!!! BLINDSPOT V3: GHOST-TOUCH PROTECTION INITIALIZED !!!");

        // --- NEW: Block Focus Requests (Stops Keyboard) ---
        XposedHelpers.findAndHookMethod(View.class, "requestFocus", int.class, android.graphics.Rect.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                View v = (View) param.thisObject;
                
                // Check if hook is enabled
                if (!isHookEnabled(v.getContext())) {
                    return;
                }
                
                if (shouldBlock(v)) {
                    param.setResult(false); // Cancel the focus request
                }
            }
        });

        // --- Standard Attachment Hook ---
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                View v = (View) param.thisObject;
                
                // Check if hook is enabled
                if (!isHookEnabled(v.getContext())) {
                    return;
                }
                
                if (shouldBlock(v)) {
                    nukeView(v);
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
                new String[]{"hook_messenger_enabled"},
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

    // Helper to determine if a view is a target
    private boolean shouldBlock(View v) {
        String className = v.getClass().getSimpleName();

        // Check Description
        CharSequence desc = v.getContentDescription();
        if (desc != null) {
            String d = desc.toString().toLowerCase();
            if (isBlacklisted(d, className)) return true;
        }

        // Check Hint
        if (v instanceof TextView) {
            CharSequence hint = ((TextView) v).getHint();
            if (hint != null) {
                String h = hint.toString().toLowerCase();
                if (isBlacklisted(h, className)) return true;
            }
        }
        return false;
    }

    private boolean isBlacklisted(String text, String className) {
        // Whitelist first
        if (text.contains("sticker") || text.contains("tenor") || text.contains("gif") ||
                text.contains("emoji") || className.equals("8HS")) {
            return false;
        }
        // Blacklist
        return text.contains("meta ai") || text.equals("ask meta ai or search") ||
                text.equals("search messenger") || (text.equals("search") && className.contains("ComposerEditText"));
    }

    private void nukeView(View v) {
        try {
            v.setVisibility(View.GONE);
            v.setAlpha(0);
            v.setClickable(false);
            v.setFocusable(false);
            v.setFocusableInTouchMode(false);

            // Set a touch listener that does NOTHING but "eats" the touch
            v.setOnTouchListener((view, motionEvent) -> true);

            // NUKE THE PARENT: If the search bar is in a layout, hide the layout too
            ViewParent parent = v.getParent();
            if (parent instanceof View) {
                View p = (View) parent;
                // Only hide parents that look like containers (usually ComponentHost or LinearLayout)
                if (p.getClass().getSimpleName().contains("Host") || p.getClass().getSimpleName().contains("Layout")) {
                    p.setVisibility(View.GONE);
                    p.setClickable(false);
                }
            }

            Log.e(TAG, "NUKED: " + v.getClass().getSimpleName());
        } catch (Exception ignored) {}
    }
}