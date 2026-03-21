package com.hardening.blindspot;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

public class LSPosedFragment extends Fragment {
    
    private SharedPreferences prefs;
    private Handler pendingUpdater;
    private Runnable updaterRunnable;
    
    private LinearLayout morphePendingArea, messengerPendingArea, facebookPendingArea, edgePendingArea;
    private TextView morphePendingTimer, messengerPendingTimer, facebookPendingTimer, edgePendingTimer;
    private Button morphePendingApply, messengerPendingApply, facebookPendingApply, edgePendingApply;
    private Button morphePendingCancel, messengerPendingCancel, facebookPendingCancel, edgePendingCancel;
    private Switch morpheSwitch, messengerSwitch, facebookSwitch, edgeSwitch;

    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.tab_lsposed, container, false);
    }

    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        prefs = getActivity().getSharedPreferences("BlindSpotRules", Context.MODE_PRIVATE);
        
        PackageManager pm = getActivity().getPackageManager();
        loadAppIcon(view, R.id.icon_morphe, "app.morphe.android.youtube", pm);
        loadAppIcon(view, R.id.icon_messenger, "com.facebook.orca", pm);
        loadAppIcon(view, R.id.icon_facebook, "com.facebook.katana", pm);
        loadAppIcon(view, R.id.icon_edge, "com.microsoft.emmx", pm);
        
        morpheSwitch = view.findViewById(R.id.switch_hook_morphe);
        messengerSwitch = view.findViewById(R.id.switch_hook_messenger);
        facebookSwitch = view.findViewById(R.id.switch_hook_facebook);
        edgeSwitch = view.findViewById(R.id.switch_hook_edge);
        
        morphePendingArea = view.findViewById(R.id.pending_morphe_area);
        morphePendingTimer = view.findViewById(R.id.pending_morphe_timer);
        morphePendingApply = view.findViewById(R.id.pending_morphe_apply);
        morphePendingCancel = view.findViewById(R.id.pending_morphe_cancel);
        
        messengerPendingArea = view.findViewById(R.id.pending_messenger_area);
        messengerPendingTimer = view.findViewById(R.id.pending_messenger_timer);
        messengerPendingApply = view.findViewById(R.id.pending_messenger_apply);
        messengerPendingCancel = view.findViewById(R.id.pending_messenger_cancel);
        
        facebookPendingArea = view.findViewById(R.id.pending_facebook_area);
        facebookPendingTimer = view.findViewById(R.id.pending_facebook_timer);
        facebookPendingApply = view.findViewById(R.id.pending_facebook_apply);
        facebookPendingCancel = view.findViewById(R.id.pending_facebook_cancel);
        
        edgePendingArea = view.findViewById(R.id.pending_edge_area);
        edgePendingTimer = view.findViewById(R.id.pending_edge_timer);
        edgePendingApply = view.findViewById(R.id.pending_edge_apply);
        edgePendingCancel = view.findViewById(R.id.pending_edge_cancel);
        
        boolean morpheEnabled = prefs.getBoolean("hook_morphe_enabled", true);
        morpheSwitch.setChecked(morpheEnabled);
        messengerSwitch.setChecked(prefs.getBoolean("hook_messenger_enabled", true));
        facebookSwitch.setChecked(prefs.getBoolean("hook_facebook_enabled", true));
        edgeSwitch.setChecked(prefs.getBoolean("hook_edge_enabled", true));
        
        writeMorphePreferenceFile(morpheEnabled);
        
        setupSwitchListeners();
        setupPendingActionButtons();
        setupInfoIcons(view);
        startPendingActionsUpdater();
    }

    private void setupSwitchListeners() {
        morpheSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            handleHookToggle("morphe", MainActivity.PendingAction.ActionType.HOOK_MORPHE_TOGGLE, 
                           isChecked, morpheSwitch);
        });
        
        messengerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            handleHookToggle("messenger", MainActivity.PendingAction.ActionType.HOOK_MESSENGER_TOGGLE, 
                           isChecked, messengerSwitch);
        });
        
        facebookSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            handleHookToggle("facebook", MainActivity.PendingAction.ActionType.HOOK_FACEBOOK_TOGGLE, 
                           isChecked, facebookSwitch);
        });
        
        edgeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            handleHookToggle("edge", MainActivity.PendingAction.ActionType.HOOK_EDGE_TOGGLE, 
                           isChecked, edgeSwitch);
        });
    }
    
    private void handleHookToggle(String hookName, MainActivity.PendingAction.ActionType actionType, 
                                  boolean isChecked, Switch switchView) {
        if (isProtectionLocked()) {
            long delayMinutes = prefs.getLong("protection_delay_minutes", 0);
            long delayMillis = delayMinutes * 60 * 1000L;
            
            if (delayMillis > 0) {
                String pendingKey = "hook_" + hookName + "_toggle";
                MainActivity.PendingAction action = new MainActivity.PendingAction(
                    actionType,
                    pendingKey,
                    String.valueOf(isChecked),
                    delayMillis
                );
                
                MainActivity mainActivity = (MainActivity) getActivity();
                mainActivity.addPendingAction(pendingKey, action);
                updatePendingUI();
                
                Toast.makeText(getActivity(), 
                    " " + capitalizeFirst(hookName) + " hook change pending",
                    Toast.LENGTH_SHORT).show();
                return;
            }
        }
        
        applyHookToggle(hookName, isChecked);
    }
    private void applyHookToggle(String hookName, boolean isChecked) {
        android.util.Log.e("LSPosedFragment", hookName + " switch toggled to: " + isChecked);
        prefs.edit().putBoolean("hook_" + hookName + "_enabled", isChecked).apply();
        
        if (hookName.equals("morphe")) {
            writeMorphePreferenceFile(isChecked);
        }
        
        makePrefsReadable();
        String status = isChecked ? "enabled" : "disabled";
        Toast.makeText(getActivity(), 
            " " + capitalizeFirst(hookName) + " Hook " + status + 
            "\n\n IMPORTANT: Force stop " + capitalizeFirst(hookName) + 
            " app completely, then restart it for changes to take effect", 
            Toast.LENGTH_LONG).show();
    }
    private void setupPendingActionButtons() {
        morphePendingApply.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            MainActivity.PendingAction action = mainActivity.getPendingAction("hook_morphe_toggle");
            if (action != null) {
                applyHookToggle("morphe", Boolean.parseBoolean(action.data));
                morpheSwitch.setChecked(Boolean.parseBoolean(action.data));
                mainActivity.removePendingAction("hook_morphe_toggle");
                updatePendingUI();
            }
        });
        
        morphePendingCancel.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            mainActivity.removePendingAction("hook_morphe_toggle");
            morpheSwitch.setChecked(prefs.getBoolean("hook_morphe_enabled", true));
            updatePendingUI();
        });
        
        messengerPendingApply.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            MainActivity.PendingAction action = mainActivity.getPendingAction("hook_messenger_toggle");
            if (action != null) {
                applyHookToggle("messenger", Boolean.parseBoolean(action.data));
                messengerSwitch.setChecked(Boolean.parseBoolean(action.data));
                mainActivity.removePendingAction("hook_messenger_toggle");
                updatePendingUI();
            }
        });
        
        messengerPendingCancel.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            mainActivity.removePendingAction("hook_messenger_toggle");
            messengerSwitch.setChecked(prefs.getBoolean("hook_messenger_enabled", true));
            updatePendingUI();
        });
        
        facebookPendingApply.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            MainActivity.PendingAction action = mainActivity.getPendingAction("hook_facebook_toggle");
            if (action != null) {
                applyHookToggle("facebook", Boolean.parseBoolean(action.data));
                facebookSwitch.setChecked(Boolean.parseBoolean(action.data));
                mainActivity.removePendingAction("hook_facebook_toggle");
                updatePendingUI();
            }
        });
        
        facebookPendingCancel.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            mainActivity.removePendingAction("hook_facebook_toggle");
            facebookSwitch.setChecked(prefs.getBoolean("hook_facebook_enabled", true));
            updatePendingUI();
        });
        
        edgePendingApply.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            MainActivity.PendingAction action = mainActivity.getPendingAction("hook_edge_toggle");
            if (action != null) {
                applyHookToggle("edge", Boolean.parseBoolean(action.data));
                edgeSwitch.setChecked(Boolean.parseBoolean(action.data));
                mainActivity.removePendingAction("hook_edge_toggle");
                updatePendingUI();
            }
        });
        
        edgePendingCancel.setOnClickListener(v -> {
            MainActivity mainActivity = (MainActivity) getActivity();
            mainActivity.removePendingAction("hook_edge_toggle");
            edgeSwitch.setChecked(prefs.getBoolean("hook_edge_enabled", true));
            updatePendingUI();
        });
    }
    
    private void setupInfoIcons(View view) {
        view.findViewById(R.id.info_morphe).setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(getActivity())
                .setTitle("Morphe Content Watchdog")
                .setMessage(R.string.hook_morphe_description)
                .setPositiveButton("OK", null)
                .show();
        });
        
        view.findViewById(R.id.info_messenger).setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(getActivity())
                .setTitle("Messenger Ghost-Touch Protection")
                .setMessage(R.string.hook_messenger_description)
                .setPositiveButton("OK", null)
                .show();
        });
        
        view.findViewById(R.id.info_facebook).setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(getActivity())
                .setTitle("Facebook Scroll-Lock & Feed Blocker")
                .setMessage(R.string.hook_facebook_description)
                .setPositiveButton("OK", null)
                .show();
        });
        
        view.findViewById(R.id.info_edge).setOnClickListener(v -> {
            new android.app.AlertDialog.Builder(getActivity())
                .setTitle("Edge Preview Page Blocker")
                .setMessage("Disables preview page options in Edge browser context menus. This prevents users from accessing preview functionality when long-pressing links or accessing context menus.")
                .setPositiveButton("OK", null)
                .show();
        });
    }
    private void startPendingActionsUpdater() {
        if (pendingUpdater != null) {
            pendingUpdater.removeCallbacks(updaterRunnable);
        }
        
        pendingUpdater = new Handler();
        updaterRunnable = new Runnable() {
            @Override
            public void run() {
                updatePendingUI();
                
                MainActivity mainActivity = (MainActivity) getActivity();
                if (mainActivity != null && 
                    (!mainActivity.getPendingActions().isEmpty() || isProtectionLocked())) {
                    pendingUpdater.postDelayed(this, 1000);
                }
            }
        };
        pendingUpdater.post(updaterRunnable);
    }
    
    private void updatePendingUI() {
        MainActivity mainActivity = (MainActivity) getActivity();
        if (mainActivity == null) return;
        
        updateHookPendingUI("morphe", mainActivity.getPendingAction("hook_morphe_toggle"),
                           morphePendingArea, morphePendingTimer, morphePendingApply);
        updateHookPendingUI("messenger", mainActivity.getPendingAction("hook_messenger_toggle"),
                           messengerPendingArea, messengerPendingTimer, messengerPendingApply);
        updateHookPendingUI("facebook", mainActivity.getPendingAction("hook_facebook_toggle"),
                           facebookPendingArea, facebookPendingTimer, facebookPendingApply);
        updateHookPendingUI("edge", mainActivity.getPendingAction("hook_edge_toggle"),
                           edgePendingArea, edgePendingTimer, edgePendingApply);
    }
    
    private void updateHookPendingUI(String hookName, MainActivity.PendingAction pendingAction,
                                     LinearLayout pendingArea, TextView pendingTimer, 
                                     Button applyButton) {
        if (pendingAction != null) {
            pendingArea.setVisibility(View.VISIBLE);
            long remainingMs = pendingAction.getRemainingMillis();
            long totalSeconds = remainingMs / 1000;
            
            long days = totalSeconds / (24 * 3600);
            long hours = (totalSeconds % (24 * 3600)) / 3600;
            long minutes = (totalSeconds % 3600) / 60;
            long seconds = totalSeconds % 60;
            
            StringBuilder timeStr = new StringBuilder();
            if (days > 0) timeStr.append(days).append("d ");
            if (hours > 0 || days > 0) timeStr.append(hours).append("h ");
            timeStr.append(minutes).append("m ");
            timeStr.append(seconds).append("s");
            
            String statusText = Boolean.parseBoolean(pendingAction.data) ? "enable" : "disable";
            pendingTimer.setText(" Hook " + statusText + " pending: " + timeStr);
            
            if (pendingAction.isExpired()) {
                applyButton.setVisibility(View.VISIBLE);
                pendingTimer.setText(" Ready to " + statusText + " hook");
            } else {
                applyButton.setVisibility(View.GONE);
            }
        } else {
            pendingArea.setVisibility(View.GONE);
        }
    }
    private void writeMorphePreferenceFile(boolean enabled) {
        try {
            String value = enabled ? "1" : "0";
            String command = "settings put global blindspot_morphe_hook_enabled " + value;
            
            Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            int exitCode = process.waitFor();
            
            android.util.Log.e("LSPosedFragment", "Wrote Morphe state to Settings.Global (exit=" + exitCode + "): " + enabled);
            
            String readBack = android.provider.Settings.Global.getString(
                getActivity().getContentResolver(),
                "blindspot_morphe_hook_enabled"
            );
            android.util.Log.e("LSPosedFragment", "Read back from Settings.Global: " + readBack);
        } catch (Exception e) {
            android.util.Log.e("LSPosedFragment", "Failed to write to Settings.Global: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    private void makePrefsReadable() {
        try {
            java.io.File dataDir = new java.io.File(getActivity().getApplicationInfo().dataDir);
            java.io.File prefsDir = new java.io.File(dataDir, "shared_prefs");
            java.io.File prefsFile = new java.io.File(prefsDir, "BlindSpotRules.xml");
            
            dataDir.setExecutable(true, false);
            dataDir.setReadable(true, false);
            prefsDir.setExecutable(true, false);
            prefsDir.setReadable(true, false);
            if (prefsFile.exists()) {
                prefsFile.setReadable(true, false);
            }
        } catch (Exception e) {
            android.util.Log.e("LSPosedFragment", "Failed to set prefs readable: " + e.getMessage());
        }
    }
    
    private void loadAppIcon(View view, int imageViewId, String packageName, PackageManager pm) {
        ImageView iconView = view.findViewById(imageViewId);
        try {
            ApplicationInfo appInfo = pm.getApplicationInfo(packageName, 0);
            Drawable icon = pm.getApplicationIcon(appInfo);
            iconView.setImageDrawable(icon);
        } catch (PackageManager.NameNotFoundException e) {
        }
    }
    
    private boolean isProtectionLocked() {
        long lockUntil = prefs.getLong("protection_locked_until", 0);
        return System.currentTimeMillis() < lockUntil;
    }
    
    private long getRemainingLockMinutes() {
        long lockUntil = prefs.getLong("protection_locked_until", 0);
        long remaining = lockUntil - System.currentTimeMillis();
        return (remaining > 0) ? (remaining / 60000) + 1 : 0;
    }
    
    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (pendingUpdater != null && updaterRunnable != null) {
            pendingUpdater.removeCallbacks(updaterRunnable);
        }
    }
}
