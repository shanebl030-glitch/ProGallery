# ProGallery R8 rules.
# Compose, Coil, Media3 and AndroidX ship their own consumer rules; only app-specific
# keeps are needed here.

# Keep enum names used for persisted settings (stored by name in DataStore).
-keepclassmembers enum com.shaneblackburn.progallery.** {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep line numbers for readable crash reports.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
