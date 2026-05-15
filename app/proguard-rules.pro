# Keep the accessibility service
-keep class com.chameleon.stager.service.StagerAccessibilityService { *; }

# Keep the foreground service
-keep class com.chameleon.stager.service.PayloadService { *; }

# Keep DexClassLoader usage
-keep class dalvik.system.DexClassLoader { *; }

# Keep crypto utilities
-keep class com.chameleon.stager.utils.CryptoUtils { *; }

# Obfuscate everything else aggressively
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
