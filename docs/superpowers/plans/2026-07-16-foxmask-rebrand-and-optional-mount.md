# FoxMask Rebrand and Optional Mount Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Magisk's traditional systemless module mount an optional toggleable feature (default ON, preserves `su`), and rebrand the user-visible name and `applicationId` to FoxMask / `com.foxmask.app`.

**Architecture:** Part 1 adds a new `MountModules` `DbEntryKey` flowing through the same shared `settings` table contract used by `zygisk`. Rust loads it once into an `AtomicBool` at `post_fs_data` and gates the `handle_modules()` call. Kotlin writes via `Config.mountModules` (a `dbSettings` Boolean property). Settings UI adds a toggle in both apk (legacy DataBinding) and apk-ng (Compose). Part 2 changes `applicationId` to `com.foxmask.app` across gradle files, the native `APP_PACKAGE_NAME`/`JAVA_PACKAGE_NAME` constants, test package refs, and user-visible strings — keeping the internal Java/Kotlin namespace `com.topjohnwu.magisk` unchanged (R1 approach).

**Tech Stack:** Rust (native libmagisk-rs via cxx bridge), Kotlin (Android app, MVVM with DataBinding in apk and Jetpack Compose in apk-ng), Gradle Kotlin DSL, SQLite (magisk.db `settings` table).

**Workspace Rules (from AGENTS.md):**
- All shell commands MUST be prefixed with `scripts/env.py`
- App builds use `./gradlew` from `app/` directory
- Native builds use `./gradlew` from `app/` directory (native is built via `:app:[module]:build` Rust cargo tasks invoked by gradle)
- Prefer Kotlin for new app code

---

## File Structure

### Part 1 — Optional Systemless Mount

| File | Responsibility |
|------|----------------|
| `native/src/core/lib.rs` (lines 82-90, 217-229) | cxx bridge: add `MountModules` to `DbEntryKey` enum; expose `mount_modules_enabled()` accessor to C++ |
| `native/src/core/db.rs` (lines 93-106, 248-258) | `DbEntryKey::to_str` arm `"mount_modules"`; default `1` in `get_db_setting` |
| `native/src/core/daemon.rs` (lines 56-69, ~76-90) | Add `mount_modules_enabled: AtomicBool` field to `MagiskD`; add accessor method |
| `native/src/core/bootstages.rs` (lines 109-161) | Load AtomicBool in `post_fs_data`, gate `handle_modules()` call |
| `app/core/src/main/java/com/topjohnwu/magisk/core/Config.kt` (lines 19-52, 137) | Add `Key.MOUNT_MODULES` const; add `mountModules` dbSettings property |
| `app/core/src/main/res/values/strings.xml` | New strings: `settings_mount_modules_title`, `settings_mount_modules_summary`, `settings_mount_modules_warning` |
| `app/apk/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsItems.kt` (after line 219) | New `SystemlessMount` Toggle object (mirror Zygisk pattern) |
| `app/apk/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsViewModel.kt` (lines 60-69, 104-117) | Add `SystemlessMount` to Magisk section; add `onItemAction` arm |
| `app/apk-ng/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsViewModel.kt` (lines 25, 58-60) | Add `mountModulesMismatch` property; add `notifyMountModulesChange()` |
| `app/apk-ng/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsScreen.kt` (lines 246-291) | Add `SettingsSwitch` for mount modules in `MagiskSection` |

### Part 2 — FoxMask Rebrand

| File | Responsibility |
|------|----------------|
| `native/src/include/consts.rs:14` | `APP_PACKAGE_NAME` → `com.foxmask.app` |
| `native/src/include/consts.hpp:3` | `JAVA_PACKAGE_NAME` → `com.foxmask.app` |
| `app/core/build.gradle.kts:23` | `APP_PACKAGE_NAME` BuildConfig field → `com.foxmask.app` |
| `app/build-logic/src/main/java/Setup.kt:280` | Main app `applicationId` → `com.foxmask.app` |
| `app/stub/build.gradle.kts:20` | Stub `applicationId` → `com.foxmask.app` |
| `app/test/build.gradle.kts:9` | Test `applicationId` → `com.foxmask.app.test` |
| `app/test/src/main/AndroidManifest.xml:17,21` | Instrumentation `targetPackage` refs |
| `app/test/src/main/java/com/topjohnwu/magisk/test/AppMigrationTest.kt:23` | `APP_PKG` constant |
| `app/test/src/main/java/com/topjohnwu/magisk/test/Runners.kt:13` | Hardcoded test package prefix |
| `scripts/test_common.sh:56,71` | Test package refs in shell scripts |
| `app/core/src/main/res/values/resources.xml:5` | `<string name="magisk">` value → FoxMask |
| `app/core/src/main/res/values/strings.xml` | ~23 user-facing "Magisk" string replacements |
| `app/stub-res/src/main/res/values/strings.xml:3-4` | 2 "Magisk" occurrences in stub strings |
| `app/shared/src/main/AndroidManifest.xml:29` | `android:label="Magisk"` → FoxMask |

---

## Part 1: Optional Systemless Mount

### Task 1: Add `MountModules` DbEntryKey variant and DB plumbing

**Files:**
- Modify: `native/src/core/lib.rs:82-90`
- Modify: `native/src/core/db.rs:93-106` and `native/src/core/db.rs:248-258`

- [ ] **Step 1: Add `MountModules` to the cxx `DbEntryKey` enum**

Edit `native/src/core/lib.rs` — add `MountModules,` arm to the enum so the FFI bridge knows about it. Insert after `ZygiskConfig,`:

```rust
    enum DbEntryKey {
        RootAccess,
        SuMultiuserMode,
        SuMntNs,
        DenylistConfig,
        ZygiskConfig,
        MountModules,
        BootloopCount,
        SuManager,
    }
```

- [ ] **Step 2: Add the `to_str` arm for the new key**

Edit `native/src/core/db.rs` — add a `MountModules => "mount_modules",` arm to `DbEntryKey::to_str`. Insert after `DbEntryKey::ZygiskConfig => "zygisk",`:

```rust
impl DbEntryKey {
    fn to_str(self) -> &'static str {
        match self {
            DbEntryKey::RootAccess => "root_access",
            DbEntryKey::SuMultiuserMode => "multiuser_mode",
            DbEntryKey::SuMntNs => "mnt_ns",
            DbEntryKey::DenylistConfig => "denylist",
            DbEntryKey::ZygiskConfig => "zygisk",
            DbEntryKey::MountModules => "mount_modules",
            DbEntryKey::BootloopCount => "bootloop",
            DbEntryKey::SuManager => "requester",
            _ => "",
        }
    }
}
```

