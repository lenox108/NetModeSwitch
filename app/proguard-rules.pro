# libsu spawns its own entry point inside the root shell.
-keep class com.topjohnwu.superuser.internal.** { *; }

# Keep warnings and errors: on an unfamiliar OEM build they are the only signal that a
# telephony command failed.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
