package com.hardening.blindspot;

import android.util.Log;
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ModuleLoader implements IXposedHookLoadPackage {
    private static final String TAG = "BLINDSPOT_LOADER";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {

        if (lpparam.packageName.equals("com.facebook.orca")) {
            new MessengerHook().handleLoadPackage(lpparam);
        }

        if (lpparam.packageName.equals("com.facebook.katana")) {
            new FacebookHook().handleLoadPackage(lpparam);
        }

        // --- ADD MORPHE HERE ---
        if (lpparam.packageName.equals("app.morphe.android.youtube")) {
            Log.i(TAG, "Morphe detected. Loading MorpheHook...");
            new MorpheHook().handleLoadPackage(lpparam);
        }
    }
}