package com.hardening.blindspot;

import android.app.Activity;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class MainActivity extends Activity {

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

        // Initialize UI
        statusIndicator = findViewById(R.id.status_indicator);
        appSearch = findViewById(R.id.app_search);
        selectedAppContainer = findViewById(R.id.selected_app_container);
        selectedAppIcon = findViewById(R.id.selected_app_icon);
        selectedAppName = findViewById(R.id.selected_app_name);
        showAllAppsCheckbox = findViewById(R.id.show_all_apps);
        typeRadioGroup = findViewById(R.id.type_radio_group);
        adminSpinner = findViewById(R.id.admin_spinner);
        keyInput = findViewById(R.id.key_input);
        valueInput = findViewById(R.id.value_input);
        arrayValueInput = findViewById(R.id.array_value_input);
        singleValueContainer = findViewById(R.id.single_value_container);
        arrayValueContainer = findViewById(R.id.array_value_container);
        rulesListView = findViewById(R.id.rules_list);

        updateDeviceOwnerStatus();
        setupAppSearch();
        setupAdminDiscovery();
        refreshRulesList();
        
        // Handle app selection - show dialog with searchable list
        selectedAppContainer.setOnClickListener(v -> showAppSelectionDialog());
        
        // Toggle separator button for array values
        Button toggleSeparatorBtn = findViewById(R.id.btn_toggle_separator);
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
        
        // Open full-screen array editor
        findViewById(R.id.btn_open_full_editor).setOnClickListener(v -> openFullScreenEditor());
        
        // Toggle input fields based on data type selection
        typeRadioGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.type_string_array) {
                singleValueContainer.setVisibility(View.GONE);
                arrayValueContainer.setVisibility(View.VISIBLE);
            } else {
                singleValueContainer.setVisibility(View.VISIBLE);
                arrayValueContainer.setVisibility(View.GONE);
                
                // Update hint based on type
                if (checkedId == R.id.type_boolean) {
                    valueInput.setHint("true or false");
                } else if (checkedId == R.id.type_integer) {
                    valueInput.setHint("Enter a number");
                    valueInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER | android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);
                } else {
                    valueInput.setHint("Value");
                    valueInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
                }
            }
        });
        
        // Refresh status button
        findViewById(R.id.btn_refresh_status).setOnClickListener(v -> updateDeviceOwnerStatus());
        
        // Reload app list when "Show All Apps" checkbox is toggled
        showAllAppsCheckbox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            setupAppSearch();
        });

        // 1. DISCOVER BUTTON: See what rules an app supports (e.g. Chrome)
        findViewById(R.id.btn_discover).setOnClickListener(v -> {
            if (currentSelectedApp != null) {
                showSupportedRestrictions(currentSelectedApp.packageName);
            } else {
                Toast.makeText(this, "Please select an app first", Toast.LENGTH_SHORT).show();
            }
        });

        // 2. APPLY BUTTON: Push rule to target app and save
        findViewById(R.id.btn_apply).setOnClickListener(v -> {
            if (!dpm.isDeviceOwnerApp(getPackageName())) {
                updateDeviceOwnerStatus(); // Refresh the status
                Toast.makeText(this, "⚠️ Cannot apply: Not Device Owner. See status above.", Toast.LENGTH_LONG).show();
                return;
            }
            applyAndSaveRule();
        });

        // 3. TRANSFER BUTTON: Hand over the DO role to any selected app
        findViewById(R.id.btn_transfer).setOnClickListener(v -> {
            AppInfo selectedApp = (AppInfo) adminSpinner.getSelectedItem();
            if (selectedApp != null) {
                transferOwnership(selectedApp.packageName);
            } else {
                Toast.makeText(this, "Please select an admin app", Toast.LENGTH_SHORT).show();
            }
        });
        
        // Single click to edit a rule
        rulesListView.setOnItemClickListener((parent, view, position, id) -> {
            loadRuleForEditing(position);
        });
        
        // Long click to delete a rule
        rulesListView.setOnItemLongClickListener((parent, view, position, id) -> {
            deleteRule(position);
            return true;
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
        
        // Check for StringSet format
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
                
                displayList.add(new ActiveRuleInfo(appName, pkg, key, value, type, icon));
                android.util.Log.d("RULES_DEBUG", "Added rule: " + appName + " / " + key);
            }
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
        
        ActiveRuleInfo(String appName, String packageName, String key, String value, String type, android.graphics.drawable.Drawable icon) {
            this.appName = appName;
            this.packageName = packageName;
            this.key = key;
            this.value = value;
            this.type = type;
            this.icon = icon;
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
            if (checkedId == R.id.type_boolean) {
                boolean val = Boolean.parseBoolean(valueStr);
                bundle.putBoolean(key, val);
                storageValue = String.valueOf(val);
                dataType = "Boolean";
            } else if (checkedId == R.id.type_integer) {
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
                typeRadioGroup.check(R.id.type_boolean);
                valueInput.setText(bundleValue.toString());
            } else if (bundleValue instanceof Integer) {
                typeRadioGroup.check(R.id.type_integer);
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
        // Format is "pkg|key|value|type"
        String[] parts = entry.split("\\|");
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
                // 2. Remove from Local Prefs (StringSet)
                Set<String> rulesSet = new HashSet<>(prefs.getStringSet("rules", new HashSet<>()));
                rulesSet.remove(entry);
                prefs.edit().putStringSet("rules", rulesSet).apply();
                
                refreshRulesList();
                Toast.makeText(this, "Rule deleted", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void transferOwnership(String targetPackage) {
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
    }
}
