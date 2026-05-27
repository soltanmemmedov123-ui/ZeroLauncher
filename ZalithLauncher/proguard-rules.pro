# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in C:\tools\adt-bundle-windows-x86_64-20131030\sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# ── exp4j reflection ─────────────────────────────────────────────────────────
# We use Reflection on the builder to avoid creating too many objects
-keep class net.objecthunter.exp4j.ExpressionBuilder**
-keepclassmembers class net.objecthunter.exp4j.ExpressionBuilder** {
    *;
}

# ── java.awt shim (PojavLauncher / LWJGL) ────────────────────────────────────
# The awt shim is loaded by name at runtime. Keep class names intact so R8
# doesn't rename them (this was the original reason minify was disabled).
-keep class java.awt.** { *; }
-keep class sun.awt.** { *; }
-dontwarn java.awt.**
-dontwarn sun.awt.**

# ── SDL / native JNI ─────────────────────────────────────────────────────────
-keep class org.libsdl.app.** { *; }
-keepclassmembers class org.libsdl.app.** { *; }

# ── PojavLauncher JNI callbacks ──────────────────────────────────────────────
-keep class net.kdt.pojavlaunch.** { *; }
-keepclassmembers class net.kdt.pojavlaunch.** {
    native <methods>;
    public *;
}

# ── ZeroLauncher activity (loaded by name for launch delegation) ──────────────
-keep class com.movtery.zalithlauncher.ui.activity.LauncherActivity { *; }
-keep class com.movtery.zalithlauncher.zerolauncher.ZeroLauncherActivity { *; }

# ── Settings / EventBus (uses reflection to find subscriber methods) ──────────
-keep class com.movtery.zalithlauncher.setting.** { *; }
-keep class com.movtery.zalithlauncher.event.** { *; }
-keepclassmembers class ** {
    @org.greenrobot.eventbus.Subscribe <methods>;
}
-keep enum org.greenrobot.eventbus.ThreadMode { *; }

# ── Gson serialisation (field names must survive obfuscation) ─────────────────
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ── Glide generated API ───────────────────────────────────────────────────────
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.ImageHeaderParser$** { *; }

# ── OkHttp / Okio ────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**

# ── bytehook (native hook lib) ────────────────────────────────────────────────
-keep class com.bytedance.** { *; }
-dontwarn com.bytedance.**

# ── FloatingX ────────────────────────────────────────────────────────────────
-keep class com.petterp.floatingx.** { *; }

# ── toml4j ───────────────────────────────────────────────────────────────────
-keep class com.moandjiezana.toml.** { *; }
