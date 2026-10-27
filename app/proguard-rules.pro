# libsu spawns its own entry point inside the root shell.
-keep class com.topjohnwu.superuser.internal.** { *; }

# Shizuku instantiates the user service BY NAME inside a separate process, where R8's renaming
# is invisible to it: obfuscating this class yields
# "InstantiationException: java.lang.Class<ms> cannot be instantiated".
-keep class com.netmode.switcher.ShizukuShellService { *; }
-keep interface com.netmode.switcher.IShellService { *; }
-keep class com.netmode.switcher.IShellService$* { *; }
-keep class rikka.shizuku.** { *; }
-keep class moe.shizuku.** { *; }

# Keep warnings and errors: on an unfamiliar OEM build they are the only signal that a
# telephony command failed.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
# Run by the privileged shell through app_process; nothing in the app references it.
-keep class com.netmode.switcher.HotspotCli { public static void main(java.lang.String[]); }
-keep class com.netmode.switcher.SuspendCli { public static void main(java.lang.String[]); }
-keep class com.netmode.switcher.ChannelCli { public static void main(java.lang.String[]); }
-keep class com.netmode.switcher.VpnCli { public static void main(java.lang.String[]); }
-keep class com.netmode.switcher.ApConfigCli { public static void main(java.lang.String[]); }
-keep class com.netmode.switcher.HotspotReadCli { public static void main(java.lang.String[]); }
-keep class com.netmode.switcher.SimCli { public static void main(java.lang.String[]); }
-keep class com.netmode.switcher.PhoneCli { public static void main(java.lang.String[]); }
-keep class com.netmode.switcher.SocksCli { public static void main(java.lang.String[]); }
