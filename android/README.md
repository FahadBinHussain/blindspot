# BlindSpot - Android Device Admin & Protection Manager

**BlindSpot** is a powerful Android Device Owner application that provides comprehensive app management, protection mechanisms, and Xposed framework hooks for enhanced control over your device.

## ✨ Features

### 📱 Device Admin Management
- Full Device Owner capabilities
- Apply application restrictions programmatically
- Discover supported restriction keys for any app
- Import/Export device ownership between admin apps

### 🛡️ App Protection Manager
- **Block Apps**: Suspend apps (grayed out) + Protect data from clearing
- **Protect Apps**: Prevent uninstall + Protect data from clearing
- Pin important apps to top of list
- Search and filter installed apps
- Unhide all hidden applications
- **Delay Lock Mechanism**: Lock protection settings for customizable time periods (1m, 10m, 1h, 1d) to prevent unauthorized changes

### 📋 Application Restrictions
- View and manage app-level restriction keys
- Support for String, Integer, Boolean, and String Array types
- Full-screen array editor for complex restriction values
- External admin rules support (view and manage rules set by other admins)
- Admin source badges (BS = BlindSpot, EXT = External Admin)
- Rules grouped by app name and key for better organization
- Export rules to JSON with timestamp
- Import rules from JSON file
- Configurable export location

### 🔧 Settings
- Customizable export location for rules
- Persistent settings across app sessions

### 🎯 LSPosed/Xposed Hooks
BlindSpot includes three powerful Xposed modules for enhanced app behavior modification:

#### 📱 Morphe Content Watchdog (`app.morphe.android.youtube`)
- Monitors all on-screen text via TextView hooks
- Detects forbidden content automatically
- Immediately closes app when prohibited content is detected
- Provides parental control and content filtering
- **Enable/Disable**: Toggle via Hooks tab, requires force stop to take effect

#### 💬 Messenger Ghost-Touch Protection (`com.facebook.orca`)
- Blocks Meta AI search features
- Removes and disables search bars and Meta AI prompts
- Blocks focus requests to prevent keyboard appearance
- Whitelists stickers, emojis, and GIFs
- Removes unwanted UI elements
- **Enable/Disable**: Toggle via Hooks tab with ContentProvider state management

#### 📘 Facebook Continuous Scroll-Lock (`com.facebook.katana`)
- Blocks infinite scrolling in Facebook Feed
- Forces user to manually open posts (breaks doom-scroll cycle)
- Hooks Activity lifecycle and touch events
- Detects and blocks feed scrolling patterns
- **Enable/Disable**: Toggle via Hooks tab with ContentProvider state management

---

## 🔬 Technical Deep Dive: Cross-App State Management

### The Challenge
BlindSpot needs to communicate hook enable/disable states from the main app to three separate Xposed hooks running in different app processes (Morphe, Messenger, Facebook). Modern Android security features make cross-app communication extremely challenging.

### Failed Approaches & Lessons Learned

#### ❌ Attempt 1: XSharedPreferences (Traditional Xposed Method)
**Why it failed**: Android 7+ SELinux policies prevent apps from reading other apps' private files, even with world-readable permissions and `makeWorldReadable()`.

**Symptoms**:
```
Prefs loaded: false
All prefs keys: {}
File exists but canRead: false
```

#### ❌ Attempt 2: ContentProvider (Standard Android IPC)
**Why it failed**: Android 11+ package visibility restrictions prevent apps from seeing BlindSpot's package.

**Symptoms**:
```
Provider info: NULL
BlindSpot package NOT FOUND
```

**Resolution**: Works for Facebook & Messenger (both Facebook-owned apps), fails for Morphe (YouTube variant).

#### ❌ Attempt 3: External Cache Files
**Why it failed**: Scoped Storage (Android 10+) restricts cross-app file access, even to external cache directories.

**Symptoms**:
```
File exists: true, CanRead: false
```

#### ❌ Attempt 4: Public Download Directory
**Why it failed**: Security vulnerability - users could bypass restrictions by manually editing the file.

#### ❌ Attempt 5: Broadcast Receivers
**Why it failed**: Android 8+ restricts implicit broadcasts; explicit broadcasts require both apps to be running simultaneously.

**Symptoms**:
```
Broadcast sent successfully
Broadcast never received
```

#### ❌ Attempt 6: Direct File in BlindSpot Data Directory
**Why it failed**: SELinux prevents cross-app access even with `chmod 777` and world-readable permissions.

**Symptoms**:
```
chmod completed successfully
exists=false from target app
```

