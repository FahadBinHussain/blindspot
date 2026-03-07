package com.hardening.blindspot;

import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.PersistableBundle;
import androidx.annotation.NonNull;

public class BlindSpotAdminReceiver extends DeviceAdminReceiver {

    // This is called when another app (like Andoff) transfers the DO role to you
    @Override
    public void onTransferOwnershipComplete(@NonNull Context context, PersistableBundle bundle) {
        super.onTransferOwnershipComplete(context, bundle);
        // Ownership transferred successfully
        // Rules are already stored in SharedPreferences and will be applied via MainActivity
    }

    // Utility to transfer control back to Andoff
    public static void transferToAndoff(Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName currentAdmin = new ComponentName(context, BlindSpotAdminReceiver.class);
        // Replace with Andoff's actual package and receiver class name
        ComponentName targetAdmin = new ComponentName("com.andoff.app", "com.andoff.app.AdminReceiver");

        try {
            dpm.transferOwnership(currentAdmin, targetAdmin, new PersistableBundle());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}