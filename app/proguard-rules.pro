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

# PreviewController disables CompositionPlayer's video prewarming via reflection on devices that
# can't run two concurrent hardware decoders (multi-clip full-effects preview fails otherwise).
# Media3 exposes no public setter, so keep this package-private method's NAME so R8 minification
# doesn't rename it and break the reflection. If a future Media3 removes it (it is @VisibleForTesting,
# tracking b/369817794), the reflection fails gracefully and the preview falls back as before.
-keepclassmembers class androidx.media3.transformer.CompositionPlayer$Builder {
    *** setVideoPrewarmingEnabled(boolean);
}
