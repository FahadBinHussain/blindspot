package com.hardening.blindspot;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AppProtectionActivity extends Activity {

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private SharedPreferences prefs;
    private ListView listView;
    private EditText searchInput;
    private List<AppProtectionInfo> allApps;
    private AppProtectionAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_protection);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, BlindSpotAdminReceiver.class);
        prefs = getSharedPreferences("BlindSpotRules", MODE_PRIVATE);
        
        // Save that we're on AppProtectionActivity
        prefs.edit().putString("last_activity", "AppProtectionActivity").apply();

        findViewById(R.id.btn_back).setOnClickListener(v -> {
            prefs.edit().remove("last_activity").apply();
            finish();
        });
        findViewById(R.id.btn_unhide_all).setOnClickListener(v -> unhideAllApps());
        
        searchInput = findViewById(R.id.search_apps);
        listView = findViewById(R.id.apps_protection_list);

        loadApps();
        
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s);
            }
            @Override
            public void afterTextChanged(Editable s) {}
        });
    }
    
    private void unhideAllApps() {
        try {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> packages = pm.getInstalledApplications(PackageManager.GET_UNINSTALLED_PACKAGES);
            
            int unhiddenCount = 0;
            for (ApplicationInfo appInfo : packages) {
                try {
                    // Try to unhide the app
                    dpm.setApplicationHidden(adminComponent, appInfo.packageName, false);
                    unhiddenCount++;
                } catch (Exception e) {}
            }
            
            Toast.makeText(this, "✅ Processed " + unhiddenCount + " apps", Toast.LENGTH_LONG).show();
            
            // Reload the list
            loadApps();
        } catch (Exception e) {
            Toast.makeText(this, "❌ Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadApps() {
        PackageManager pm = getPackageManager();
        allApps = new ArrayList<>();
        
        Set<String> blockedApps = prefs.getStringSet("blocked_apps", new HashSet<>());
        Set<String> protectedApps = prefs.getStringSet("protected_apps", new HashSet<>());

        try {
            List<ApplicationInfo> packages = pm.getInstalledApplications(0);
            for (ApplicationInfo appInfo : packages) {
                // Skip system apps without launcher icon
                Intent launchIntent = pm.getLaunchIntentForPackage(appInfo.packageName);
                if (launchIntent == null && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    continue;
                }

                String appName = pm.getApplicationLabel(appInfo).toString();
                Drawable icon = pm.getApplicationIcon(appInfo);
                
                String status = "default";
                if (blockedApps.contains(appInfo.packageName)) {
                    status = "blocked";
                } else if (protectedApps.contains(appInfo.packageName)) {
                    status = "protected";
                } else {
                    // Check if blocked/protected by other admins
                    try {
                        // Check if app is suspended (blocked)
                        boolean isSuspended = pm.isPackageSuspended(appInfo.packageName);
                        if (isSuspended) {
                            status = "blocked";
                        } else if (dpm.isUninstallBlocked(adminComponent, appInfo.packageName)) {
                            status = "protected";
                        }
                    } catch (Exception e) {}
                }

                allApps.add(new AppProtectionInfo(appName, appInfo.packageName, icon, status, false));
            }
        } catch (Exception e) {}

        // Load pinned apps
        Set<String> pinnedApps = prefs.getStringSet("pinned_apps", new HashSet<>());
        for (AppProtectionInfo app : allApps) {
            app.isPinned = pinnedApps.contains(app.packageName);
        }

        // Sort: pinned first, then blocked/protected, then alphabetically
        Collections.sort(allApps, (a, b) -> {
            // Priority: pinned first
            if (a.isPinned != b.isPinned) {
                return a.isPinned ? -1 : 1;
            }
            
            // Then: blocked > protected > default
            int priorityA = a.status.equals("blocked") ? 0 : (a.status.equals("protected") ? 1 : 2);
            int priorityB = b.status.equals("blocked") ? 0 : (b.status.equals("protected") ? 1 : 2);
            
            if (priorityA != priorityB) {
                return Integer.compare(priorityA, priorityB);
            }
            
            // Same priority, sort alphabetically
            return a.appName.compareToIgnoreCase(b.appName);
        });

        adapter = new AppProtectionAdapter(this, allApps);
        listView.setAdapter(adapter);
    }

    private static class AppProtectionInfo {
        String appName;
        String packageName;
        Drawable icon;
        String status; // "default", "blocked", "protected"
        boolean isPinned;

        AppProtectionInfo(String appName, String packageName, Drawable icon, String status, boolean isPinned) {
            this.appName = appName;
            this.packageName = packageName;
            this.icon = icon;
            this.status = status;
            this.isPinned = isPinned;
        }
    }

    private class AppProtectionAdapter extends BaseAdapter implements Filterable {
        private Context context;
        private PackageManager pm;
        private List<AppProtectionInfo> originalList;
        private List<AppProtectionInfo> filteredList;
        private String[] statusOptions = {"Default", "Blocked", "Protected"};

        AppProtectionAdapter(Context context, List<AppProtectionInfo> apps) {
            this.context = context;
            this.pm = context.getPackageManager();
            this.originalList = new ArrayList<>(apps);
            this.filteredList = new ArrayList<>(apps);
        }

        @Override
        public int getCount() {
            return filteredList.size();
        }

        @Override
        public AppProtectionInfo getItem(int position) {
            return filteredList.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                LayoutInflater inflater = LayoutInflater.from(context);
                convertView = inflater.inflate(R.layout.app_protection_item, parent, false);
            }

            ImageButton pinButton = convertView.findViewById(R.id.btn_pin_app);
            ImageView iconView = convertView.findViewById(R.id.app_protection_icon);
            TextView nameView = convertView.findViewById(R.id.app_protection_name);
            Spinner spinner = convertView.findViewById(R.id.app_protection_spinner);

            AppProtectionInfo app = filteredList.get(position);
            iconView.setImageDrawable(app.icon);
            nameView.setText(app.appName);
            
            // Update pin button icon
            if (app.isPinned) {
                pinButton.setImageResource(android.R.drawable.star_big_on);
            } else {
                pinButton.setImageResource(android.R.drawable.star_big_off);
            }
            
            // Handle pin button click
            pinButton.setOnClickListener(v -> {
                app.isPinned = !app.isPinned;
                
                // Save to prefs
                Set<String> pinnedApps = new HashSet<>(prefs.getStringSet("pinned_apps", new HashSet<>()));
                if (app.isPinned) {
                    pinnedApps.add(app.packageName);
                } else {
                    pinnedApps.remove(app.packageName);
                }
                prefs.edit().putStringSet("pinned_apps", pinnedApps).apply();
                
                // Reload list to re-sort
                loadApps();
            });

            ArrayAdapter<String> spinnerAdapter = new ArrayAdapter<>(
                context,
                android.R.layout.simple_spinner_item,
                statusOptions
            );
            spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(spinnerAdapter);

            // Set current status
            int selectedIndex = 0;
            if (app.status.equals("blocked")) selectedIndex = 1;
            else if (app.status.equals("protected")) selectedIndex = 2;
            spinner.setSelection(selectedIndex, false);

            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int statusIndex, long id) {
                    String oldStatus = app.status;
                    String newStatus;
                    if (statusIndex == 1) newStatus = "blocked";
                    else if (statusIndex == 2) newStatus = "protected";
                    else newStatus = "default";

                    if (!oldStatus.equals(newStatus)) {
                        updateAppStatus(app, newStatus);
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });

            return convertView;
        }

        private void updateAppStatus(AppProtectionInfo app, String newStatus) {
            if (!dpm.isDeviceOwnerApp(getPackageName())) {
                Toast.makeText(context, "⚠️ Not Device Owner", Toast.LENGTH_SHORT).show();
                return;
            }

            Set<String> blockedApps = new HashSet<>(prefs.getStringSet("blocked_apps", new HashSet<>()));
            Set<String> protectedApps = new HashSet<>(prefs.getStringSet("protected_apps", new HashSet<>()));

            // Remove from old status
            blockedApps.remove(app.packageName);
            protectedApps.remove(app.packageName);
            
            // Unblock/unprotect
            try {
                // Unsuspend the app
                String[] packages = {app.packageName};
                String[] unsuspended = dpm.setPackagesSuspended(adminComponent, packages, false);
                
                dpm.setUninstallBlocked(adminComponent, app.packageName, false);
                
                // Remove from user control disabled list
                List<String> disabledPackages = dpm.getUserControlDisabledPackages(adminComponent);
                if (disabledPackages.contains(app.packageName)) {
                    List<String> newList = new ArrayList<>(disabledPackages);
                    newList.remove(app.packageName);
                    dpm.setUserControlDisabledPackages(adminComponent, newList);
                }
            } catch (Exception e) {
                Toast.makeText(context, "⚠️ Unsuspend error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }

            // Apply new status
            try {
                if (newStatus.equals("blocked")) {
                    // Suspend the app (grayed out, can't launch)
                    String[] packages = {app.packageName};
                    String[] suspended = dpm.setPackagesSuspended(adminComponent, packages, true);
                    
                    if (suspended == null || suspended.length == 0) {
                        // Also protect from data clearing
                        List<String> disabledPackages = new ArrayList<>(dpm.getUserControlDisabledPackages(adminComponent));
                        if (!disabledPackages.contains(app.packageName)) {
                            disabledPackages.add(app.packageName);
                            dpm.setUserControlDisabledPackages(adminComponent, disabledPackages);
                        }
                        
                        blockedApps.add(app.packageName);
                        Toast.makeText(context, "✅ App suspended (blocked + data protected)", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "⚠️ Failed to suspend app", Toast.LENGTH_SHORT).show();
                    }
                } else if (newStatus.equals("protected")) {
                    dpm.setUninstallBlocked(adminComponent, app.packageName, true);
                    
                    // Add to user control disabled list - prevents uninstall, force stop, and data clearing
                    List<String> disabledPackages = new ArrayList<>(dpm.getUserControlDisabledPackages(adminComponent));
                    if (!disabledPackages.contains(app.packageName)) {
                        disabledPackages.add(app.packageName);
                        dpm.setUserControlDisabledPackages(adminComponent, disabledPackages);
                    }
                    
                    protectedApps.add(app.packageName);
                    Toast.makeText(context, "✅ App protected (uninstall + data)", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(context, "✅ Status reset to default", Toast.LENGTH_SHORT).show();
                }

                app.status = newStatus;
                prefs.edit()
                    .putStringSet("blocked_apps", blockedApps)
                    .putStringSet("protected_apps", protectedApps)
                    .apply();

            } catch (Exception e) {
                Toast.makeText(context, "❌ Failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    List<AppProtectionInfo> filtered = new ArrayList<>();

                    if (constraint == null || constraint.length() == 0) {
                        filtered.addAll(originalList);
                    } else {
                        String filterPattern = constraint.toString().toLowerCase().trim();
                        for (AppProtectionInfo app : originalList) {
                            if (app.appName.toLowerCase().contains(filterPattern) ||
                                app.packageName.toLowerCase().contains(filterPattern)) {
                                filtered.add(app);
                            }
                        }
                    }

                    results.values = filtered;
                    results.count = filtered.size();
                    return results;
                }

                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    filteredList = (List<AppProtectionInfo>) results.values;
                    notifyDataSetChanged();
                }
            };
        }
    }
}
