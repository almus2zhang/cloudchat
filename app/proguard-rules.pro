# General Kotlin & Compose
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-dontwarn org.jetbrains.annotations.**

# Compose UI & Material3
-keep class androidx.compose.ui.** { *; }
-keep class androidx.compose.material3.** { *; }
-keep class androidx.compose.runtime.** { *; }
-keep class androidx.compose.foundation.** { *; }
-dontwarn androidx.compose.**

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Gson rules
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class com.cloudchat.model.** { *; }
-keep class com.cloudchat.repository.** { *; }
-keep class com.cloudchat.utils.** { *; }

# Prevent obfuscation of serializable fields for Gson
-keepclassmembers class com.cloudchat.model.** { <fields>; }

# AWS SDK
-keep class com.amazonaws.** { *; }
-dontwarn com.amazonaws.**

# OkHttp/Okio
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# AndroidX DataStore
-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }

# Navigation
-keep class androidx.navigation.** { *; }

# Coil
-keep class coil.** { *; }
-dontwarn coil.**

# Enums - essential for valueOf() calls
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Keep the entire project package to avoid class missing
-keep class com.cloudchat.** { *; }