#### ❌ Attempt 7: Shell-Forced XSharedPreferences
**Why it failed**: Even with `Runtime.exec("chmod 644")`, SELinux still blocks read access.

**Symptoms**:
```
Set permissions via shell: success
File canRead: false
```

#### ❌ Attempt 8: Settings.System
**Why it failed**: Requires `WRITE_SETTINGS` permission which needs explicit user approval via Settings UI.

**Symptoms**:
```
SecurityException: WRITE_SETTINGS permission required
```

---

### ✅ Final Working Solutions

#### Solution 1: ContentProvider (Facebook & Messenger)
**Implementation**: `HooksPreferenceProvider` at authority `com.hardening.blindspot.hooks`

**Why it works**: Facebook and Messenger are both Facebook-owned apps and can see each other's packages.

**Code Flow**:
1. LSPosedFragment saves preference to SharedPreferences
2. HooksPreferenceProvider serves preferences via cursor query
3. Facebook/MessengerHook queries ContentProvider with key
4. Returns "true"/"false" string from cursor

**Key Code**:
```java
// Provider
Cursor cursor = new MatrixCursor(new String[]{"key", "value"});
cursor.addRow(new Object[]{key, prefs.getBoolean(key, true) ? "true" : "false"});

// Hook
Cursor cursor = context.getContentResolver().query(PREFS_URI, null, null, 
    new String[]{"hook_facebook_enabled"}, null);
String value = cursor.getString(cursor.getColumnIndex("value"));
return "true".equals(value);
```

#### Solution 2: Settings.Global + Root Shell (Morphe)
**Implementation**: Write to Android system settings database using `su` command

**Why it works**: 
- Settings.Global is globally accessible across all apps and processes
- Root shell bypasses all permission checks
- Persists across reboots and app restarts
- Cannot be bypassed by user

**Code Flow**:
1. LSPosedFragment writes to Settings.Global using `su -c settings put global`
2. MorpheHook reads from Settings.Global using standard ContentResolver API
3. No permissions needed for reading global settings

**Key Code**:
```java
// Writer (BlindSpot)
String command = "settings put global blindspot_morphe_hook_enabled " + (enabled ? "1" : "0");
Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});

// Reader (Morphe Hook)
String value = Settings.Global.getString(context.getContentResolver(), 
    "blindspot_morphe_hook_enabled");
return !"0".equals(value); // Default enabled if null
```

**Advantages**:
- ✅ Secure: Root-only write access
- ✅ Global: Accessible from any app/process
- ✅ Persistent: Survives reboots
- ✅ Fast: Direct database access
- ✅ Bypass-proof: User cannot modify without root

**Requirements**:
- Root access (already required for Xposed)
- `su` binary available in PATH

---

### Architecture Summary

```
BlindSpot Main App
    ├── LSPosedFragment (UI Controls)
    │   ├── Saves to SharedPreferences
    │   ├── Writes to Settings.Global (Morphe only)
    │   └── Notifies HooksPreferenceProvider
    │
    └── HooksPreferenceProvider
        └── Serves prefs to Facebook & Messenger
        
Xposed Hooks (Separate Processes)
    ├── FacebookHook
    │   └── Queries ContentProvider → "hook_facebook_enabled"
    │
    ├── MessengerHook
    │   └── Queries ContentProvider → "hook_messenger_enabled"
    │
    └── MorpheHook
        └── Reads Settings.Global → "blindspot_morphe_hook_enabled"
```

---

## 🛡️ Security Considerations

### Time Setting Protection
- Uses `DevicePolicyManager.setAutoTimeRequired()` to enforce automatic time/timezone
- Prevents users from bypassing delay locks by changing system time
- Requires Device Owner privileges

### Hook State Management
- **Facebook/Messenger**: ContentProvider ensures only BlindSpot can write states
- **Morphe**: Settings.Global with root prevents non-root user bypass
- Default state: Enabled (fail-safe approach)

---

## 📋 Requirements

#### 👤 Facebook Scroll-Lock & Feed Blocker (`com.facebook.katana`)
- Prevents feed browsing and doom-scrolling
- Blocks scrolling by detecting touch movement threshold
- Detects and blocks multi-touch gestures
- Automatically closes app when feed is accessed
- Allows only direct links (messenger, profile, groups)
- Monitors Activity lifecycle and touch events

## 📋 Requirements

### Device Owner Setup
BlindSpot requires Device Owner status to function properly. You can set this up via ADB:

```bash
adb shell dpm set-device-owner com.hardening.blindspot/.BlindSpotAdminReceiver
```

