package com.hardening.blindspot;

import android.app.AlertDialog;
import android.app.Dialog;
import android.app.admin.DeviceAdminReceiver;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.RestrictionEntry;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;

import androidx.fragment.app.FragmentActivity;
import androidx.viewpager2.widget.ViewPager2;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends FragmentActivity {

    private DevicePolicyManager dpm;
    private ComponentName adminComponent;
    private SharedPreferences prefs;
    
    private TextView statusIndicator;
    private AutoCompleteTextView appSearch; // Keep for compatibility
    private LinearLayout selectedAppContainer;
    private ImageView selectedAppIcon;
    private TextView selectedAppName;
    private AppInfo currentSelectedApp;
    private CheckBox showAllAppsCheckbox;
    private RadioGroup typeRadioGroup;
    private Spinner adminSpinner;
    private EditText keyInput, valueInput, arrayValueInput;
    private View singleValueContainer, arrayValueContainer;
    private ListView rulesListView;
    private ArrayAdapter<String> rulesAdapter;
    private List<String> activeRulesList;
    private Map<String, String> appNameToPackageMap;
    private List<AppInfo> appInfoList;
    
    // App Protection fields
    // (Removed - now in separate activity)
    
    // Helper class to store app info
    private static class AppInfo {
        String displayName;
        String packageName;
        android.graphics.drawable.Drawable icon;
        
        AppInfo(String displayName, String packageName, android.graphics.drawable.Drawable icon) {
            this.displayName = displayName;
            this.packageName = packageName;
            this.icon = icon;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        dpm = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        adminComponent = new ComponentName(this, BlindSpotAdminReceiver.class);
        prefs = getSharedPreferences("BlindSpotRules", MODE_PRIVATE);
        
        // Check if we should redirect to AppProtectionActivity
        // Only redirect if not launched from launcher (i.e., app was in background)
        String lastActivity = prefs.getString("last_activity", "");
        boolean launchedFromLauncher = (getIntent().getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0 &&
                                       getIntent().getCategories() != null &&
                                       getIntent().getCategories().contains(Intent.CATEGORY_LAUNCHER);
        
        if (lastActivity.equals("AppProtectionActivity") && savedInstanceState == null && !launchedFromLauncher) {
            Intent intent = new Intent(this, AppProtectionActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(intent);
            finish();
            return;
        }
        
        // Clear last activity if launched from launcher (fresh start)
        if (launchedFromLauncher) {
            prefs.edit().remove("last_activity").apply();
        }

        // Setup tabs
        ViewPager2 viewPager = findViewById(R.id.view_pager);
        TabLayout tabLayout = findViewById(R.id.tab_layout);
        
        TabsAdapter adapter = new TabsAdapter(this);
        viewPager.setAdapter(adapter);
        
        new TabLayoutMediator(tabLayout, viewPager, (tab, position) -> {
            switch (position) {
                case 0:
                    tab.setText("Rules");
                    break;
                case 1:
                    tab.setText("Protection");
                    break;
                case 2:
                    tab.setText("Transfer");
                    break;
                case 3:
                    tab.setText("Settings");
                    break;
            }
        }).attach();
    }
    
    // Public method for RulesFragment to initialize its views
    public void initializeRulesTab(View rootView) {
        // Initialize UI
        statusIndicator = rootView.findViewById(R.id.status_indicator);
        appSearch = rootView.findViewById(R.id.app_search);
        selectedAppContainer = rootView.findViewById(R.id.selected_app_container);
        selectedAppIcon = rootView.findViewById(R.id.selected_app_icon);
        selectedAppName = rootView.findViewById(R.id.selected_app_name);
        showAllAppsCheckbox = rootView.findViewById(R.id.show_all_apps);
        typeRadioGroup = rootView.findViewById(R.id.value_type_group);
        keyInput = rootView.findViewById(R.id.restriction_key);
        valueInput = rootView.findViewById(R.id.restriction_value);
        arrayValueInput = rootView.findViewById(R.id.array_value_input);
        singleValueContainer = rootView.findViewById(R.id.single_value_container);
        arrayValueContainer = rootView.findViewById(R.id.array_value_container);
        rulesListView = rootView.findViewById(R.id.rules_list);

        updateDeviceOwnerStatus();
        setupAppSearch();
        refreshRulesList();
        
        // Handle app selection - show dialog with searchable list
        selectedAppContainer.setOnClickListener(v -> showAppSelectionDialog());
        
        // Toggle separator button for array values
        Button toggleSeparatorBtn = rootView.findViewById(R.id.btn_toggle_separator);
        if (toggleSeparatorBtn != null) {
            toggleSeparatorBtn.setOnClickListener(v -> {
                String currentText = arrayValueInput.getText().toString();
                if (currentText.isEmpty()) return;
                
                // Detect current format
                boolean isNewlineSeparated = currentText.contains("\n");
                
                if (isNewlineSeparated) {
                    // Convert newlines to commas
                    String[] items = currentText.split("\n");
                    java.util.List<String> cleanedItems = new java.util.ArrayList<>();
                    for (String item : items) {
                        String trimmed = item.trim();
                        if (!trimmed.isEmpty()) {
                            cleanedItems.add(trimmed);
                        }
                    }
                    arrayValueInput.setText(String.join(", ", cleanedItems));
                    toggleSeparatorBtn.setText("Switch to Lines");
                } else {
                    // Convert commas to newlines
                    String[] items = currentText.split(",");
                    java.util.List<String> cleanedItems = new java.util.ArrayList<>();
                    for (String item : items) {
                        String trimmed = item.trim();
                        if (!trimmed.isEmpty()) {
                            cleanedItems.add(trimmed);
                        }
                    }
                    arrayValueInput.setText(String.join("\n", cleanedItems));
                    toggleSeparatorBtn.setText("Switch to Commas");
                }
            });
        }
        
        // Open full-screen array editor
        rootView.findViewById(R.id.btn_open_full_editor).setOnClickListener(v -> openFullScreenEditor());
        
        // Toggle input fields based on data type selection
        typeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.type_string_array) {
                singleValueContainer.setVisibility(View.GONE);
                arrayValueContainer.setVisibility(View.VISIBLE);
            } else {
                singleValueContainer.setVisibility(View.VISIBLE);
                arrayValueContainer.setVisibility(View.GONE);
                
                // Update hint based on type
                if (checkedId == R.id.type_bool) {
                    valueInput.setHint("true or false");
                } else if (checkedId == R.id.type_int) {
                    valueInput.setHint("Enter a number");
                    valueInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
                } else {
                    valueInput.setHint("Value");
                    valueInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                }
            }
        });
        
        // Refresh status button
        rootView.findViewById(R.id.btn_refresh_status).setOnClickListener(v -> updateDeviceOwnerStatus());
        
        // Reload app list when "Show All Apps" checkbox is toggled
        showAllAppsCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setupAppSearch();
        });

        // 1. DISCOVER BUTTON: See what rules an app supports (e.g. Chrome)
        rootView.findViewById(R.id.btn_discover).setOnClickListener(v -> {
            if (currentSelectedApp != null) {
                showSupportedRestrictions(currentSelectedApp.packageName);
            } else {
                Toast.makeText(this, "Please select an app first", Toast.LENGTH_SHORT).show();
            }
        });

        // 2. APPLY BUTTON: Push rule to target app and save
        rootView.findViewById(R.id.btn_apply).setOnClickListener(v -> {
            if (!dpm.isDeviceOwnerApp(getPackageName())) {
                updateDeviceOwnerStatus(); // Refresh the status
                Toast.makeText(this, "⚠️ Cannot apply: Not Device Owner. See status above.", Toast.LENGTH_LONG).show();
                return;
            }
            applyAndSaveRule();
        });
        
        // Import External Rules Button
        rootView.findViewById(R.id.btn_import_external).setOnClickListener(v -> {
            importExternalRules();
        });
        
        // Delete External Rules Button
        rootView.findViewById(R.id.btn_delete_external).setOnClickListener(v -> {
            deleteExternalRules();
        });
        
        // Export Rules Button
        rootView.findViewById(R.id.btn_export_rules).setOnClickListener(v -> {
            exportRules();
        });
        
        // Import Rules Button
        rootView.findViewById(R.id.btn_import_rules).setOnClickListener(v -> {
            importRulesFromFile();
        });
        
        // Single click to edit a rule
        rulesListView.setOnItemClickListener((parent, view, position, id) -> {
            if (position < activeRulesList.size()) {
                loadRuleForEditing(position);
            }
        });
        
        // Long click to delete a rule
        rulesListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < activeRulesList.size()) {
                deleteRule(position);
                return true;
            }
            return false;
        });
    }
    
    // Public method for TransferFragment to initialize its views
    public void initializeTransferTab(View rootView) {
        adminSpinner = rootView.findViewById(R.id.admin_spinner);
        setupAdminDiscovery();
        
        // 3. TRANSFER BUTTON: Hand over the DO role to any selected app
        rootView.findViewById(R.id.btn_transfer).setOnClickListener(v -> {
            AppInfo selectedApp = (AppInfo) adminSpinner.getSelectedItem();
            if (selectedApp != null) {
                transferOwnership(selectedApp.packageName);
            } else {
                Toast.makeText(this, "Please select an admin app", Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    // Public method for SettingsFragment to initialize its views
    public void initializeSettingsTab(View rootView) {
        EditText exportLocation = rootView.findViewById(R.id.export_location);
        
        // Load saved location
        String savedLocation = prefs.getString("export_location", android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
        exportLocation.setText(savedLocation);
        
        // Save settings button
        rootView.findViewById(R.id.btn_save_settings).setOnClickListener(v -> {
            String location = exportLocation.getText().toString().trim();
            prefs.edit().putString("export_location", location).apply();
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateDeviceOwnerStatus() {
        boolean isDeviceOwner = dpm.isDeviceOwnerApp(getPackageName());
        
        if (isDeviceOwner) {
            statusIndicator.setText("✅ Active Device Owner");
            statusIndicator.setTextColor(0xFF4CAF50); // Green
            if (statusIndicator.getParent() instanceof View) {
                ((View) statusIndicator.getParent()).setBackgroundColor(0xFFE8F5E9); // Light green background
            }
        } else {
            statusIndicator.setText("⚠️ Not Device Owner - Cannot Apply Rules");
            statusIndicator.setTextColor(0xFFF44336); // Red
            if (statusIndicator.getParent() instanceof View) {
                ((View) statusIndicator.getParent()).setBackgroundColor(0xFFFFEBEE); // Light red background
            }
        }
    }

    private void openFullScreenEditor() {
        // Create full-screen dialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen);
        View editorView = getLayoutInflater().inflate(R.layout.dialog_array_editor, null);
        
        EditText editorText = editorView.findViewById(R.id.editor_text);
        TextView editorStats = editorView.findViewById(R.id.editor_stats);
        Button toggleSepBtn = editorView.findViewById(R.id.btn_editor_toggle_sep);
        Button sortBtn = editorView.findViewById(R.id.btn_editor_sort);
        Button cancelBtn = editorView.findViewById(R.id.btn_editor_cancel);
        Button saveBtn = editorView.findViewById(R.id.btn_editor_save);
        
        // Load current content
        editorText.setText(arrayValueInput.getText().toString());
        updateEditorStats(editorText, editorStats);
        
        AlertDialog dialog = builder.setView(editorView).create();
        
        // Update stats on text change
        editorText.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateEditorStats(editorText, editorStats);
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });
        
        // Toggle separator
        toggleSepBtn.setOnClickListener(v -> {
            String currentText = editorText.getText().toString();
            if (currentText.isEmpty()) return;
            
            boolean isNewlineSeparated = currentText.contains("\n");
            
            if (isNewlineSeparated) {
                String[] items = currentText.split("\n");
                java.util.List<String> cleanedItems = new java.util.ArrayList<>();
                for (String item : items) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) cleanedItems.add(trimmed);
                }
                editorText.setText(String.join(", ", cleanedItems));
                toggleSepBtn.setText("Lines");
            } else {
                String[] items = currentText.split(",");
                java.util.List<String> cleanedItems = new java.util.ArrayList<>();
                for (String item : items) {
                    String trimmed = item.trim();
                    if (!trimmed.isEmpty()) cleanedItems.add(trimmed);
                }
                editorText.setText(String.join("\n", cleanedItems));
                toggleSepBtn.setText("Commas");
            }
        });
        
        // Sort alphabetically
        sortBtn.setOnClickListener(v -> {
            String currentText = editorText.getText().toString();
            if (currentText.isEmpty()) return;
            
            // Split by newlines or commas
            String[] items;
            if (currentText.contains("\n")) {
                items = currentText.split("\n");
            } else {
                items = currentText.split(",");
            }
            
            // Clean, sort, and rejoin
            java.util.List<String> cleanedItems = new java.util.ArrayList<>();
            for (String item : items) {
                String trimmed = item.trim();
                if (!trimmed.isEmpty()) cleanedItems.add(trimmed);
            }
            java.util.Collections.sort(cleanedItems, String.CASE_INSENSITIVE_ORDER);
            
            if (currentText.contains("\n")) {
                editorText.setText(String.join("\n", cleanedItems));
            } else {
                editorText.setText(String.join(", ", cleanedItems));
            }
        });

        cancelBtn.setOnClickListener(v -> dialog.dismiss());
        saveBtn.setOnClickListener(v -> {
            arrayValueInput.setText(editorText.getText().toString());
            dialog.dismiss();
        });

        dialog.show();
    }

    private void updateEditorStats(EditText editor, TextView stats) {
        String text = editor.getText().toString();
        if (text.isEmpty()) {
            stats.setText("0 items");
            return;
        }
        
        String[] items;
        if (text.contains("\n")) {
            items = text.split("\n");
        } else {
            items = text.split(",");
        }
        
        int count = 0;
        for (String s : items) {
            if (!s.trim().isEmpty()) count++;
        }
        stats.setText(count + " items");
    }

    private void setupAppSearch() {
        PackageManager pm = getPackageManager();
        appNameToPackageMap = new HashMap<>();
        appInfoList = new ArrayList<>();

        Intent intent = new Intent(Intent.ACTION_MAIN, null);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = pm.queryIntentActivities(intent, 0);

        boolean showAll = showAllAppsCheckbox != null && showAllAppsCheckbox.isChecked();

        for (ResolveInfo app : apps) {
            String pkgName = app.activityInfo.packageName;
            String label = app.loadLabel(pm).toString();
            
            // If checkbox is NOT checked, only show apps that define <restrictions>
            if (!showAll) {
                try {
                    Bundle meta = pm.getApplicationInfo(pkgName, PackageManager.GET_META_DATA).metaData;
                    if (meta == null || !meta.containsKey("android.content.APP_RESTRICTIONS")) {
                        continue; // Skip apps that don't explicitly support restrictions
                    }
                } catch (Exception e) {
                    continue;
                }
            }

            String display = label + " (" + pkgName + ")";
            android.graphics.drawable.Drawable icon = app.loadIcon(pm);
            appNameToPackageMap.put(display, pkgName);
            appInfoList.add(new AppInfo(display, pkgName, icon));
        }

        // Use custom adapter with icons (for backward compatibility if needed)
        AppSearchAdapter adapter = new AppSearchAdapter(this, appInfoList);
        appSearch.setAdapter(adapter);
        appSearch.setThreshold(1);
    }
    
    private void showAppSelectionDialog() {
        Dialog dialog = new Dialog(this);
        dialog.setContentView(R.layout.dialog_searchable_rules);
        if (dialog.getWindow() != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        dialog.setTitle("🔍 Select App");
        
        EditText searchInput = dialog.findViewById(R.id.search_rules);
        ListView listView = dialog.findViewById(R.id.rules_listview);
        
        if (searchInput == null || listView == null) {
            Toast.makeText(this, "Dialog layout error", Toast.LENGTH_SHORT).show();
            return;
        }
        
        AppSelectionAdapter adapter = new AppSelectionAdapter(this, appInfoList);
        listView.setAdapter(adapter);
        
        searchInput.setHint("🔍 Search apps...");
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
        
        listView.setOnItemClickListener((parent, view, position, id) -> {
            AppInfo selected = adapter.getItem(position);
            if (selected != null) {
                currentSelectedApp = selected;
                selectedAppIcon.setImageDrawable(selected.icon);
                selectedAppIcon.setVisibility(View.VISIBLE);
                selectedAppName.setText(selected.displayName);
                // Use theme-aware color
                android.content.res.TypedArray ta = obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
                selectedAppName.setTextColor(ta.getColor(0, 0xFF000000));
                ta.recycle();
                dialog.dismiss();
            }
        });
        
        dialog.show();
        
        // Auto-focus search field and show keyboard
        searchInput.requestFocus();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
    }
    
    // Adapter for app selection dialog
    private static class AppSelectionAdapter extends BaseAdapter implements Filterable {
        private Context context;
        private List<AppInfo> originalList;
        private List<AppInfo> filteredList;
        
        public AppSelectionAdapter(Context context, List<AppInfo> apps) {
            this.context = context;
            this.originalList = new ArrayList<>(apps);
            this.filteredList = new ArrayList<>(apps);
        }
        
        @Override
        public int getCount() { return filteredList.size(); }
        
        @Override
        public AppInfo getItem(int position) { return filteredList.get(position); }
        
        @Override
        public long getItemId(int position) { return position; }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                LayoutInflater inflater = LayoutInflater.from(context);
                convertView = inflater.inflate(R.layout.app_item, parent, false);
            }
            
            ImageView iconView = convertView.findViewById(R.id.app_icon);
            TextView nameView = convertView.findViewById(R.id.app_name);
            
            AppInfo app = filteredList.get(position);
            if (app != null) {
                iconView.setImageDrawable(app.icon);
                nameView.setText(app.displayName);
            }
            
            return convertView;
        }
        
        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    List<AppInfo> filtered = new ArrayList<>();
                    
                    if (constraint == null || constraint.length() == 0) {
                        filtered.addAll(originalList);
                    } else {
                        String filterPattern = constraint.toString().toLowerCase().trim();
                        for (AppInfo app : originalList) {
                            if (app.displayName.toLowerCase().contains(filterPattern) ||
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
                    filteredList = (List<AppInfo>) results.values;
                    notifyDataSetChanged();
                }
            };
        }
    }
    
    // Custom adapter for app search with icons
    private static class AppSearchAdapter extends ArrayAdapter<AppInfo> implements Filterable {
        private Context context;
        private List<AppInfo> originalList;
        private List<AppInfo> filteredList;
        
        public AppSearchAdapter(Context context, List<AppInfo> apps) {
            super(context, R.layout.app_item, apps);
            this.context = context;
            this.originalList = new ArrayList<>(apps);
            this.filteredList = new ArrayList<>(apps);
        }
        
        @Override
        public int getCount() {
            return filteredList.size();
        }
        
        @Override
        public AppInfo getItem(int position) {
            return filteredList.get(position);
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                LayoutInflater inflater = LayoutInflater.from(context);
                convertView = inflater.inflate(R.layout.app_item, parent, false);
            }
            
            ImageView iconView = convertView.findViewById(R.id.app_icon);
            TextView nameView = convertView.findViewById(R.id.app_name);
            
            AppInfo app = filteredList.get(position);
            if (app != null) {
                iconView.setImageDrawable(app.icon);
                nameView.setText(app.displayName);
            }
            
            return convertView;
        }
        
        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    List<AppInfo> filtered = new ArrayList<>();
                    
                    if (constraint == null || constraint.length() == 0) {
                        filtered.addAll(originalList);
                    } else {
                        String filterPattern = constraint.toString().toLowerCase().trim();
                        for (AppInfo app : originalList) {
                            if (app.displayName.toLowerCase().contains(filterPattern) ||
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
                    filteredList = (List<AppInfo>) results.values;
                    notifyDataSetChanged();
                }
            };
        }
    }

    private void setupAdminDiscovery() {
        PackageManager pm = getPackageManager();
        List<AppInfo> adminApps = new ArrayList<>();
        
        // Find all Device Admins
        Intent intent = new Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED);
        List<ResolveInfo> admins = pm.queryBroadcastReceivers(intent, 0);
        
        for (ResolveInfo admin : admins) {
            String pkgName = admin.activityInfo.packageName;
            try {
                android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(pkgName, 0);
                String appName = pm.getApplicationLabel(appInfo).toString();
                android.graphics.drawable.Drawable icon = pm.getApplicationIcon(pkgName);
                adminApps.add(new AppInfo(appName + " (" + pkgName + ")", pkgName, icon));
            } catch (Exception e) {
                adminApps.add(new AppInfo(pkgName, pkgName, null));
            }
        }
        
        AdminSpinnerAdapter adapter = new AdminSpinnerAdapter(this, adminApps);
        adminSpinner.setAdapter(adapter);
    }
    
    // Custom adapter for admin spinner with icons
    private static class AdminSpinnerAdapter extends BaseAdapter {
        private Context context;
        private List<AppInfo> apps;
        
        public AdminSpinnerAdapter(Context context, List<AppInfo> apps) {
            this.context = context;
            this.apps = apps;
        }
        
        @Override
        public int getCount() {
            return apps.size();
        }
        
        @Override
        public AppInfo getItem(int position) {
            return apps.get(position);
        }
        
        @Override
        public long getItemId(int position) {
            return position;
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return createView(position, convertView, parent);
        }
        
        @Override
        public View getDropDownView(int position, View convertView, ViewGroup parent) {
            return createView(position, convertView, parent);
        }
        
        private View createView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                LayoutInflater inflater = LayoutInflater.from(context);
                convertView = inflater.inflate(R.layout.app_item, parent, false);
            }
            
            ImageView iconView = convertView.findViewById(R.id.app_icon);
            TextView nameView = convertView.findViewById(R.id.app_name);
            
            AppInfo app = apps.get(position);
            if (app != null) {
                if (app.icon != null) {
                    iconView.setImageDrawable(app.icon);
                } else {
                    iconView.setImageResource(android.R.drawable.sym_def_app_icon);
                }
                nameView.setText(app.displayName);
            }
            
            return convertView;
        }
    }

    private void refreshRulesList() {
        activeRulesList = new ArrayList<>();
        List<ActiveRuleInfo> displayList = new ArrayList<>();
        
        android.util.Log.d("RULES_DEBUG", "=== Refreshing Rules List ===");
        
        // 1. Get BlindSpot's own rules from SharedPreferences
        Set<String> rulesSet = prefs.getStringSet("rules", new HashSet<>());
        android.util.Log.d("RULES_DEBUG", "Found rules set with " + rulesSet.size() + " entries");
        
        PackageManager pm = getPackageManager();
        
        for (String ruleEntry : rulesSet) {
            android.util.Log.d("RULES_DEBUG", "Processing rule: " + ruleEntry);
            
            // Format is: "pkg|key|value|type"
            String[] parts = ruleEntry.split("\\|");
            if (parts.length >= 4) {
                String pkg = parts[0];
                String key = parts[1];
                String value = parts[2];
                String type = parts[3];
                
                // Store for parsing (in activeRulesList)
                activeRulesList.add(ruleEntry);
                
                // Get app info
                String appName = getAppNameFromPackage(pkg);
                android.graphics.drawable.Drawable icon = null;
                try {
                    icon = pm.getApplicationIcon(pkg);
                } catch (Exception e) {}
                
                displayList.add(new ActiveRuleInfo(appName, pkg, key, value, type, icon, "BlindSpot"));
                android.util.Log.d("RULES_DEBUG", "Added rule: " + appName + " / " + key);
            }
        }
        
        // 2. Check ALL installed apps for restrictions set by other admins
        try {
            List<ApplicationInfo> packages = pm.getInstalledApplications(0);
            Set<String> processedRules = new HashSet<>();
            
            for (ApplicationInfo appInfo : packages) {
                try {
                    Bundle restrictions = dpm.getApplicationRestrictions(adminComponent, appInfo.packageName);
                    if (restrictions != null && !restrictions.isEmpty()) {
                        for (String key : restrictions.keySet()) {
                            Object value = restrictions.get(key);
                            if (value != null) {
                                String ruleKey = appInfo.packageName + "|" + key;
                                if (!processedRules.contains(ruleKey)) {
                                    processedRules.add(ruleKey);
                                    
                                    // Check if this rule is already in BlindSpot's rules
                                    boolean isBlindSpotRule = false;
                                    for (String bsRule : rulesSet) {
                                        if (bsRule.startsWith(ruleKey + "|")) {
                                            isBlindSpotRule = true;
                                            break;
                                        }
                                    }
                                    
                                    // Only add as external if not managed by BlindSpot
                                    if (!isBlindSpotRule) {
                                        String appName = pm.getApplicationLabel(appInfo).toString();
                                        android.graphics.drawable.Drawable icon = pm.getApplicationIcon(appInfo);
                                        
                                        String valueStr = value.toString();
                                        String type = value.getClass().getSimpleName();
                                        
                                        // Add to activeRulesList with EXTERNAL marker
                                        activeRulesList.add("EXTERNAL|" + appInfo.packageName + "|" + key + "|" + valueStr + "|" + type);
                                        
                                        displayList.add(new ActiveRuleInfo(appName, appInfo.packageName, key, valueStr, type, icon, "External Admin"));
                                        android.util.Log.d("RULES_DEBUG", "Added external rule: " + appName + " / " + key);
                                    }
                                }
                            }
                        }
                    }
                } catch (Exception e) {}
            }
        } catch (Exception e) {
            android.util.Log.e("RULES_DEBUG", "Error checking external rules: " + e.getMessage());
        }
        
        // Sort by app name first, then by key
        displayList.sort((a, b) -> {
            int appCompare = a.appName.compareToIgnoreCase(b.appName);
            if (appCompare != 0) return appCompare;
            return a.key.compareToIgnoreCase(b.key);
        });
        
        // Rebuild activeRulesList in the same order as displayList (after sorting)
        activeRulesList.clear();
        for (ActiveRuleInfo info : displayList) {
            // Reconstruct the entry format
            String entry;
            if (info.adminSource.equals("External Admin")) {
                entry = "EXTERNAL|" + info.packageName + "|" + info.key + "|" + info.value + "|" + info.type;
            } else {
                entry = info.packageName + "|" + info.key + "|" + info.value + "|" + info.type;
            }
            activeRulesList.add(entry);
        }
        
        android.util.Log.d("RULES_DEBUG", "Final displayList size: " + displayList.size());
        
        ActiveRulesAdapter adapter = new ActiveRulesAdapter(this, displayList);
        rulesListView.setAdapter(adapter);
    }
    
    // Helper class for active rule display
    private static class ActiveRuleInfo {
        String appName;
        String packageName;
        String key;
        String value;
        String type;
        android.graphics.drawable.Drawable icon;
        String adminSource; // "BlindSpot" or "External Admin"
        
        ActiveRuleInfo(String appName, String packageName, String key, String value, String type, android.graphics.drawable.Drawable icon, String adminSource) {
            this.appName = appName;
            this.packageName = packageName;
            this.key = key;
            this.value = value;
            this.type = type;
            this.icon = icon;
            this.adminSource = adminSource;
        }
    }
    
    // Custom adapter for active rules with icons
    private static class ActiveRulesAdapter extends BaseAdapter {
        private Context context;
        private List<ActiveRuleInfo> rules;
        
        public ActiveRulesAdapter(Context context, List<ActiveRuleInfo> rules) {
            this.context = context;
            this.rules = rules;
        }
        
        @Override
        public int getCount() {
            return rules.isEmpty() ? 1 : rules.size();
        }
        
        @Override
        public ActiveRuleInfo getItem(int position) {
            return rules.isEmpty() ? null : rules.get(position);
        }
        
        @Override
        public long getItemId(int position) {
            return position;
        }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (rules.isEmpty()) {
                // Show "No rules" message
                if (convertView == null || convertView.getId() != android.R.id.text1) {
                    TextView tv = new TextView(context);
                    tv.setId(android.R.id.text1);
                    tv.setPadding(32, 32, 32, 32);
                    tv.setTextSize(14);
                    tv.setTextColor(0xFF888888);
                    tv.setText("No rules configured yet\n\nTap to edit • Long press to delete");
                    convertView = tv;
                }
                return convertView;
            }
            
            if (convertView == null || convertView.getId() != R.id.active_rule_app_icon) {
                LayoutInflater inflater = LayoutInflater.from(context);
                convertView = inflater.inflate(R.layout.active_rule_item, parent, false);
            }
            
            ImageView iconView = convertView.findViewById(R.id.active_rule_app_icon);
            TextView appNameView = convertView.findViewById(R.id.active_rule_app_name);
            TextView keyView = convertView.findViewById(R.id.active_rule_key);
            TextView valueView = convertView.findViewById(R.id.active_rule_value);
            TextView typeBadge = convertView.findViewById(R.id.active_rule_type_badge);
            TextView adminView = convertView.findViewById(R.id.active_rule_admin);
            TextView adminBadge = convertView.findViewById(R.id.active_rule_admin_badge);
            
            ActiveRuleInfo rule = rules.get(position);
            if (rule != null) {
                if (rule.icon != null) {
                    iconView.setImageDrawable(rule.icon);
                } else {
                    iconView.setImageResource(android.R.drawable.sym_def_app_icon);
                }
                appNameView.setText(rule.appName);
                keyView.setText(rule.key);
                
                // Truncate value if too long
                String displayValue = rule.value;
                if (displayValue.length() > 80) {
                    displayValue = displayValue.substring(0, 77) + "...";
                }
                valueView.setText(displayValue);
                
                // Display admin source with colored badge
                if (rule.adminSource.equals("BlindSpot")) {
                    adminBadge.setText("BS");
                    adminBadge.setBackgroundColor(0xFF4CAF50); // Green for BlindSpot
                    adminView.setText("Set by: " + rule.adminSource);
                } else {
                    adminBadge.setText("EXT");
                    adminBadge.setBackgroundColor(0xFFFF5722); // Orange/Red for External
                    adminView.setText("Set by: " + rule.adminSource);
                }
                
                // Set badge color based on type
                typeBadge.setText(rule.type);
                switch (rule.type) {
                    case "String":
                        typeBadge.setBackgroundColor(0xFF2196F3); // Blue
                        break;
                    case "Integer":
                        typeBadge.setBackgroundColor(0xFFFF9800); // Orange
                        break;
                    case "Boolean":
                        typeBadge.setBackgroundColor(0xFF9C27B0); // Purple
                        break;
                    case "Array":
                        typeBadge.setBackgroundColor(0xFF4CAF50); // Green
                        break;
                    default:
                        typeBadge.setBackgroundColor(0xFF757575); // Grey
                        break;
                }
            }
            
            return convertView;
        }
    }

    private String getAppNameFromPackage(String pkg) {
        try {
            PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo appInfo = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(appInfo).toString();
        } catch (Exception e) {
            return pkg; // Fallback to package name
        }
    }

    private void showSupportedRestrictions(String packageName) {
        RestrictionsManager rm = (RestrictionsManager) getSystemService(Context.RESTRICTIONS_SERVICE);
        try {
            List<RestrictionEntry> entries = rm.getManifestRestrictions(packageName);
            if (entries == null || entries.isEmpty()) {
                Toast.makeText(this, "No restrictions found in manifest for " + packageName, Toast.LENGTH_SHORT).show();
                return;
            }

            // Use custom searchable dialog
            Dialog dialog = new Dialog(this);
            dialog.setContentView(R.layout.dialog_searchable_rules);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            }
            dialog.setTitle("🔍 Available Restrictions for " + packageName);
            
            EditText searchInput = dialog.findViewById(R.id.search_rules);
            ListView listView = dialog.findViewById(R.id.rules_listview);
            
            if (searchInput == null || listView == null) {
                Toast.makeText(this, "Dialog layout error", Toast.LENGTH_SHORT).show();
                return;
            }
            
            RuleAdapter adapter = new RuleAdapter(this, entries);
            listView.setAdapter(adapter);
            
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
            
            listView.setOnItemClickListener((parent, view, position, id) -> {
                RestrictionEntry selectedEntry = adapter.getItem(position);
                if (selectedEntry != null) {
                    keyInput.setText(selectedEntry.getKey());
                    dialog.dismiss();
                    Toast.makeText(this, "Selected: " + selectedEntry.getKey(), Toast.LENGTH_SHORT).show();
                }
            });
            
            dialog.show();

        } catch (Exception e) {
            Toast.makeText(this, "Error fetching restrictions: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
    
    // Custom adapter for searchable rules
    private static class RuleAdapter extends BaseAdapter implements Filterable {
        private Context context;
        private List<RestrictionEntry> originalList;
        private List<RestrictionEntry> filteredList;
        
        public RuleAdapter(Context context, List<RestrictionEntry> entries) {
            this.context = context;
            this.originalList = entries;
            this.filteredList = new ArrayList<>(entries);
        }
        
        @Override
        public int getCount() { return filteredList.size(); }
        
        @Override
        public RestrictionEntry getItem(int position) { return filteredList.get(position); }
        
        @Override
        public long getItemId(int position) { return position; }
        
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                LayoutInflater inflater = LayoutInflater.from(context);
                convertView = inflater.inflate(R.layout.rule_item, parent, false);
            }
            
            TextView keyView = convertView.findViewById(R.id.rule_key);
            TextView titleView = convertView.findViewById(R.id.rule_title);
            TextView descView = convertView.findViewById(R.id.rule_description);
            
            RestrictionEntry entry = filteredList.get(position);
            if (entry != null) {
                keyView.setText(entry.getKey());
                titleView.setText(getEntryTypeString(entry.getType()));
                descView.setText(entry.getDescription() != null ? entry.getDescription() : "No description");
            }
            
            return convertView;
        }
        
        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults results = new FilterResults();
                    List<RestrictionEntry> filtered = new ArrayList<>();
                    
                    if (constraint == null || constraint.length() == 0) {
                        filtered.addAll(originalList);
                    } else {
                        String filterPattern = constraint.toString().toLowerCase().trim();
                        for (RestrictionEntry entry : originalList) {
                            if (entry.getKey().toLowerCase().contains(filterPattern) ||
                                (entry.getTitle() != null && entry.getTitle().toLowerCase().contains(filterPattern)) ||
                                (entry.getDescription() != null && entry.getDescription().toLowerCase().contains(filterPattern))) {
                                filtered.add(entry);
                            }
                        }
                    }
                    
                    results.values = filtered;
                    results.count = filtered.size();
                    return results;
                }
                
                @Override
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    filteredList = (List<RestrictionEntry>) results.values;
                    notifyDataSetChanged();
                }
            };
        }
        
        private static String getEntryTypeString(int type) {
            switch (type) {
                case RestrictionEntry.TYPE_BOOLEAN: return "Boolean";
                case RestrictionEntry.TYPE_INTEGER: return "Integer";
                case RestrictionEntry.TYPE_STRING: return "String";
                case RestrictionEntry.TYPE_MULTI_SELECT: return "String Array (Multi-select)";
                case RestrictionEntry.TYPE_CHOICE: return "Choice";
                case RestrictionEntry.TYPE_BUNDLE: return "Bundle";
                case RestrictionEntry.TYPE_BUNDLE_ARRAY: return "Bundle Array";
                default: return "Unknown (" + type + ")";
            }
        }
    }

    private void applyAndSaveRule() {
        if (currentSelectedApp == null) {
            Toast.makeText(this, "Please select an app first", Toast.LENGTH_SHORT).show();
            return;
        }
        
        String pkg = currentSelectedApp.packageName;
        String key = keyInput.getText().toString().trim();
        
        if (key.isEmpty()) {
            Toast.makeText(this, "Please enter a restriction key", Toast.LENGTH_SHORT).show();
            return;
        }

        Bundle bundle = dpm.getApplicationRestrictions(adminComponent, pkg);
        if (bundle == null) bundle = new Bundle();

        int checkedId = typeRadioGroup.getCheckedRadioButtonId();
        String valueStr = valueInput.getText().toString().trim();
        String storageValue;
        String dataType;

        try {
            if (checkedId == R.id.type_bool) {
                boolean val = Boolean.parseBoolean(valueStr);
                bundle.putBoolean(key, val);
                storageValue = String.valueOf(val);
                dataType = "Boolean";
            } else if (checkedId == R.id.type_int) {
                int val = Integer.parseInt(valueStr);
                bundle.putInt(key, val);
                storageValue = String.valueOf(val);
                dataType = "Integer";
            } else if (checkedId == R.id.type_string_array) {
                String raw = arrayValueInput.getText().toString();
                String[] items;
                if (raw.contains("\n")) {
                    items = raw.split("\n");
                } else {
                    items = raw.split(",");
                }
                
                List<String> cleaned = new ArrayList<>();
                for (String item : items) {
                    if (!item.trim().isEmpty()) cleaned.add(item.trim());
                }
                String[] array = cleaned.toArray(new String[0]);
                bundle.putStringArray(key, array);
                storageValue = String.join(", ", array);
                dataType = "Array";
            } else {
                bundle.putString(key, valueStr);
                storageValue = valueStr;
                dataType = "String";
            }

            // Apply to the system
            dpm.setApplicationRestrictions(adminComponent, pkg, bundle);
            
            // Save to local prefs in StringSet format: "pkg|key|value|type"
            Set<String> rulesSet = new HashSet<>(prefs.getStringSet("rules", new HashSet<>()));
            
            // Remove any existing rule with same pkg+key first (update scenario)
            rulesSet.removeIf(rule -> {
                String[] parts = rule.split("\\|");
                return parts.length >= 2 && parts[0].equals(pkg) && parts[1].equals(key);
            });
            
            // Add new rule
            String ruleEntry = pkg + "|" + key + "|" + storageValue + "|" + dataType;
            rulesSet.add(ruleEntry);
            prefs.edit().putStringSet("rules", rulesSet).apply();
            
            Toast.makeText(this, "Applied: " + key + " = " + storageValue, Toast.LENGTH_SHORT).show();
            refreshRulesList();
        } catch (Exception e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadRuleForEditing(int position) {
        String entry = activeRulesList.get(position);
        
        // Remove EXTERNAL| prefix if present
        if (entry.startsWith("EXTERNAL|")) {
            entry = entry.substring(9); // Remove "EXTERNAL|"
        }
        
        // Format is "pkg|key|value|type"
        try {
            String[] parts = entry.split("\\|");
            if (parts.length < 4) return;
            
            String pkg = parts[0];
            String key = parts[1];
            String value = parts[2];
            String type = parts[3];
            
            // Find and set the app
            for (AppInfo app : appInfoList) {
                if (app.packageName.equals(pkg)) {
                    currentSelectedApp = app;
                    selectedAppIcon.setImageDrawable(app.icon);
                    selectedAppIcon.setVisibility(View.VISIBLE);
                    selectedAppName.setText(app.displayName);
                    // Use theme-aware color
                    android.content.res.TypedArray ta = obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
                    selectedAppName.setTextColor(ta.getColor(0, 0xFF000000));
                    ta.recycle();
                    break;
                }
            }
            
            keyInput.setText(key);
            
            // Fetch current real bundle value
            Bundle bundle = dpm.getApplicationRestrictions(adminComponent, pkg);
            Object bundleValue = bundle.get(key);
            
            if (bundleValue instanceof Boolean) {
                typeRadioGroup.check(R.id.type_bool);
                valueInput.setText(bundleValue.toString());
            } else if (bundleValue instanceof Integer) {
                typeRadioGroup.check(R.id.type_int);
                valueInput.setText(bundleValue.toString());
            } else if (bundleValue instanceof String[]) {
                typeRadioGroup.check(R.id.type_string_array);
                arrayValueInput.setText(String.join("\n", (String[]) bundleValue));
            } else {
                typeRadioGroup.check(R.id.type_string);
                valueInput.setText(bundleValue != null ? bundleValue.toString() : "");
            }
        } catch (Exception e) {}
    }

    private void deleteRule(int position) {
        String entry = activeRulesList.get(position);
        
        // Remove EXTERNAL| prefix if present
        final String cleanEntry;
        if (entry.startsWith("EXTERNAL|")) {
            cleanEntry = entry.substring(9); // Remove "EXTERNAL|"
        } else {
            cleanEntry = entry;
        }
        
        // Format is "pkg|key|value|type"
        String[] parts = cleanEntry.split("\\|");
        if (parts.length < 4) return;
        
        String pkg = parts[0];
        String key = parts[1];

        new AlertDialog.Builder(this)
            .setTitle("Delete Rule?")
            .setMessage("Do you want to remove the restriction '" + key + "' from " + pkg + "?")
            .setPositiveButton("Delete", (dialog, which) -> {
                // 1. Remove from System
                Bundle bundle = dpm.getApplicationRestrictions(adminComponent, pkg);
                if (bundle != null) {
                    bundle.remove(key);
                    dpm.setApplicationRestrictions(adminComponent, pkg, bundle);
                }
                // 2. Remove from Local Prefs (StringSet) - only if not external
                if (!entry.startsWith("EXTERNAL|")) {
                    Set<String> rulesSet = new HashSet<>(prefs.getStringSet("rules", new HashSet<>()));
                    rulesSet.remove(cleanEntry);
                    prefs.edit().putStringSet("rules", rulesSet).apply();
                }
                
                refreshRulesList();
                Toast.makeText(this, "Rule deleted", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void importExternalRules() {
        new AlertDialog.Builder(this)
            .setTitle("Import External Rules?")
            .setMessage("This will copy all rules set by other admins to BlindSpot's rules list. The rules will remain active and now managed by BlindSpot.")
            .setPositiveButton("Import", (dialog, which) -> {
                int importedCount = 0;
                Set<String> rulesSet = new HashSet<>(prefs.getStringSet("rules", new HashSet<>()));
                
                // Find all external rules in activeRulesList
                for (String entry : activeRulesList) {
                    if (entry.startsWith("EXTERNAL|")) {
                        // Remove EXTERNAL| prefix and add to BlindSpot's rules
                        String cleanEntry = entry.substring(9);
                        rulesSet.add(cleanEntry);
                        importedCount++;
                    }
                }
                
                // Save updated rules set
                prefs.edit().putStringSet("rules", rulesSet).apply();
                
                refreshRulesList();
                Toast.makeText(this, "Imported " + importedCount + " external rules", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void deleteExternalRules() {
        new AlertDialog.Builder(this)
            .setTitle("Delete External Rules?")
            .setMessage("This will remove ALL restrictions set by other admins from the system. This action cannot be undone.")
            .setPositiveButton("Delete All", (dialog, which) -> {
                int deletedCount = 0;
                
                // Find all external rules in activeRulesList
                for (String entry : activeRulesList) {
                    if (entry.startsWith("EXTERNAL|")) {
                        // Remove EXTERNAL| prefix
                        String cleanEntry = entry.substring(9);
                        String[] parts = cleanEntry.split("\\|");
                        if (parts.length >= 4) {
                            String pkg = parts[0];
                            String key = parts[1];
                            
                            // Remove from system
                            Bundle bundle = dpm.getApplicationRestrictions(adminComponent, pkg);
                            if (bundle != null) {
                                bundle.remove(key);
                                dpm.setApplicationRestrictions(adminComponent, pkg, bundle);
                                deletedCount++;
                            }
                        }
                    }
                }
                
                refreshRulesList();
                Toast.makeText(this, "Deleted " + deletedCount + " external rules", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void exportRules() {
        try {
            // Get all rules
            Set<String> rulesSet = prefs.getStringSet("rules", new HashSet<>());
            
            if (rulesSet.isEmpty()) {
                Toast.makeText(this, "No rules to export", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // Create JSON format
            StringBuilder json = new StringBuilder();
            json.append("{\n  \"rules\": [\n");
            
            int count = 0;
            for (String rule : rulesSet) {
                if (count > 0) json.append(",\n");
                
                String[] parts = rule.split("\\|");
                if (parts.length >= 4) {
                    json.append("    {\n");
                    json.append("      \"package\": \"").append(parts[0]).append("\",\n");
                    json.append("      \"key\": \"").append(parts[1]).append("\",\n");
                    json.append("      \"value\": \"").append(parts[2].replace("\"", "\\\"")).append("\",\n");
                    json.append("      \"type\": \"").append(parts[3]).append("\"\n");
                    json.append("    }");
                    count++;
                }
            }
            
            json.append("\n  ]\n}");
            
            // Get export location from settings
            String exportPath = prefs.getString("export_location", android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
            java.io.File exportDir = new java.io.File(exportPath);
            if (!exportDir.exists()) {
                exportDir.mkdirs();
            }
            
            String filename = "BlindSpot_Rules_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date()) + ".json";
            java.io.File file = new java.io.File(exportDir, filename);
            
            java.io.FileWriter writer = new java.io.FileWriter(file);
            writer.write(json.toString());
            writer.close();
            
            Toast.makeText(this, "Exported " + count + " rules to:\n" + file.getAbsolutePath(), Toast.LENGTH_LONG).show();
            
        } catch (Exception e) {
            Toast.makeText(this, "Export failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            android.util.Log.e("EXPORT", "Error exporting rules", e);
        }
    }
    
    private void importRulesFromFile() {
        // Get export location from settings
        String exportPath = prefs.getString("export_location", android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS).getAbsolutePath());
        java.io.File exportDir = new java.io.File(exportPath);
        
        if (!exportDir.exists() || !exportDir.isDirectory()) {
            Toast.makeText(this, "Export directory not found", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // List all JSON files in the directory
        java.io.File[] files = exportDir.listFiles((dir, name) -> name.endsWith(".json"));
        
        if (files == null || files.length == 0) {
            Toast.makeText(this, "No JSON files found in export directory", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // Show file selection dialog
        String[] fileNames = new String[files.length];
        for (int i = 0; i < files.length; i++) {
            fileNames[i] = files[i].getName();
        }
        
        final java.io.File[] finalFiles = files;
        new AlertDialog.Builder(this)
            .setTitle("Select File to Import")
            .setItems(fileNames, (dialog, which) -> {
                importRulesFromJsonFile(finalFiles[which]);
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
    
    private void importRulesFromJsonFile(java.io.File file) {
        try {
            // Read file content
            java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(file));
            StringBuilder jsonContent = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonContent.append(line);
            }
            reader.close();
            
            // Parse JSON manually (simple parsing)
            String json = jsonContent.toString();
            Set<String> rulesSet = new HashSet<>(prefs.getStringSet("rules", new HashSet<>()));
            
            // Extract rules from JSON
            int count = 0;
            int startIndex = json.indexOf("\"rules\"");
            if (startIndex != -1) {
                // Find each rule object
                int pos = startIndex;
                while (true) {
                    int packageStart = json.indexOf("\"package\"", pos);
                    if (packageStart == -1) break;
                    
                    int packageValueStart = json.indexOf("\"", packageStart + 11) + 1;
                    int packageValueEnd = json.indexOf("\"", packageValueStart);
                    String pkg = json.substring(packageValueStart, packageValueEnd);
                    
                    int keyStart = json.indexOf("\"key\"", packageValueEnd);
                    if (keyStart == -1) break;
                    int keyValueStart = json.indexOf("\"", keyStart + 7) + 1;
                    int keyValueEnd = json.indexOf("\"", keyValueStart);
                    String key = json.substring(keyValueStart, keyValueEnd);
                    
                    int valueStart = json.indexOf("\"value\"", keyValueEnd);
                    if (valueStart == -1) break;
                    int valueValueStart = json.indexOf("\"", valueStart + 9) + 1;
                    int valueValueEnd = json.indexOf("\"", valueValueStart);
                    String value = json.substring(valueValueStart, valueValueEnd).replace("\\\"", "\"");
                    
                    int typeStart = json.indexOf("\"type\"", valueValueEnd);
                    if (typeStart == -1) break;
                    int typeValueStart = json.indexOf("\"", typeStart + 8) + 1;
                    int typeValueEnd = json.indexOf("\"", typeValueStart);
                    String type = json.substring(typeValueStart, typeValueEnd);
                    
                    // Add rule
                    String rule = pkg + "|" + key + "|" + value + "|" + type;
                    rulesSet.add(rule);
                    count++;
                    
                    pos = typeValueEnd;
                }
            }
            
            // Save rules
            prefs.edit().putStringSet("rules", rulesSet).apply();
            
            // Apply rules to system
            for (String rule : rulesSet) {
                String[] parts = rule.split("\\|");
                if (parts.length >= 4) {
                    try {
                        Bundle bundle = dpm.getApplicationRestrictions(adminComponent, parts[0]);
                        if (bundle == null) bundle = new Bundle();
                        
                        // Add restriction based on type
                        switch (parts[3]) {
                            case "Boolean":
                                bundle.putBoolean(parts[1], Boolean.parseBoolean(parts[2]));
                                break;
                            case "Integer":
                                bundle.putInt(parts[1], Integer.parseInt(parts[2]));
                                break;
                            case "Array":
                                String[] array = parts[2].split(",");
                                bundle.putStringArray(parts[1], array);
                                break;
                            default:
                                bundle.putString(parts[1], parts[2]);
                                break;
                        }
                        
                        dpm.setApplicationRestrictions(adminComponent, parts[0], bundle);
                    } catch (Exception e) {
                        android.util.Log.e("IMPORT", "Error applying rule: " + rule, e);
                    }
                }
            }
            
            refreshRulesList();
            Toast.makeText(this, "Imported " + count + " rules from:\n" + file.getName(), Toast.LENGTH_LONG).show();
            
        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            android.util.Log.e("IMPORT", "Error importing rules", e);
        }
    }

    private void transferOwnership(String targetPackage) {
        new AlertDialog.Builder(this)
            .setTitle("⚠️ Transfer Device Ownership?")
            .setMessage("This will transfer device owner rights to:\n\n" + targetPackage + "\n\nBlindSpot will lose admin privileges. This action cannot be undone.\n\nAre you sure?")
            .setPositiveButton("Transfer", (dialog, which) -> {
                try {
                    // Find the DeviceAdminReceiver component for this package
                    Intent intent = new Intent(DeviceAdminReceiver.ACTION_DEVICE_ADMIN_ENABLED);
                    PackageManager pm = getPackageManager();
                    List<ResolveInfo> admins = pm.queryBroadcastReceivers(intent, 0);
                    
                    ComponentName targetAdmin = null;
                    for (ResolveInfo admin : admins) {
                        if (admin.activityInfo.packageName.equals(targetPackage)) {
                            targetAdmin = new ComponentName(
                                admin.activityInfo.packageName,
                                admin.activityInfo.name
                            );
                            break;
                        }
                    }
                    
                    if (targetAdmin == null) {
                        Toast.makeText(this, "Error: " + targetPackage + " is not a valid device admin", Toast.LENGTH_LONG).show();
                        return;
                    }
                    
                    dpm.transferOwnership(adminComponent, targetAdmin, null);
                    Toast.makeText(this, "✅ Ownership transferred to " + targetPackage, Toast.LENGTH_LONG).show();
                    updateDeviceOwnerStatus();
                } catch (Exception e) {
                    Toast.makeText(this, "❌ Transfer failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
