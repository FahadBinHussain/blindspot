package com.hardening.blindspot;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Arrays;
import java.util.List;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MorpheHook implements IXposedHookLoadPackage {
    private static final String TAG = "BLINDSPOT_MORPHE";

    // --- FORBIDDEN KEYWORDS ---
    private static final List<String> BLACKLIST = Arrays.asList(
            "nsfw",
            "breast",
            "erotic",
            "hand expression",
            "porn",
            "sexy"
    );

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("app.morphe.android.youtube")) return;

        Log.e(TAG, "Morphe Content Watchdog Active");

        // THE WATCHDOG: This hooks every single piece of text that appears on your screen
        XposedHelpers.findAndHookMethod(TextView.class, "onLayout", 
            boolean.class, int.class, int.class, int.class, int.class, 
            new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    TextView tv = (TextView) param.thisObject;
                    String text = tv.getText().toString().toLowerCase();

                    if (isForbidden(text)) {
                        Log.e(TAG, "WATCHDOG TRIGGERED: Found forbidden word [" + text + "]");
                        
                        // Action: Flee the screen
                        flee(tv);
                    }
                }
            }
        );
        
        // Also hook setText to catch dynamic updates (like typing or suggestions loading)
        XposedHelpers.findAndHookMethod(TextView.class, "setText", CharSequence.class, TextView.BufferType.class, boolean.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                if (param.args[0] == null) return;
                String text = param.args[0].toString().toLowerCase();

                if (isForbidden(text)) {
                    Log.e(TAG, "SETTEXT TRIGGERED: Blocked [" + text + "]");
                    // 1. Clear the text so it's not visible
                    param.args[0] = ""; 
                    // 2. Flee the screen
                    flee((TextView) param.thisObject);
                }
            }
        });
    }

    private boolean isForbidden(String query) {
        if (query.length() < 3) return false; // Ignore very short words
        for (String word : BLACKLIST) {
            if (query.contains(word)) return true;
        }
        return false;
    }

    private void flee(TextView tv) {
        try {
            // Find the Activity hosting this text
            if (tv.getContext() instanceof Activity) {
                Activity activity = (Activity) tv.getContext();
                
                // Show a brief message
                Toast.makeText(activity, "Content Restricted. Exiting.", Toast.LENGTH_SHORT).show();
                
                // Nuclear Option: Kill the app immediately
                activity.finishAffinity(); 
                android.os.Process.killProcess(android.os.Process.myPid());
            }
        } catch (Exception ignored) {}
    }
}