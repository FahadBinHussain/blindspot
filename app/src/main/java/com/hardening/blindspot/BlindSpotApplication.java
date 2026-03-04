package com.hardening.blindspot;

import android.app.Application;
import android.content.SharedPreferences;

public class BlindSpotApplication extends Application {
    
    @Override
    public void onTerminate() {
        super.onTerminate();
        // Clear last activity when app terminates
        SharedPreferences prefs = getSharedPreferences("BlindSpotRules", MODE_PRIVATE);
        prefs.edit().remove("last_activity").apply();
    }
}
