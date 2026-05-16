package com.movtery.zalithlauncher;

import android.app.Application;

/**
 * Zero Launcher Application class.
 * Initialises global state before any activity starts.
 */
public class ZeroLauncherApp extends Application {

    private static ZeroLauncherApp instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    public static ZeroLauncherApp getInstance() {
        return instance;
    }
}
