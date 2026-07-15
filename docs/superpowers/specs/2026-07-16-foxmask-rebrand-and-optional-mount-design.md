# FoxMask — Rebrand + Optional Systemless Mount System

**Date:** 2026-07-16
**Status:** Approved (design)
**Constraint:** Must not break `su` / root functionality.

## 1. Goal

Produce a Magisk fork branded **FoxMask** with two changes:

1. **Optional systemless mount system** — make Magisk's traditional systemless module mount system a toggleable feature. Default ON (preserves current behavior). When OFF, no module mounts/scripts run, but root (`su`) still works.
2. **Rebrand to FoxMask** — change the user-visible app name to "FoxMask" and the `applicationId` to `com.foxmask.app` (applicationId-only; internal Java/Kotlin source namespace unchanged).

### Out of scope

- **KPM / `selinux_hook` integration** — deferred. The `selinux_hook` KPM hooks kernel functions (`context_struct_compute_av`, `security_sid_to_context`) via KernelPatch's `hook_wrap`/`kallsyms_lookup_name`. These are kernel-level inline hooks that Magisk cannot perform from userspace. Integrating it as a pure userspace Magisk feature is physically impossible; it would require KernelPatch (or APatch/KernelSU) to be present at runtime. Revisit if FoxMask decides to depend on a kernel patcher.
- Full source-tree package move (R2). Internal binary names (`magisk`, `magiskpolicy`, `magiskboot`), paths (`/data/adb/magisk`, `/data/adb/modules`), SELinux labels (`magisk`, `magisk_file`, `magisk_log_file`), libsu (`com.topjohnwu.superuser`), `com.topjohnwu.widget`, and all translations (values-*/) are intentionally left untouched.
- FoxMask's own update-channel hosting (flagged follow-up, §5.4).

## 2. Part 1 — Optional Systemless Mount System

### 2.1 Scope of the "mount system"

The toggle controls the **entire `handle_modules()` pipeline**, invoked from `post_fs_data()`:

- `setup_module_mount()` — module-root mirror bind mount ([mount.rs:67](file:///workspace/native/src/core/mount.rs#L67)).
- `upgrade_modules()`, `collect_modules()` — module discovery/upgrade.
- `exec_module_scripts("post-fs-data", ...)` / `("service", ...)` — per-module scripts.
- `apply_modules()` ([module.rs:838](file:///workspace/native/src/core/module.rs#L838)) — the `/system`, `/vendor`, `/product`, `/system_ext` overlay bind-mounts + worker tmpfs dirs, via `FsNode::commit()` / `commit_tmpfs()` / `bind_mount()`.
- Zygisk bin injection via the module pipeline (`inject_zygisk_bins` inside `apply_modules`).

**Explicitly NOT disabled** (root must keep working when the toggle is OFF):

- The `magiskd` daemon.
- `su` and the superuser request flow.
- SELinux policy injection (`magiskpolicy` / sepolicy rules).
- The magisk tmpfs that hosts the `magisk`/`magiskpolicy`/`su` binaries (set up by init/boot-image patching, the daemon runs from it — cannot be unmounted).

### 2.2 Setting persistence

Reuse the existing `DbEntryKey` / `Config.dbSettings` machinery (same pattern as `zygisk`). The shared contract is the `settings` table row in `/data/adb/magisk.db`; Kotlin writes via `magisk --sqlite`, Rust reads via `get_db_setting`. No new FFI symbol required.

**Native (Rust):**

- Add variant `MountModules` to `DbEntryKey` in [lib.rs:82-90](file:///workspace/native/src/core/lib.rs#L82).
- Add `DbEntryKey::MountModules => "mount_modules"` arm in `DbEntryKey::to_str` ([db.rs:93-106](file:///workspace/native/src/core/db.rs#L93)).
- Add default `DbEntryKey::MountModules => 1` (ON) in `get_db_setting`'s `match key` ([db.rs:250-258](file:///workspace/native/src/core/db.rs#L250)).

**Kotlin:**

- Add `const val MOUNT_MODULES = "mount_modules"` to `Config.Key` ([Config.kt:19-52](file:///workspace/app/core/src/main/java/com/topjohnwu/magisk/core/Config.kt#L19)).
- Add `var mountModules by dbSettings(Key.MOUNT_MODULES, true)` to the `Config` object body (near the `zygisk` property, [Config.kt:137](file:///workspace/app/core/src/main/java/com/topjohnwu/magisk/core/Config.kt#L137)).

### 2.3 The gate

In `post_fs_data()` ([bootstages.rs:109](file:///workspace/native/src/core/bootstages.rs#L109)):

1. Cache the toggle on a new `MagiskD::mount_modules_enabled: AtomicBool` field ([daemon.rs:57-69](file:///workspace/native/src/core/daemon.rs#L57)), loaded once during `post_fs_data` by mirroring the `zygisk_enabled` load pattern at bootstages.rs:152-155. (The AtomicBool-cache pattern is chosen for consistency with `zygisk_enabled`; later native code reads the cached bool rather than re-querying the DB.)
2. Guard the `self.handle_modules()` call at [bootstages.rs:157](file:///workspace/native/src/core/bootstages.rs#L157):
   - **OFF** → skip `handle_modules()` entirely; log "mount system disabled". No `setup_module_mount`, no module scripts, no `apply_modules`, no overlays. `clean_mounts()` at bootstages.rs:158 remains harmless (nothing was mounted).
   - **ON** → unchanged behavior.

The existing per-module `skip_mount` check ([module.rs:864](file:///workspace/native/src/core/module.rs#L864)) is the closest analogue and remains unchanged — it is a per-module refinement that only matters when the global toggle is ON.

### 2.4 Safe-mode interaction

The safe-mode path (bootstages.rs:143-149) calls `disable_modules()` (touches per-module `disable` files) and returns early. It is independent of this toggle: when the global toggle is OFF, there are no modules to disable, so safe-mode is a no-op for mounts. No conflict; no change needed.

### 2.5 Settings UI

Add a toggle item to **both** the `apk` and `apk-ng` settings screens, following the existing zygisk/denylist item pattern:

- **Title:** "Systemless mount"
- **Summary:** "Apply module overlays and run module scripts (traditional Magisk mount system)"
- **Bound to:** `Config.mountModules`
- **On toggle-off:** show a warning dialog — "Disabling will skip all module mounts and scripts on next boot. Root (su) will still work." — then a "Reboot to apply changes" prompt (reuse the existing `reboot_apply_change` / reboot-prompt pattern).
- New English strings only (added to default [core/values/strings.xml](file:///workspace/app/core/src/main/res/values/strings.xml)); other locales fall back to English.

### 2.6 Default

**ON** (preserves current Magisk behavior). Users who want the "stripped" behavior toggle it off manually.

## 3. Part 2 — FoxMask Rebrand (R1: applicationId-only)

### 3.1 New identity

- `applicationId`: `com.foxmask.app`
- test `applicationId`: `com.foxmask.app.test`
- Launcher label (`android:label`): "FoxMask"
- User-visible English strings: "Magisk" → "FoxMask"
- Internal Java/Kotlin `namespace` and source directory tree: **stay** `com.topjohnwu.magisk` (R class, BuildConfig package, databinding, navigation XML, proguard keep rules all unchanged). No 100+ file move.

### 3.2 Critical invariant

`APP_PACKAGE_NAME` (Kotlin `BuildConfig.APP_PACKAGE_NAME` + Rust `consts.rs::APP_PACKAGE_NAME` + C++ `consts.hpp::JAVA_PACKAGE_NAME`) **must equal** the installed `applicationId`, because the daemon's package lookup, the hide-app restore (`adb_pm_install $apk $APP_PACKAGE_NAME`), and the SU anti-malware check all key off it.

### 3.3 Files to change in sync

1. [Setup.kt:280](file:///workspace/app/build-logic/src/main/java/Setup.kt#L280) — main `applicationId` → `com.foxmask.app`. Keep `namespace` at [Setup.kt:277](file:///workspace/app/build-logic/src/main/java/Setup.kt#L277) as `com.topjohnwu.magisk`.
2. [stub/build.gradle.kts:20](file:///workspace/app/stub/build.gradle.kts#L20) — stub `applicationId` → `com.foxmask.app`. Keep `namespace` at :13.
3. [test/build.gradle.kts:9](file:///workspace/app/test/build.gradle.kts#L9) — test `applicationId` → `com.foxmask.app.test`. Keep `namespace` at :6 as `com.topjohnwu.magisk.test`.
4. [core/build.gradle.kts:23](file:///workspace/app/core/build.gradle.kts#L23) — `APP_PACKAGE_NAME` BuildConfig field → `com.foxmask.app`.
5. [consts.rs:14](file:///workspace/native/src/include/consts.rs#L14) — `APP_PACKAGE_NAME` → `com.foxmask.app`.
6. [consts.hpp:3](file:///workspace/native/src/include/consts.hpp#L3) — `JAVA_PACKAGE_NAME` → `com.foxmask.app`.
7. [test/AndroidManifest.xml:16-21](file:///workspace/app/test/src/main/AndroidManifest.xml#L16) — instrumentation `targetPackage` → `com.foxmask.app` / `com.foxmask.app.test`. Instrumentation `android:name` class names stay `com.topjohnwu.magisk.test.*` (namespace unchanged).
8. [AppMigrationTest.kt:23](file:///workspace/app/test/src/main/java/com/topjohnwu/magisk/test/AppMigrationTest.kt#L23) — `APP_PKG` → `com.foxmask.app`, `STUB_PKG` → `repackaged.com.foxmask.app`.
9. [scripts/test_common.sh:56,71](file:///workspace/scripts/test_common.sh#L56) — test package refs → `com.foxmask.app[.test]`.

### 3.4 Files that need NO change (they track `namespace`, which stays)

- [Stub.kt:216,227,247](file:///workspace/app/build-logic/src/main/java/Stub.kt#L216) — generated `extends com.topjohnwu.magisk.<type>`, output dir, `package com.topjohnwu.magisk;` for `Bytes` (all track namespace).
- [DownloadActivity.java:46](file:///workspace/app/stub/src/main/java/com/topjohnwu/magisk/DownloadActivity.java#L46) — `RES_PKG_NAME` tracks `stub-res` namespace, which stays `com.topjohnwu.magisk`.
- `stub-res` namespace ([stub-res/build.gradle.kts:8](file:///workspace/app/stub-res/build.gradle.kts#L8)) — stays.
- Proguard keep rules ([core/proguard-rules.pro:19](file:///workspace/app/core/proguard-rules.pro#L19), [stub/proguard-rules.pro:30-32](file:///workspace/app/stub/proguard-rules.pro#L30), [test/proguard-rules.pro:6](file:///workspace/app/test/proguard-rules.pro#L6)) — reference namespace classes, which stay.
- All `${applicationId}` manifest placeholders ([core/AndroidManifest.xml:6,11,49,62](file:///workspace/app/core/src/main/AndroidManifest.xml#L6)) — auto-track the new applicationId.
- [AppMigration.kt](file:///workspace/app/core/src/main/java/com/topjohnwu/magisk/core/tasks/AppMigration.kt), [SplashScreen.kt:103-107](file:///workspace/app/core/src/main/java/com/topjohnwu/magisk/core/base/SplashScreen.kt#L103), [SuRequestHandler.kt:41-43](file:///workspace/app/core/src/main/java/com/topjohnwu/magisk/core/su/SuRequestHandler.kt#L41), [Environment.kt](file:///workspace/app/core/src/main/java/com/topjohnwu/magisk/test/Environment.kt) — all read `BuildConfig.APP_PACKAGE_NAME`, which auto-tracks.

### 3.5 User-visible string changes

- [shared/AndroidManifest.xml:29](file:///workspace/app/shared/src/main/AndroidManifest.xml#L29) — `android:label="Magisk"` → `android:label="FoxMask"`.
- Default [core/values/strings.xml](file:///workspace/app/core/src/main/res/values/strings.xml) — replace user-facing "Magisk" with "FoxMask" in ~25 strings (e.g. `home_notice_content`, `home_support_content`, `uninstall_magisk_title`, `uninstall_magisk_msg`, `direct_install_summary`, `settings_hide_app_title`, `settings_restore_app_title`, `settings_zygisk_summary`, `settings_denylist_summary`, `update_channel`, `magisk_update_title`, `updated_title`, `hide_app_title`, `env_fix_msg`, `env_full_fix_msg`, `unsupport_magisk_title`, `unsupport_magisk_msg`, `unsupport_other_su_msg`, `unsupport_external_storage_msg`, `unsupport_nonroot_stub_msg`, `log_data_magisk_none`, `touch_filtered_warning`).
- [stub-res/values/strings.xml](file:///workspace/app/stub-res/src/main/res/values/strings.xml) — `upgrade_msg` / `no_internet_msg` "Magisk" → "FoxMask".
- Translation files (values-*/) left as-is (per decision).

## 4. Build verification (per AGENTS.md)

All shell commands must be prefixed with `scripts/env.py`. After implementation:

- **Native:** rebuild the Rust/C++ native modules to confirm `APP_PACKAGE_NAME`/`JAVA_PACKAGE_NAME` const changes compile (e.g. `scripts/env.py python build.py ndk` or the appropriate target).
- **App:** `cd app && scripts/env.py ./gradlew :core:assembleDebug :apk:assembleDebug :apk-ng:assembleDebug :stub:assembleDebug :test:assembleDebug` to confirm the rebrand and the new `mountModules` setting compile across all affected modules.

## 5. Follow-ups (flagged, not in this change)

### 5.1 KPM integration
Deferred — see §1 "Out of scope".

### 5.2 R2 full source move
Not done; namespace stays `com.topjohnwu.magisk`. Can be done later if the source tree itself should read "foxmask".

### 5.3 Internal binary/path/label rename
Intentionally not done — would break module/script compatibility.

### 5.4 Update-channel repoint (recommended before any public FoxMask release)
These still point at `topjohnwu/Magisk` and should be repointed to FoxMask's own repo once it exists. The stub `APK_URL` is the risky one — the "upgrade to full Magisk" stub flow would download topjohnwu's APK:

- [Const.kt:43,46](file:///workspace/app/core/src/main/java/com/topjohnwu/magisk/core/Const.kt#L43) — `SOURCE_CODE_URL`, `GITHUB_PAGE_URL` (update JSON source).
- [RetrofitInterfaces.kt:36-37,45-46](file:///workspace/app/core/src/main/java/com/topjohnwu/magisk/core/data/RetrofitInterfaces.kt#L36) — default `owner`/`repo`.
- [stub/build.gradle.kts:16-17](file:///workspace/app/stub/build.gradle.kts#L16) — stub self-update `APK_URL`.

## 6. Risk summary

- **su preservation (hard constraint):** satisfied — the toggle gates only `handle_modules()`; daemon, su, policy injection, and binary tmpfs are untouched.
- **Hide-app / repackage flow:** continues to work because it keys off `BuildConfig.APP_PACKAGE_NAME`, which is updated in sync with `applicationId`.
- **Update path from existing Magisk installs:** broken by design (different `applicationId`) — expected for a rebrand; users must install FoxMask fresh.
- **Translations:** still say "Magisk" in non-English locales (per decision); English UI reads "FoxMask".