- [ ] **Step 3: Add default value `1` (ON) for `MountModules` in `get_db_setting`**

Edit `native/src/core/db.rs` — in the `get_db_setting` method's `match key` block, add `DbEntryKey::MountModules => 1,` after `DbEntryKey::ZygiskConfig => self.is_emulator as i32,`:

```rust
    pub fn get_db_setting(&self, key: DbEntryKey) -> i32 {
        // Get default values
        let mut val = match key {
            DbEntryKey::RootAccess => RootAccess::default() as i32,
            DbEntryKey::SuMultiuserMode => MultiuserMode::default() as i32,
            DbEntryKey::SuMntNs => MntNsMode::default().repr,
            DbEntryKey::DenylistConfig => 0,
            DbEntryKey::ZygiskConfig => self.is_emulator as i32,
            DbEntryKey::MountModules => 1,
            DbEntryKey::BootloopCount => 0,
            _ => -1,
        };
        // ... rest unchanged
```

- [ ] **Step 4: Verify the native Rust code compiles**

Run from `/workspace`:

```bash
scripts/env.py bash -c 'cd app && ./gradlew :app:shared:assembleRelease 2>&1 | tail -30'
```

Expected: BUILD SUCCESSFUL (this is the lightest gradle task that exercises the cxx bridge compile). If a quicker cargo check is available, prefer:

```bash
scripts/env.py bash -c 'cd native && cargo check -p magisk 2>&1 | tail -30'
```

Expected: `cargo check` finishes with no error mentioning `MountModules` or `DbEntryKey`.

- [ ] **Step 5: Commit**

```bash
git add native/src/core/lib.rs native/src/core/db.rs
git commit -m "feat(native): add MountModules DbEntryKey with default ON"
```

---

### Task 2: Add `mount_modules_enabled` AtomicBool field to `MagiskD`

**Files:**
- Modify: `native/src/core/daemon.rs:56-69` (struct) and add accessor
- Modify: `native/src/core/lib.rs:217-229` (FFI exposure)

- [ ] **Step 1: Add the AtomicBool field to `MagiskD`**

Edit `native/src/core/daemon.rs` — add `pub mount_modules_enabled: AtomicBool,` after `pub zygisk_enabled: AtomicBool,`:

```rust
#[derive(Default)]
pub struct MagiskD {
    pub sql_connection: Mutex<Option<Sqlite3>>,
    pub manager_info: Mutex<ManagerInfo>,
    pub boot_stage_lock: Mutex<BootState>,
    pub module_list: OnceLock<Vec<ModuleInfo>>,
    pub zygisk_enabled: AtomicBool,
    pub mount_modules_enabled: AtomicBool,
    pub zygisk: Mutex<ZygiskState>,
    pub cached_su_info: AtomicArc<SuInfo>,
    pub sdk_int: i32,
    pub is_emulator: bool,
    is_recovery: bool,
    exe_attr: FileAttr,
}
```

- [ ] **Step 2: Add the `mount_modules_enabled()` accessor method**

In `native/src/core/daemon.rs`, inside `impl MagiskD { ... }`, right after the existing `zygisk_enabled` accessor (search for `pub fn zygisk_enabled`), add a parallel method. First read the surrounding code to find the existing accessor pattern, then add:

```rust
    pub fn zygisk_enabled(&self) -> bool {
        self.zygisk_enabled.load(Ordering::Relaxed)
    }

    pub fn mount_modules_enabled(&self) -> bool {
        self.mount_modules_enabled.load(Ordering::Relaxed)
    }
```

If the existing `zygisk_enabled` accessor does not exist in this file (it may be defined elsewhere), find it via `Grep` for `fn zygisk_enabled` and add the parallel method in the same location.

- [ ] **Step 3: Expose the accessor through the cxx FFI bridge**

Edit `native/src/core/lib.rs` — in the `extern "Rust"` block for `MagiskD` (around line 217-229), add `fn mount_modules_enabled(&self) -> bool;` after `fn zygisk_enabled(&self) -> bool;`:

```rust
    // FFI for MagiskD
    extern "Rust" {
        type MagiskD;
        fn sdk_int(&self) -> i32;
        fn zygisk_enabled(&self) -> bool;
        fn mount_modules_enabled(&self) -> bool;
        fn get_db_setting(&self, key: DbEntryKey) -> i32;
        #[cxx_name = "set_db_setting"]
        fn set_db_setting_for_cxx(&self, key: DbEntryKey, value: i32) -> bool;

        #[Self = MagiskD]
        #[cxx_name = "Get"]
        fn get() -> &'static MagiskD;
    }
```

- [ ] **Step 4: Verify compilation**

Run:

```bash
scripts/env.py bash -c 'cd native && cargo check -p magisk 2>&1 | tail -30'
```

Expected: no errors. If cargo task unavailable, fall back to `scripts/env.py bash -c 'cd app && ./gradlew :app:shared:assembleRelease 2>&1 | tail -40'` and expect BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add native/src/core/daemon.rs native/src/core/lib.rs
git commit -m "feat(native): add mount_modules_enabled AtomicBool on MagiskD"
```

---

### Task 3: Gate `handle_modules()` in `post_fs_data`

**Files:**
- Modify: `native/src/core/bootstages.rs:151-158`

- [ ] **Step 1: Load the AtomicBool and gate the `handle_modules()` call**

Edit `native/src/core/bootstages.rs` — in `post_fs_data`, after the existing `zygisk_enabled.store(...)` block (around line 152-155) and before `self.handle_modules();`, add a parallel load for `mount_modules_enabled`. Then wrap the `self.handle_modules()` call in `if self.mount_modules_enabled.load(Ordering::Relaxed) { ... }`. The block from line 151 to 158 becomes:

```rust
        exec_common_scripts(cstr!("post-fs-data"));
        self.zygisk_enabled.store(
            self.get_db_setting(DbEntryKey::ZygiskConfig) != 0,
            Ordering::Release,
        );
        self.mount_modules_enabled.store(
            self.get_db_setting(DbEntryKey::MountModules) != 0,
            Ordering::Release,
        );
        initialize_denylist();
        if self.mount_modules_enabled.load(Ordering::Relaxed) {
            self.handle_modules();
        } else {
            info!("* Mount modules disabled, skipping module mount pipeline");
        }
        clean_mounts();
