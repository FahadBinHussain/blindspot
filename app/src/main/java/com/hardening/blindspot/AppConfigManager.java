package com.hardening.blindspot;

import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.RestrictionEntry;
import android.content.RestrictionsManager;
import android.os.Bundle;
import java.util.List;

public class AppConfigManager {

    // 1. DISCOVER: Get the list of rules an app says it supports
    public static List<RestrictionEntry> getSupportedRules(Context context, String packageName) {
        RestrictionsManager rm = (RestrictionsManager) context.getSystemService(Context.RESTRICTIONS_SERVICE);
        return rm.getManifestRestrictions(packageName);
    }

    // 2. APPLY: The "Manual Mode" engine
    public static void applyCustomRestriction(Context context, String targetPackage, String key, String type, Object value) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName adminName = new ComponentName(context, BlindSpotAdminReceiver.class);

        if (!dpm.isDeviceOwnerApp(context.getPackageName())) return;

        // Get existing restrictions first so we don't overwrite others
        Bundle currentRestrictions = dpm.getApplicationRestrictions(adminName, targetPackage);
        
        // Add the new rule based on type
        switch (type) {
            case "String":
                currentRestrictions.putString(key, (String) value);
                break;
            case "Integer":
                currentRestrictions.putInt(key, Integer.parseInt(value.toString()));
                break;
            case "Boolean":
                currentRestrictions.putBoolean(key, Boolean.parseBoolean(value.toString()));
                break;
            case "StringArray":
                // Value would be a comma-separated string from your UI
                String[] items = value.toString().split(",");
                currentRestrictions.putStringArray(key, items);
                break;
        }

        // Push to the target app
        dpm.setApplicationRestrictions(adminName, targetPackage, currentRestrictions);
    }
}