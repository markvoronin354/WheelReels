# R8 Shrinking & Optimization Keep Rules

# Keep Shizuku Reflection & Binder Interfaces
-keep class rikka.shizuku.** { *; }
-keepclassmembers class rikka.shizuku.Shizuku {
    private static rikka.shizuku.ShizukuRemoteProcess newProcess(java.lang.String[], java.lang.String[], java.lang.String);
}

# Keep Services
-keep class com.markvoronin.reelsonthego.service.** { *; }