```

Notes:
- `Ordering` and `info!` are already imported in this file (used by the existing zygisk load and `info!("** post-fs-data mode running")`).
- `clean_mounts()` stays outside the gate so the mounts cleanup machinery still runs regardless — this matches the safe-mode behavior pattern.
- The gate is placed AFTER `initialize_denylist()` because denylist is independent of module mounts and must always run for `magisk --denylist` to work.

- [ ] **Step 2: Verify compilation**

Run:

```bash
scripts/env.py bash -c 'cd native && cargo check -p magisk 2>&1 | tail -30'
```

Expected: no errors.

- [ ] **Step 3: Commit**

```bash
git add native/src/core/bootstages.rs
git commit -m "feat(native): gate handle_modules behind mount_modules_enabled toggle"
```

---

### Task 4: Add `mountModules` Config property (Kotlin)

**Files:**
- Modify: `app/core/src/main/java/com/topjohnwu/magisk/core/Config.kt:19-52` (Key object) and `app/core/src/main/java/com/topjohnwu/magisk/core/Config.kt:137` (property)

- [ ] **Step 1: Add `MOUNT_MODULES` to the `Key` object**

Edit `app/core/src/main/java/com/topjohnwu/magisk/core/Config.kt` — add `const val MOUNT_MODULES = "mount_modules"` after `const val ZYGISK = "zygisk"` (line 25) inside `object Key`:

```kotlin
    object Key {
        // db configs
        const val ROOT_ACCESS = "root_access"
        const val SU_MULTIUSER_MODE = "multiuser_mode"
        const val SU_MNT_NS = "mnt_ns"
        const val SU_BIOMETRIC = "su_biometric"
        const val ZYGISK = "zygisk"
        const val MOUNT_MODULES = "mount_modules"
        const val BOOTLOOP = "bootloop"
        const val SU_MANAGER = "requester"
        const val KEYSTORE = "keystore"
```

- [ ] **Step 2: Add the `mountModules` property**

Edit `app/core/src/main/java/com/topjohnwu/magisk/core/Config.kt` — add `var mountModules by dbSettings(Key.MOUNT_MODULES, true)` on the line after `var zygisk by dbSettings(Key.ZYGISK, Info.isEmulator)` (line 137):

```kotlin
    var zygisk by dbSettings(Key.ZYGISK, Info.isEmulator)
    var mountModules by dbSettings(Key.MOUNT_MODULES, true)
    var suManager by dbStrings(Key.SU_MANAGER, "", true)
```

Notes:
- `dbSettings(name, default: Boolean)` already exists in `DBConfig.kt` and returns a `BoolDBProperty` that writes `1`/`0` to the `settings` table — exactly matching the Rust side's `!= 0` check.
- Default `true` matches the Rust default of `1` (ON).

- [ ] **Step 3: Verify the app compiles**

Run from `/workspace`:

```bash
scripts/env.py bash -c 'cd app && ./gradlew :app:core:assembleRelease 2>&1 | tail -30'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/core/src/main/java/com/topjohnwu/magisk/core/Config.kt
git commit -m "feat(app): add Config.mountModules dbSettings property"
```

---

### Task 5: Add new English strings for the mount toggle UI

**Files:**
- Modify: `app/core/src/main/res/values/strings.xml`

- [ ] **Step 1: Add three new strings**

Edit `app/core/src/main/res/values/strings.xml` — add the following three strings immediately after the `settings_zygisk_summary` line (search for `settings_zygisk_summary` and insert after it):

```xml
    <string name="settings_zygisk_summary">Run parts of Magisk in the Zygote daemon</string>
    <string name="settings_mount_modules_title">Systemless Modules</string>
    <string name="settings_mount_modules_summary">Mount systemless modules over system partitions on boot</string>
    <string name="settings_mount_modules_warning">Disabling this does NOT disable root (su). All modules will be inactive until re-enabled. Requires reboot to take effect.</string>
```

Notes:
- These strings will be referenced as `CoreR.string.settings_mount_modules_title`, `CoreR.string.settings_mount_modules_summary`, `CoreR.string.settings_mount_modules_warning`.
- The "Magisk" in `settings_zygisk_summary` will be replaced in Part 2 Task 13. Do not touch it now to keep this task focused.

- [ ] **Step 2: Verify the resources compile**

Run:

```bash
scripts/env.py bash -c 'cd app && ./gradlew :app:core:assembleRelease 2>&1 | tail -30'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/core/src/main/res/values/strings.xml
git commit -m "feat(app): add systemless modules toggle strings"
```

---

### Task 6: Add `SystemlessMount` toggle in apk (legacy DataBinding UI)

**Files:**
- Modify: `app/apk/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsItems.kt` (add new object after `Zygisk` object, around line 219)
- Modify: `app/apk/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsViewModel.kt:60-69` (items list) and `app/apk/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsViewModel.kt:104-117` (onItemAction)

- [ ] **Step 1: Add the `SystemlessMount` Toggle object**

Edit `app/apk/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsItems.kt` — insert a new object immediately after the `Zygisk` object's closing brace (after line 219):

```kotlin
object SystemlessMount : BaseSettingsItem.Toggle() {
    override val title = CoreR.string.settings_mount_modules_title.asText()
    override val description get() =
        if (mismatch) CoreR.string.reboot_apply_change.asText()
        else CoreR.string.settings_mount_modules_summary.asText()
    override var value
        get() = Config.mountModules
        set(value) {
            Config.mountModules = value
            notifyPropertyChanged(BR.description)
        }
    val mismatch get() = value != Info.isMountModulesEnabled
}
```

Notes:
- This mirrors the existing `Zygisk` object exactly. `Info.isMountModulesEnabled` will be added in Step 2 below.
- `BR.description`, `CoreR`, `Config`, `Info` are already imported in this file (used by `Zygisk`).

- [ ] **Step 2: Add `isMountModulesEnabled` flag to `Info`**

`Info.isZygiskEnabled` is defined at `app/core/src/main/java/com/topjohnwu/magisk/core/Info.kt:52` as:

```kotlin
    @JvmField val isZygiskEnabled = System.getenv("ZYGISK_ENABLED") == "1"
```

It reads the `ZYGISK_ENABLED` env var which is set by the native daemon in `set_script_env()` at `native/src/core/scripting.cpp:28-29`:

```cpp
    if (MagiskD::Get().zygisk_enabled())
        setenv("ZYGISK_ENABLED", "1", 1);
```

Mirror this exact pattern. Edit `app/core/src/main/java/com/topjohnwu/magisk/core/Info.kt` — add `isMountModulesEnabled` immediately after `isZygiskEnabled` (line 52):

```kotlin
    @JvmField val isZygiskEnabled = System.getenv("ZYGISK_ENABLED") == "1"
    @JvmField val isMountModulesEnabled = System.getenv("MOUNT_MODULES_ENABLED") == "1"
```

- [ ] **Step 3: Set `MOUNT_MODULES_ENABLED` env var in native `set_script_env()`**

Edit `native/src/core/scripting.cpp` — in `set_script_env()` (around lines 23-30), add a parallel `setenv` call conditioned on `MagiskD::Get().mount_modules_enabled()` immediately after the existing `ZYGISK_ENABLED` block:

```cpp
static void set_script_env() {
    setenv("ASH_STANDALONE", "1", 1);
    char new_path[4096];
    ssprintf(new_path, sizeof(new_path), "%s:%s", getenv("PATH"), get_magisk_tmp());
    setenv("PATH", new_path, 1);
    if (MagiskD::Get().zygisk_enabled())
        setenv("ZYGISK_ENABLED", "1", 1);
    if (MagiskD::Get().mount_modules_enabled())
        setenv("MOUNT_MODULES_ENABLED", "1", 1);
};
```

Notes:
- `MagiskD::Get().mount_modules_enabled()` was exposed through the cxx FFI bridge in Part 1 Task 2 Step 3 — C++ can call it directly.
- This env var propagates to the app process via the same path as `ZYGISK_ENABLED`. The "mismatch" warning shown by `Info.isMountModulesEnabled != Config.mountModules` prompts the user to reboot when they change the toggle, mirroring Zygisk's UX exactly.
- No native CLI handler in `magisk.rs` is needed — the env-var approach is sufficient for the UI mismatch pattern.

- [ ] **Step 4: Add `SystemlessMount` to the items list in `SettingsViewModel.kt`**

Edit `app/apk/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsViewModel.kt` — in `createItems()`, the Magisk section block (lines 60-69) currently reads:

```kotlin
        // Magisk
        if (Info.envIsActive) {
            list.addAll(listOf(
                Magisk,
                SystemlessHosts
            ))
            if (Const.Version.atLeast_24_0()) {
                list.addAll(listOf(Zygisk, DenyList, DenyListConfig))
            }
        }
```

Insert `SystemlessMount` after `SystemlessHosts` so it always appears when env is active (mount toggle must be available even on older versions since the gate is generic):

```kotlin
        // Magisk
        if (Info.envIsActive) {
            list.addAll(listOf(
                Magisk,
                SystemlessHosts,
                SystemlessMount
            ))
            if (Const.Version.atLeast_24_0()) {
                list.addAll(listOf(Zygisk, DenyList, DenyListConfig))
            }
        }
```

- [ ] **Step 5: Add the `onItemAction` arm**

Edit `app/apk/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsViewModel.kt` — in `onItemAction` (lines 104-117), add a `SystemlessMount ->` arm mirroring the existing `Zygisk ->` arm. Insert before the `else -> Unit` line:

```kotlin
    override fun onItemAction(view: View, item: BaseSettingsItem) {
        when (item) {
            Theme -> SettingsFragmentDirections.actionSettingsFragmentToThemeFragment().navigate()
            LanguageSystem -> view.activity.startActivity(LocaleSetting.localeSettingsIntent)
            AddShortcut -> AddHomeIconEvent().publish()
            SystemlessHosts -> createHosts()
            DenyListConfig -> SettingsFragmentDirections.actionSettingsFragmentToDenyFragment().navigate()
            UpdateChannel -> openUrlIfNecessary(view)
            is Hide -> viewModelScope.launch { AppMigration.hide(view.activity, item.value) }
            Restore -> viewModelScope.launch { AppMigration.restore(view.activity) }
            Zygisk -> if (Zygisk.mismatch) SnackbarEvent(R.string.reboot_apply_change).publish()
            SystemlessMount -> if (SystemlessMount.mismatch) SnackbarEvent(R.string.reboot_apply_change).publish()
            else -> Unit
        }
    }
```

- [ ] **Step 6: Verify the apk compiles**

Run:

```bash
scripts/env.py bash -c 'cd app && ./gradlew :app:apk:assembleRelease 2>&1 | tail -40'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 7: Commit**

```bash
git add app/apk/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsItems.kt \
        app/apk/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsViewModel.kt \
        app/core/src/main/java/com/topjohnwu/magisk/core/Info.kt \
        native/src/core/scripting.cpp
git commit -m "feat(apk): add SystemlessMount toggle to settings"
```

---

### Task 7: Add mount modules toggle in apk-ng (Jetpack Compose UI)

**Files:**
- Modify: `app/apk-ng/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsViewModel.kt:25,58-60`
- Modify: `app/apk-ng/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsScreen.kt:246-291`

- [ ] **Step 1: Add `mountModulesMismatch` property and `notifyMountModulesChange()` to apk-ng ViewModel**

Edit `app/apk-ng/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsViewModel.kt` — add `val mountModulesMismatch get() = Config.mountModules != Info.isMountModulesEnabled` after `val zygiskMismatch` (line 25), and add `fun notifyMountModulesChange()` after `fun notifyZygiskChange()` (line 58-60):

```kotlin
    val zygiskMismatch get() = Config.zygisk != Info.isZygiskEnabled
    val mountModulesMismatch get() = Config.mountModules != Info.isMountModulesEnabled
```

```kotlin
    fun notifyZygiskChange() {
        if (zygiskMismatch) showSnackbar(R.string.reboot_apply_change)
    }

    fun notifyMountModulesChange() {
        if (mountModulesMismatch) showSnackbar(R.string.reboot_apply_change)
    }
```

Notes:
- `Info.isMountModulesEnabled` was added in Part 1 Task 6 Step 2 (shared `Info.kt` in `app/core`).
- `showSnackbar`, `R`, `Config`, `Info` are already imported in this file.

- [ ] **Step 2: Add `SettingsSwitch` for mount modules in `MagiskSection`**

Edit `app/apk-ng/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsScreen.kt` — in `MagiskSection` (lines 246-291), add a mount modules switch right after the Systemless Hosts `SettingsArrow` and before the `if (Const.Version.atLeast_24_0())` Zygisk block (so it shows for all versions when env is active):

```kotlin
@Composable
private fun MagiskSection(viewModel: SettingsViewModel) {
    SmallTitle(text = stringResource(CoreR.string.magisk))
    Card(modifier = Modifier.fillMaxWidth()) {
        // Systemless Hosts
        SettingsArrow(
            title = stringResource(CoreR.string.settings_hosts_title),
            summary = stringResource(CoreR.string.settings_hosts_summary),
            onClick = { viewModel.createHosts() }
        )

        // Systemless Modules Toggle
        var mountModules by remember { mutableStateOf(Config.mountModules) }
        SettingsSwitch(
            title = stringResource(CoreR.string.settings_mount_modules_title),
            summary = stringResource(
                if (mountModules != Info.isMountModulesEnabled) CoreR.string.reboot_apply_change
                else CoreR.string.settings_mount_modules_summary
            ),
            checked = mountModules,
            onCheckedChange = {
                mountModules = it
                Config.mountModules = it
                viewModel.notifyMountModulesChange()
            }
        )

        if (Const.Version.atLeast_24_0()) {
            // Zygisk
            var zygisk by remember { mutableStateOf(Config.zygisk) }
            SettingsSwitch(
                title = stringResource(CoreR.string.zygisk),
                summary = stringResource(
                    if (zygisk != Info.isZygiskEnabled) CoreR.string.reboot_apply_change
                    else CoreR.string.settings_zygisk_summary
                ),
                checked = zygisk,
                onCheckedChange = {
                    zygisk = it
                    Config.zygisk = it
                    viewModel.notifyZygiskChange()
                }
            )

            // DenyList
            val denyListEnabled by viewModel.denyListEnabled.collectAsState()
            SettingsSwitch(
                title = stringResource(CoreR.string.settings_denylist_title),
                summary = stringResource(CoreR.string.settings_denylist_summary),
                checked = denyListEnabled,
                onCheckedChange = { viewModel.toggleDenyList(it) }
            )

            // DenyList Config
            SettingsArrow(
                title = stringResource(CoreR.string.settings_denylist_config_title),
                summary = stringResource(CoreR.string.settings_denylist_config_summary),
                onClick = { viewModel.navigateToDenyList() }
            )
        }
    }
}
```

- [ ] **Step 3: Verify apk-ng compiles**

Run:

```bash
scripts/env.py bash -c 'cd app && ./gradlew :app:apk-ng:assembleRelease 2>&1 | tail -40'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/apk-ng/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsViewModel.kt \
        app/apk-ng/src/main/java/com/topjohnwu/magisk/ui/settings/SettingsScreen.kt
git commit -m "feat(apk-ng): add systemless modules toggle in MagiskSection"
```

---

### Task 8: Build and verify Part 1 end-to-end

**Files:** none (verification only)

- [ ] **Step 1: Full app build**

Run from `/workspace`:

```bash
scripts/env.py bash -c 'cd app && ./gradlew assembleRelease 2>&1 | tail -50'
```

Expected: BUILD SUCCESSFUL. All variants (`apk`, `apk-ng`, `stub`, `core`, `shared`, `test`) compile.

- [ ] **Step 2: Sanity-check the new DB key round-trip**

This is a manual logic check (no automated test exists for the boot pipeline in this repo). Confirm by reading the code:

1. Rust `DbEntryKey::MountModules.to_str()` returns `"mount_modules"` — matches Kotlin `Key.MOUNT_MODULES = "mount_modules"`.
2. Rust default is `1` (ON) — matches Kotlin default `true`.
3. `BoolDBProperty` writes `1`/`0` — matches Rust `!= 0` check.
4. Gate sits AFTER `initialize_denylist()` and BEFORE `clean_mounts()` — denylist and cleanup still run when modules disabled, preserving `magisk --denylist` and mount hygiene.
5. `su`, daemon, policy injection, and binary tmpfs are NOT touched by the gate — `su` is preserved.

- [ ] **Step 3: Commit any leftover fixes (if any)**

If the build surfaced fixes, commit them with `fix(part1): ...`. Otherwise skip.

---

## Part 2: FoxMask Rebrand

### Task 9: Update native package-name constants (Rust + C++)

**Files:**
- Modify: `native/src/include/consts.rs:14`
- Modify: `native/src/include/consts.hpp:3`

- [ ] **Step 1: Update Rust `APP_PACKAGE_NAME`**

Edit `native/src/include/consts.rs` line 14:

```rust
pub const APP_PACKAGE_NAME: &str = "com.foxmask.app";
```

- [ ] **Step 2: Update C++ `JAVA_PACKAGE_NAME`**

Edit `native/src/include/consts.hpp` line 3:

```cpp
#define JAVA_PACKAGE_NAME "com.foxmask.app"
```

- [ ] **Step 3: Verify native compiles**

Run:

```bash
scripts/env.py bash -c 'cd native && cargo check -p magisk 2>&1 | tail -20'
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add native/src/include/consts.rs native/src/include/consts.hpp
git commit -m "refactor(native): rename APP_PACKAGE_NAME/JAVA_PACKAGE_NAME to com.foxmask.app"
```

---

### Task 10: Update Kotlin `APP_PACKAGE_NAME` BuildConfig field

**Files:**
- Modify: `app/core/build.gradle.kts:23`

- [ ] **Step 1: Update the BuildConfig field value**

Edit `app/core/build.gradle.kts` — line 23 currently reads `buildConfigField("String", "APP_PACKAGE_NAME", "\"com.topjohnwu.magisk\"")`. Change the value only (keep the field name and type):

```kotlin
        buildConfigField("String", "APP_PACKAGE_NAME", "\"com.foxmask.app\"")
```

Notes:
- Do NOT change `namespace = "com.topjohnwu.magisk.core"` on line 20 — internal Kotlin namespace stays per R1 approach.

- [ ] **Step 2: Verify core module compiles**

Run:

```bash
scripts/env.py bash -c 'cd app && ./gradlew :app:core:assembleRelease 2>&1 | tail -30'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/core/build.gradle.kts
git commit -m "refactor(app): set APP_PACKAGE_NAME BuildConfig to com.foxmask.app"
```

---

### Task 11: Update `applicationId` in gradle files

**Files:**
- Modify: `app/build-logic/src/main/java/Setup.kt:280`
- Modify: `app/stub/build.gradle.kts:20`
- Modify: `app/test/build.gradle.kts:9`

- [ ] **Step 1: Update main app `applicationId` in Setup.kt**

Edit `app/build-logic/src/main/java/Setup.kt` — line 280 currently reads `applicationId = "com.topjohnwu.magisk"`. Change to:

```kotlin
            applicationId = "com.foxmask.app"
```

Notes:
- Do NOT change `namespace = "com.topjohnwu.magisk"` on line 277.

- [ ] **Step 2: Update stub `applicationId`**

Edit `app/stub/build.gradle.kts` — line 20 currently reads `applicationId = "com.topjohnwu.magisk"`. Change to:

```kotlin
        applicationId = "com.foxmask.app"
```

Notes:
- Do NOT change `namespace = "com.topjohnwu.magisk"` on line 13.
- Leave the `https://github.com/topjohnwu/Magisk/releases/download/` URL on line 16 unchanged (out of scope — flagged as follow-up in spec).

- [ ] **Step 3: Update test `applicationId`**

Edit `app/test/build.gradle.kts` — line 9 currently reads `applicationId = "com.topjohnwu.magisk.test"`. Change to:

```kotlin
        applicationId = "com.foxmask.app.test"
```

Notes:
- Do NOT change `namespace = "com.topjohnwu.magisk.test"` on line 6.

- [ ] **Step 4: Verify build**

Run:

```bash
scripts/env.py bash -c 'cd app && ./gradlew assembleRelease 2>&1 | tail -40'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/build-logic/src/main/java/Setup.kt \
        app/stub/build.gradle.kts \
        app/test/build.gradle.kts
git commit -m "refactor(app): change applicationId to com.foxmask.app across gradle"
```

---

### Task 12: Update test package references

**Files:**
- Modify: `app/test/src/main/AndroidManifest.xml:17,21`
- Modify: `app/test/src/main/java/com/topjohnwu/magisk/test/AppMigrationTest.kt:23`
- Modify: `app/test/src/main/java/com/topjohnwu/magisk/test/Runners.kt:13`
- Modify: `scripts/test_common.sh:56,71`

- [ ] **Step 1: Update `AndroidManifest.xml` instrumentation `targetPackage`**

Edit `app/test/src/main/AndroidManifest.xml` — both instrumentation entries need `targetPackage` updates:

```xml
    <instrumentation
        android:name="com.topjohnwu.magisk.test.AppTestRunner"
        android:targetPackage="com.foxmask.app" />

    <instrumentation
        android:name="com.topjohnwu.magisk.test.TestRunner"
        android:targetPackage="com.foxmask.app.test" />
```

Notes:
- The `android:name` attributes reference the Kotlin test runner classes — those live under the unchanged `com.topjohnwu.magisk.test` namespace, so they stay as-is.

- [ ] **Step 2: Update `AppMigrationTest.kt` APP_PKG constant**

Edit `app/test/src/main/java/com/topjohnwu/magisk/test/AppMigrationTest.kt` — line 23 currently reads `private const val APP_PKG = "com.topjohnwu.magisk"`. Change to:

```kotlin
        private const val APP_PKG = "com.foxmask.app"
        private const val STUB_PKG = "repackaged.$APP_PKG"
```

Notes:
- `STUB_PKG` is derived from `APP_PKG` so it auto-tracks.

- [ ] **Step 3: Update `Runners.kt` hardcoded package prefix**

Edit `app/test/src/main/java/com/topjohnwu/magisk/test/Runners.kt` — line 13 currently reads `"com.topjohnwu.magisk.test$clz"`. Change to:

```kotlin
                if (clz.startsWith(".")) {
                    "com.foxmask.app.test$clz"
                } else {
                    clz
                }
```

- [ ] **Step 4: Update `scripts/test_common.sh` test package refs**

Edit `scripts/test_common.sh` — line 56 currently reads `local app='com.topjohnwu.magisk.test/com.topjohnwu.magisk.test.AppTestRunner'`. Change the application id portion (keep the runner class FQN which references the unchanged namespace):

```bash
  local app='com.foxmask.app.test/com.topjohnwu.magisk.test.AppTestRunner'
```

Line 71 currently reads `local pkg='com.topjohnwu.magisk.test'`. Change to:

```bash
  local pkg='com.foxmask.app.test'
```

Line 74 reads `local stub="repackaged.$pkg/$pkg.TestRunner"` — this is derived from `pkg`, so it auto-tracks. Leave it as-is. But check: the original `$pkg.TestRunner` would expand to `com.foxmask.app.test.TestRunner`, which is WRONG — the runner class lives at `com.topjohnwu.magisk.test.TestRunner`. Re-read line 74 carefully and decide:

If the original pattern (before our change) relied on `pkg.TestRunner` where `pkg == com.topjohnwu.magisk.test` AND the runner class FQN was `com.topjohnwu.magisk.test.TestRunner`, then `pkg.TestRunner` worked only because the package id matched the namespace. After our change they diverge. Update line 72-74 to use explicit runner class FQNs:

```bash
run_tests() {
  local pkg='com.foxmask.app.test'
  local self="$pkg/com.topjohnwu.magisk.test.TestRunner"
  local app="$pkg/com.topjohnwu.magisk.test.AppTestRunner"
  local stub="repackaged.$pkg/com.topjohnwu.magisk.test.AppTestRunner"
```

- [ ] **Step 5: Verify the test module still builds**

Run:

```bash
scripts/env.py bash -c 'cd app && ./gradlew :app:test:assembleRelease 2>&1 | tail -30'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/test/src/main/AndroidManifest.xml \
        app/test/src/main/java/com/topjohnwu/magisk/test/AppMigrationTest.kt \
        app/test/src/main/java/com/topjohnwu/magisk/test/Runners.kt \
        scripts/test_common.sh
git commit -m "refactor(test): update package refs to com.foxmask.app"
```

---

### Task 13: Update user-visible "Magisk" strings to "FoxMask"

**Files:**
- Modify: `app/core/src/main/res/values/resources.xml:5`
- Modify: `app/core/src/main/res/values/strings.xml` (~23 lines)
- Modify: `app/stub-res/src/main/res/values/strings.xml:3-4`
- Modify: `app/shared/src/main/AndroidManifest.xml:29`

- [ ] **Step 1: Update `resources.xml` `magisk` string**

Edit `app/core/src/main/res/values/resources.xml` line 5 — change the value `Magisk` to `FoxMask` (keep the `name="magisk"` and `translatable="false"` attributes — the resource key is referenced in many places):

```xml
    <string name="magisk" translatable="false">FoxMask</string>
```

- [ ] **Step 2: Update all user-facing "Magisk" occurrences in `strings.xml`**

Edit `app/core/src/main/res/values/strings.xml`. Replace the word `Magisk` with `FoxMask` in user-facing strings (the value text, NOT the `name=` attributes). The 23 lines to update (line numbers from the grep):

| Line | Old value (excerpt) | New value (excerpt) |
|------|---------------------|---------------------|
| 25 | `Download Magisk ONLY from...` | `Download FoxMask ONLY from...` |
| 29 | `Magisk is, and always will be, free...` | `FoxMask is, and always will be, free...` |
| 38 | `Uninstall Magisk` | `Uninstall FoxMask` |
| 39 | `...removed!\nAny internal storage unencrypted through the use of Magisk will be re-encrypted!` | `...removed!\nAny internal storage unencrypted through the use of FoxMask will be re-encrypted!` |
| 51 | `Directly install Magisk to the boot partition` | `Directly install FoxMask to the boot partition` |
| 67 | `...Magisk can\'t verify your response.` | `...FoxMask can\'t verify your response.` |
| 101 | `Magisk logs are empty, that\'s weird.` | `FoxMask logs are empty, that\'s weird.` |
| 160 | `Hide the Magisk app` | `Hide the FoxMask app` |
| 162 | `Restore the Magisk app` | `Restore the FoxMask app` |
| 174 | `Run parts of Magisk in the Zygote daemon` | `Run parts of FoxMask in the Zygote daemon` |
| 176 | `...all Magisk modifications reverted` | `...all FoxMask modifications reverted` |
| 237 | `Magisk updates` | `FoxMask updates` |
| 242 | `Magisk update available!` | `FoxMask update available!` |
| 243 | `Magisk updated` | `FoxMask updated` |
| 259 | `Hiding the Magisk app…` | `Hiding the FoxMask app…` |
| 268 | `...additional setup for Magisk to work properly...` | `...additional setup for FoxMask to work properly...` |
| 269 | `...reflash Magisk to work properly. Please reinstall Magisk within app...` | `...reflash FoxMask to work properly. Please reinstall FoxMask within app...` |
| 271 | `Unsupported Magisk version` | `Unsupported FoxMask version` |
| 272 | `...doesn\'t support Magisk versions lower than %1$s...as if no Magisk is installed. Please update Magisk as soon as possible.` | `...doesn\'t support FoxMask versions lower than %1$s...as if no FoxMask is installed. Please update FoxMask as soon as possible.` |
| 275 | `A "su" binary not from Magisk has been detected...reinstall Magisk.` | `A "su" binary not from FoxMask has been detected...reinstall FoxMask.` |
| 276 | `Magisk is installed to external storage...` | `FoxMask is installed to external storage...` |
| 277 | `The hidden Magisk app cannot continue...` | `The hidden FoxMask app cannot continue...` |

Do NOT touch:
- Line 119 `<!--MagiskHide-->` — that's a code-comment marker for a feature name, leave it.
- Any `name=` attributes (e.g. `name="uninstall_magisk_title"`) — those are resource keys referenced in code, leave them.
- The new `settings_mount_modules_*` strings added in Part 1 Task 5 — they don't contain "Magisk".

The cleanest approach: read the full file, then do targeted `Edit` calls per line, or do a single pass with multiple Edits. Each Edit must use enough surrounding context to be unique.

- [ ] **Step 3: Update `stub-res/strings.xml`**

Edit `app/stub-res/src/main/res/values/strings.xml` lines 3-4:

```xml
    <string name="upgrade_msg">Upgrade to full FoxMask to finish the setup. Download and install?</string>
    <string name="no_internet_msg">Please connect to the Internet! Upgrading to full FoxMask is required.</string>
```

- [ ] **Step 4: Update `shared/AndroidManifest.xml` app label**

Edit `app/shared/src/main/AndroidManifest.xml` line 29 — change `android:label="Magisk"` to `android:label="FoxMask"`:

```xml
        android:label="FoxMask"
```

- [ ] **Step 5: Verify resources compile and the app builds**

Run:

```bash
scripts/env.py bash -c 'cd app && ./gradlew assembleRelease 2>&1 | tail -40'
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Sanity-check no user-facing "Magisk" string remains**

Run:

```bash
scripts/env.py bash -c 'cd app && grep -rn ">.*Magisk" core/src/main/res/values/strings.xml stub-res/src/main/res/values/strings.xml'
```

Expected: no output (the only remaining `Magisk` references should be in `name=` attributes and code-comment markers, which `>.*Magisk` would not match because those are not inside string values).

- [ ] **Step 7: Commit**

```bash
git add app/core/src/main/res/values/resources.xml \
        app/core/src/main/res/values/strings.xml \
        app/stub-res/src/main/res/values/strings.xml \
        app/shared/src/main/AndroidManifest.xml
git commit -m "refactor(app): rebrand user-visible Magisk strings to FoxMask"
```

---

### Task 14: Final full-build verification

**Files:** none (verification only)

- [ ] **Step 1: Clean full build**

Run from `/workspace`:

```bash
scripts/env.py bash -c 'cd app && ./gradlew clean assembleRelease 2>&1 | tail -50'
```

Expected: BUILD SUCCESSFUL across all modules.

- [ ] **Step 2: Verify the APP_PACKAGE_NAME invariant**

The invariant `BuildConfig.APP_PACKAGE_NAME` (Kotlin) == `APP_PACKAGE_NAME` (Rust) == `JAVA_PACKAGE_NAME` (C++) == `applicationId` (gradle) must hold. Verify:

```bash
scripts/env.py bash -c 'echo "=== Rust ===" && grep "APP_PACKAGE_NAME" native/src/include/consts.rs && echo "=== C++ ===" && grep "JAVA_PACKAGE_NAME" native/src/include/consts.hpp && echo "=== Kotlin BuildConfig ===" && grep "APP_PACKAGE_NAME" app/core/build.gradle.kts && echo "=== Main appId ===" && grep "applicationId = " app/build-logic/src/main/java/Setup.kt && echo "=== Stub appId ===" && grep "applicationId = " app/stub/build.gradle.kts && echo "=== Test appId ===" && grep "applicationId = " app/test/build.gradle.kts'
```

Expected: every line shows `com.foxmask.app` (or `com.foxmask.app.test` for the test module).

- [ ] **Step 3: Verify mount toggle gate is in place**

```bash
scripts/env.py bash -c 'grep -A2 "mount_modules_enabled.load" native/src/core/bootstages.rs'
```

Expected: shows the `if self.mount_modules_enabled.load(Ordering::Relaxed) { self.handle_modules(); }` block.

- [ ] **Step 4: Verify su is preserved**

Read `native/src/core/bootstages.rs` lines 109-180 and confirm:
- `prune_su_access()` runs BEFORE the gate.
- `setup_magisk_env()` runs BEFORE the gate.
- The gate only wraps `handle_modules()`.
- `late_start` and `boot_complete` are untouched.
- The daemon, su binary tmpfs, and policy injection code paths are not in the gated block.

- [ ] **Step 5: Final commit (if any verification fixes were needed)**

If everything is already committed, skip. Otherwise:

```bash
git add -A
git commit -m "fix: verification adjustments"
```

---

## Out of Scope (Flagged Follow-ups)

These are noted in the spec but NOT part of this plan:
- Update `https://github.com/topjohnwu/Magisk/releases/download/` URL in `app/stub/build.gradle.kts:16` — requires decision on where FoxMask release artifacts will be hosted.
- Update URLs in `app/core/src/main/java/com/topjohnwu/magisk/core/Const.kt` — same reason.
- Update internal Java/Kotlin namespace `com.topjohnwu.magisk` → `com.foxmask.app` (R2 approach) — explicitly declined per user choice of R1.
- Localizations under `app/core/src/main/res/values-*/strings.xml` — those will inherit the English source-of-truth on next translation pass; not regenerated here.

---

## Self-Review

**1. Spec coverage:**

- §2.1 `MountModules` DbEntryKey added — Task 1. ✓
- §2.2 `mount_modules_enabled` AtomicBool on MagiskD — Task 2. ✓
- §2.3 Gate `handle_modules()` in `post_fs_data`, default ON, preserves su — Task 3 (gate) + Task 1 (default `1`) + Task 14 Step 4 (su preserved). ✓
- §2.4 Kotlin `Config.mountModules` writes via shared `settings` table — Task 4. ✓
- §2.5 Settings UI toggle in apk (legacy) — Task 6. ✓
- §2.5 Settings UI toggle in apk-ng (Compose) — Task 7. ✓
- §3.1 Native `APP_PACKAGE_NAME`/`JAVA_PACKAGE_NAME` → `com.foxmask.app` — Task 9. ✓
- §3.2 Kotlin `BuildConfig.APP_PACKAGE_NAME` → `com.foxmask.app` — Task 10. ✓
- §3.3 `applicationId` across gradle (main, stub, test) — Task 11. ✓
- §3.4 Test package refs (manifest, AppMigrationTest, Runners, test_common.sh) — Task 12. ✓
- §3.5 User-visible strings rebrand — Task 13. ✓
- §3.6 Keep internal Java/Kotlin namespace `com.topjohnwu.magisk` — explicit "Do NOT change namespace" notes in Tasks 10, 11. ✓
- §3.7 APP_PACKAGE_NAME invariant verification — Task 14 Step 2. ✓

**2. Placeholder scan:**

- No "TBD", "TODO", "implement later", "fill in details", "add appropriate error handling" anywhere.
- Every code step shows the actual code to write.
- Task 6 Steps 2-3 use the env-var mirror pattern (concrete: `Info.isMountModulesEnabled` reads `MOUNT_MODULES_ENABLED` env var, set in `set_script_env()` in `scripting.cpp` conditioned on `MagiskD::Get().mount_modules_enabled()`) — fully specified, no open-ended search instructions.

**3. Type consistency:**

- `DbEntryKey::MountModules` — used in lib.rs (Task 1 Step 1), db.rs (Task 1 Steps 2-3), bootstages.rs (Task 3). Same name throughout. ✓
- `mount_modules_enabled` field on `MagiskD` — used in daemon.rs (Task 2 Step 1), lib.rs FFI (Task 2 Step 3), bootstages.rs (Task 3). Same name throughout. ✓
- `mount_modules_enabled()` accessor method — declared in lib.rs FFI (Task 2 Step 3), referenced from Kotlin `Info.isMountModulesEnabled` assignment (Task 6 Step 3). ✓
- `Config.mountModules` — defined in Config.kt (Task 4), used in SettingsItems.kt (Task 6 Step 1), apk-ng SettingsViewModel.kt (Task 7 Step 1), apk-ng SettingsScreen.kt (Task 7 Step 2). Same name throughout. ✓
- `Info.isMountModulesEnabled` — added in Task 6 Step 2, referenced in Task 6 Step 1 (apk), Task 7 Step 1 (apk-ng), Task 7 Step 2 (apk-ng). Same name throughout. ✓
- `Key.MOUNT_MODULES = "mount_modules"` (Kotlin) matches `DbEntryKey::MountModules.to_str() => "mount_modules"` (Rust). ✓
- String resource keys `settings_mount_modules_title`/`_summary`/`_warning` — defined in Task 5, referenced in Task 6 Step 1 (apk) and Task 7 Step 2 (apk-ng). Same names throughout. ✓
- `SystemlessMount` object name — defined in Task 6 Step 1, referenced in Task 6 Steps 4-5. ✓
- `notifyMountModulesChange()` and `mountModulesMismatch` — defined in Task 7 Step 1, referenced in Task 7 Step 2. ✓
- `applicationId` value `com.foxmask.app` consistent across Setup.kt (Task 11 Step 1), stub (Task 11 Step 2), AndroidManifest targetPackage (Task 12 Step 1), AppMigrationTest APP_PKG (Task 12 Step 2), Runners.kt (Task 12 Step 3), test_common.sh (Task 12 Step 4). Test module uses `com.foxmask.app.test`. ✓
- `APP_PACKAGE_NAME`/`JAVA_PACKAGE_NAME` value `com.foxmask.app` consistent across consts.rs (Task 9 Step 1), consts.hpp (Task 9 Step 2), core/build.gradle.kts BuildConfig (Task 10 Step 1). ✓

No type/name inconsistencies found.
