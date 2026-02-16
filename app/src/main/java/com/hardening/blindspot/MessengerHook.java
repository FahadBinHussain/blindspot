package com.hardening.blindspot;

import android.util.Log;
import android.view.View;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MessengerHook implements IXposedHookLoadPackage {
    private static final String TAG = "BLINDSPOT";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.facebook.orca")) return;

        Log.e(TAG, "!!! BLINDSPOT V2: SELECTIVE BLOCKING ACTIVE !!!");

        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                View v = (View) param.thisObject;
                String className = v.getClass().getSimpleName();

                // Check Content Description
                CharSequence desc = v.getContentDescription();
                if (desc != null) {
                    processSelection(v, desc.toString(), className);
                }

                // Check Hint
                if (v instanceof TextView) {
                    CharSequence hint = ((TextView) v).getHint();
                    if (hint != null) {
                        processSelection(v, hint.toString(), className);
                    }
                }
            }
        });
    }

    private void processSelection(View v, String text, String className) {
        String lowerText = text.toLowerCase();

        // --- THE "ALWAYS ALLOW" LIST (EMOTES/GIFS) ---
        if (lowerText.contains("sticker") ||
                lowerText.contains("tenor") ||
                lowerText.contains("gif") ||
                lowerText.contains("emoji") ||
                className.equals("8HS")) { // 8HS is the Emoji/Sticker search container

            // If we've already blocked this view, don't unblock it,
            // but if it's new, we let it be.
            if (v.getVisibility() != View.GONE) {
                // Log.e(TAG, "ALLOWING: " + text + " (" + className + ")");
                return;
            }
        }

        // --- THE "ALWAYS BLOCK" LIST (MAIN SEARCH & META AI) ---
        if (lowerText.contains("meta ai") ||
                lowerText.equals("ask meta ai or search") ||
                lowerText.equals("search messenger")) {

            Log.e(TAG, "BLOCKING MAIN SEARCH: " + text);
            applyBlindspot(v);
            return;
        }

        // --- THE "SPECIFIC BLOCK" (IN-CHAT SEARCH) ---
        // Only block "Search" if it's in the composer (Search in Conversation)
        if (lowerText.equals("search") && className.contains("ComposerEditText")) {
            Log.e(TAG, "BLOCKING IN-CHAT SEARCH: " + text);
            applyBlindspot(v);
        }
    }

    private void applyBlindspot(View v) {
        try {
            v.setVisibility(View.GONE);
            if (v.getLayoutParams() != null) {
                v.getLayoutParams().height = 0;
                v.getLayoutParams().width = 0;
            }
            v.setClickable(false);
            v.setFocusable(false);
            v.setEnabled(false);
        } catch (Exception ignored) {}
    }
}