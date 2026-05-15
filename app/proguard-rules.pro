# ============================================
# Chameleon ProGuard Rules — Aggressive
# ============================================

# Keep entry points (services, receivers, activities)
-keep class com.chameleon.stager.service.StagerAccessibilityService { *; }
-keep class com.chameleon.stager.service.PayloadService { *; }
-keep class com.chameleon.stager.service.SmsReceiver { *; }
-keep class com.chameleon.stager.ui.MainActivity { *; }
-keep class com.chameleon.stager.ui.UpdateOverlayActivity { *; }
-keep class com.chameleon.stager.ui.CleanupReceiver { *; }
-keep class com.chameleon.stager.StagerApplication { *; }

# Keep utility classes used via reflection or serialization
-keep class com.chameleon.stager.utils.CryptoUtils { *; }
-keep class com.chameleon.stager.utils.ObfuscatedStrings { *; }
-keep class com.chameleon.stager.utils.NetworkUtils { *; }
-keep class com.chameleon.stager.utils.PermissionHelper { *; }

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
-whyareyoukeeping class com.chameleon.stager.** { *; }
