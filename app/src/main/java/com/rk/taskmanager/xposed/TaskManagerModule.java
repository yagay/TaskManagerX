package com.rk.taskmanager.xposed;

import android.util.Log;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedModule;

/**
 * Modern libxposed API 102 entry point.
 *
 * Phase 1 intentionally installs no behavioral hooks. Root owns process/system
 * collection. This module establishes the API-102/system_server integration
 * point that the later UID network monitor can use without redesigning the app.
 */
public final class TaskManagerModule extends XposedModule {
    private static final String TAG = "TaskManagerXposed";

    @Override
    public void onModuleLoaded(@NonNull ModuleLoadedParam param) {
        log(
                Log.INFO,
                TAG,
                "Loaded in " + param.getProcessName()
                        + "; framework=" + getFrameworkName()
                        + "; api=" + getApiVersion()
        );
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if ("android".equals(param.getPackageName())) {
            log(Log.INFO, TAG, "Android framework package loaded; system enhancement layer ready");
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        // Reserved for phase 2:
        // - NetworkStatsService / netd related observation
        // - UID-level network accounting compatibility hooks
        // No hooks are installed in phase 1 to minimize system_server risk.
    }

    @Override
    public boolean onHotReloading(@NonNull HotReloadingParam param) {
        return true;
    }

    @Override
    public void onHotReloaded(@NonNull HotReloadedParam param) {
        param.getOldHookHandles().forEach(HookHandle::unhook);
    }
}
