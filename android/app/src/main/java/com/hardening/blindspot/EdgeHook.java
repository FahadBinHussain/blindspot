package com.hardening.blindspot;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class EdgeHook implements IXposedHookLoadPackage {
    private static final String TAG = "BLINDSPOT_EDGE";
    private static final String EDGE_PACKAGE = "com.microsoft.emmx";
    private static final String EDGE_BETA_PACKAGE = "com.microsoft.emmx.beta";
    private static final String EDGE_CANARY_PACKAGE = "com.microsoft.emmx.canary";
    private static final String EDGE_DEV_PACKAGE = "com.microsoft.emmx.dev";
    private static final Uri PREFS_URI = Uri.parse("content://com.hardening.blindspot.hooks/prefs");

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        String packageName = lpparam.packageName;

        if (!packageName.equals(EDGE_PACKAGE) &&
            !packageName.equals(EDGE_BETA_PACKAGE) &&
            !packageName.equals(EDGE_CANARY_PACKAGE) &&
            !packageName.equals(EDGE_DEV_PACKAGE)) {
            return;
        }

        Log.e(TAG, "!!! EDGE HOOK INITIALIZED for " + packageName + " !!!");

        // Hook popup window creation to remove Preview row
        hookPopupWindow(lpparam);

        // Block Preview menu item clicks
        hookMenuClicks(lpparam);
    }

    private void hookPopupWindow(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod("android.view.WindowManagerImpl", lpparam.classLoader,
                "addView", View.class, android.view.ViewGroup.LayoutParams.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        View view = (View) param.args[0];
                        String className = view.getClass().getName();

                        // Detect popup menu windows
                        if (className.contains("DecorView") || className.contains("Popup")) {
                            Log.e(TAG, "=== POPUP WINDOW DETECTED: " + className + " ===");

                            // Delay to let menu fully render, then remove Preview row
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                removePreviewRow(view);
                            }, 50);
                        }
                    }
                });
        } catch (Exception e) {
            Log.e(TAG, "Error hooking WindowManager: " + e.getMessage());
        }
    }

    private void removePreviewRow(View root) {
        if (root == null) return;
        try {
            if (root instanceof ViewGroup) {
                ViewGroup vg = (ViewGroup) root;
                for (int i = 0; i < vg.getChildCount(); i++) {
                    View child = vg.getChildAt(i);

                    // Check if this child or its descendants contain "Preview" text
                    String text = getViewText(child);
                    if (text != null && isPreviewText(text)) {
                        Log.e(TAG, "!!! HIDING PREVIEW ROW: '" + text + "' !!!");
                        // AdapterView doesn't support removeView, so hide with GONE
                        child.setVisibility(View.GONE);
                        child.setEnabled(false);
                        child.setClickable(false);
                        return; // Done
                    }

                    // Recurse into child
                    removePreviewRow(child);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing Preview row: " + e.getMessage());
        }
    }

    private String getViewText(View view) {
        if (view instanceof android.widget.TextView) {
            CharSequence text = ((android.widget.TextView) view).getText();
            return text != null ? text.toString() : null;
        }
        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                String text = getViewText(vg.getChildAt(i));
                if (text != null) return text;
            }
        }
        return null;
    }

    private boolean isPreviewText(String text) {
        if (text == null) return false;
        String lower = text.toLowerCase();
        return lower.contains("preview") || lower.contains("preview page");
    }

    private void hookMenuClicks(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            // Hook standard Android context menu item selection
            XposedHelpers.findAndHookMethod(Activity.class, "onContextItemSelected",
                MenuItem.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        MenuItem item = (MenuItem) param.args[0];
                        Activity activity = (Activity) param.thisObject;

                        if (!isHookEnabled(activity)) return;

                        CharSequence title = item.getTitle();
                        if (title != null && isPreviewText(title.toString())) {
                            Log.e(TAG, "!!! PREVIEW CLICK BLOCKED !!!");
                            Toast.makeText(activity, "Preview is disabled", Toast.LENGTH_SHORT).show();
                            param.setResult(true);
                        }
                    }
                });
        } catch (Exception e) {
            Log.e(TAG, "Error hooking menu clicks: " + e.getMessage());
        }

        try {
            // Hook MenuBuilder to intercept menu item creation
            Class<?> menuBuilderClass = XposedHelpers.findClass("com.android.internal.view.menu.MenuBuilder", lpparam.classLoader);
            XposedHelpers.findAndHookMethod(menuBuilderClass, "add",
                int.class, int.class, int.class, CharSequence.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        CharSequence title = (CharSequence) param.args[3];
                        if (title != null && isPreviewText(title.toString())) {
                            Log.e(TAG, "!!! PREVIEW MENU ITEM CREATED - DISABLING !!!");
                            MenuItem item = (MenuItem) param.getResult();
                            if (item != null) {
                                item.setEnabled(false);
                                item.setVisible(false);
                            }
                        }
                    }
                });
        } catch (Exception e) {
            Log.e(TAG, "Error hooking MenuBuilder: " + e.getMessage());
        }
    }

    private boolean isHookEnabled(Context context) {
        try {
            Cursor cursor = context.getContentResolver().query(
                PREFS_URI, null, null, new String[]{"hook_edge_enabled"}, null);
            if (cursor != null && cursor.moveToFirst()) {
                String value = cursor.getString(cursor.getColumnIndex("value"));
                cursor.close();
                return "true".equals(value);
            }
            if (cursor != null) cursor.close();
            return true;
        } catch (Exception e) {
            return true;
        }
    }
}
