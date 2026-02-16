package com.hardening.blindspot;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MessengerHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // 1. Filter: Only run this code inside Messenger
        if (!lpparam.packageName.equals("com.facebook.orca")) {
            return;
        }

        // 2. The Trap: Listen for when ANY View gets a "Content Description" set.
        // Messenger sets the description "Search" on the search bar for accessibility.
        XposedHelpers.findAndHookMethod(
                View.class,
                "setContentDescription",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        CharSequence description = (CharSequence) param.args[0];

                        if (description != null) {
                            String desc = description.toString().toLowerCase();

                            // 3. The Check: Does it say "search"?
                            // We check "search" and "buscar" (Spanish) just in case.
                            // NOTE: You might need to add other keywords if your phone is in another language.
                            if (desc.equals("search") || desc.contains("search messenger")) {

                                View v = (View) param.thisObject;

                                // 4. The Kill: Hide it completely
                                v.setVisibility(View.GONE);

                                // Double Tap: Set dimensions to 0 to prevent clicking empty space
                                if (v.getLayoutParams() != null) {
                                    v.getLayoutParams().height = 0;
                                    v.getLayoutParams().width = 0;
                                }
                                v.setClickable(false);
                            }
                        }
                    }
                }
        );

        // 5. Backup Trap: Listen for Text (Hints) in the search box
        XposedHelpers.findAndHookMethod(
                TextView.class, // Covers EditText and Buttons
                "setHint",
                CharSequence.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        CharSequence hint = (CharSequence) param.args[0];
                        if (hint != null) {
                            String hintText = hint.toString().toLowerCase();
                            if (hintText.contains("search")) {
                                View v = (View) param.thisObject;
                                v.setVisibility(View.GONE);
                            }
                        }
                    }
                }
        );
    }
}