### LSPosed/Xposed Hooks
- LSPosed or EdXposed framework must be installed
- Enable BlindSpot module in LSPosed Manager
- Select target apps in module scope:
  - `app.morphe.android.youtube` (Morphe)
  - `com.facebook.orca` (Messenger)
  - `com.facebook.katana` (Facebook)
- Force stop target apps for hooks to take effect

## 🚀 Installation

1. Build the APK from source or download the release
2. Install the app on your device
3. Grant Device Owner permissions via ADB
4. (Optional) Install LSPosed and enable BlindSpot module for hooks

## 🏗️ Project Structure

```
blindspot/
├── app/src/main/java/com/hardening/blindspot/
│   ├── MainActivity.java              # Main activity with tabbed interface
│   ├── BlindSpotAdminReceiver.java    # Device Admin receiver
│   ├── AppProtectionActivity.java     # Separate protection activity (legacy)
│   ├── MorpheHook.java                # Morphe content watchdog hook
│   ├── MessengerHook.java             # Messenger ghost-touch protection hook
│   ├── FacebookHook.java              # Facebook scroll-lock & feed blocker hook
│   ├── RulesFragment.java             # Rules management tab
│   ├── ProtectionFragment.java        # App protection tab
│   ├── TransferFragment.java          # Transfer ownership tab
│   ├── SettingsFragment.java          # Settings tab
│   ├── LSPosedFragment.java           # Hooks information tab
│   └── TabsAdapter.java                # ViewPager2 adapter for tabs
├── app/src/main/res/layout/
│   ├── activity_main.xml              # Main tabbed layout
│   ├── tab_rules.xml                  # Rules tab layout
│   ├── tab_protection.xml             # Protection tab layout with delay lock
│   ├── tab_transfer.xml               # Transfer tab layout
│   ├── tab_settings.xml               # Settings tab layout
│   ├── tab_lsposed.xml                # Hooks info tab layout
│   └── app_protection_item.xml        # Protection list item layout
└── build.gradle                        # Project build configuration
```

## 📱 Screenshots

### Rules Tab
- Discover and apply app restriction keys
- View rules grouped by app and key
- Import/Export rules functionality
- External admin rules management

### Protection Tab
- Delay Lock mechanism with countdown timer
- Block/Protect app status management
- Pin apps for quick access
- Real-time status updates

### Transfer Tab
- Transfer device ownership to other admin apps
- Confirmation dialog with warning
- List of available device admin apps

### Settings Tab
- Configure default export location for rules
- Persistent settings storage

### Hooks Tab
- Information about all Xposed hooks
- Shows app icons for hooked packages
- Detailed descriptions of hook functionality

## 🔐 Security Features

- **Delay Lock**: Prevents unauthorized changes to protection settings
- **Protection Status Lock**: Blocks app status changes during lock period
- **Admin Source Tracking**: Identify which admin set each rule
- **External Rules Management**: View and modify rules from other admins

## 🛠️ Development

### Building from Source

1. Clone the repository:
```bash
git clone https://github.com/yourusername/blindspot.git
cd blindspot
```

2. Build with Gradle:
```bash
./gradlew assembleRelease
```

3. Install the APK:
```bash
adb install app/build/outputs/apk/release/app-release.apk
```

### Requirements
- Android SDK 28+
- Gradle 7.0+
- Device Owner capabilities for full functionality

## 📝 Usage

### Managing Application Restrictions

1. Select an app from the dropdown
2. Tap "Discover" to see supported restriction keys
3. Choose a restriction key
4. Set the value (String, Integer, Boolean, or Array)
5. Tap "Apply" to set the restriction

### Protecting Apps

1. Navigate to Protection tab
2. Set delay lock time if needed (optional)
3. Find the app in the list
4. Change status: Default → Blocked → Protected
5. Pin important apps for quick access

### Exporting/Importing Rules

1. Set export location in Settings tab
2. In Rules tab, tap "Export Rules"
3. JSON file saved with timestamp
4. Use "Import Rules" to load rules from file

### LSPosed Hooks

1. Install LSPosed framework
2. Enable BlindSpot module
3. Select target apps in scope
4. Force stop target apps
5. Hooks activate on next app launch

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## 📄 License

This project is licensed under the MIT License - see the LICENSE file for details.

## ⚠️ Disclaimer

BlindSpot is a powerful tool that requires Device Owner permissions. Use responsibly and at your own risk. The developers are not responsible for any damage or data loss resulting from the use of this application.

## 🐛 Known Issues

- Some apps may not support all restriction keys
- Xposed hooks require compatible framework (LSPosed/EdXposed)
- Device Owner status cannot be revoked without factory reset

## 📧 Support

For issues and feature requests, please use the GitHub Issues tracker.
