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
        
        // --- EDGE HOOK ---
        if (lpparam.packageName.equals("com.microsoft.emmx") || 
            lpparam.packageName.equals("com.microsoft.msedge") || 
            lpparam.packageName.equals("com.android.chrome")) {
            Log.i(TAG, "Edge/Chrome detected. Loading EdgeHook...");
            new EdgeHook().handleLoadPackage(lpparam);
        }
        
        // --- REDDIT HOOK ---
        if (lpparam.packageName.equals("com.reddit.frontpage")) {
            Log.i(TAG, "Reddit detected. Loading RedditHook...");
            new RedditHook().handleLoadPackage(lpparam);
        }
        
        // --- X/TWITTER HOOK ---
        if (lpparam.packageName.equals("com.twitter.android")) {
            Log.i(TAG, "X/Twitter detected. Loading XHook...");
            new XHook().handleLoadPackage(lpparam);
        }
        
        // --- INSTAGRAM HOOK ---
        if (lpparam.packageName.equals("com.instagram.android")) {
            Log.i(TAG, "Instagram detected. Loading InstagramHook...");
            new InstagramHook().handleLoadPackage(lpparam);
        }
    }
}