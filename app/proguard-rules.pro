# Parseable Android ProGuard Rules

# Keep kotlinx serialization
-keepattributes *Annotation*, InnerClasses, Signature
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep all @Serializable model classes and their generated serializers
-keep,includedescriptorclasses class com.parseable.android.**$$serializer { *; }
-keepclassmembers class com.parseable.android.** {
    *** Companion;
}
-keepclasseswithmembers class com.parseable.android.** {
    kotlinx.serialization.KSerializer serializer(...);
}
# Keep @Serializable data classes (fields needed for JSON encoding/decoding)
-keep @kotlinx.serialization.Serializable class com.parseable.android.data.model.** { *; }

# Keep ViewModel state data classes (used with StateFlow/collectAsState)
-keep class com.parseable.android.ui.screens.**State { *; }
-keep class com.parseable.android.ui.screens.logviewer.FilterState { *; }
-keep class com.parseable.android.ui.screens.logviewer.StreamingState { *; }
-keep class com.parseable.android.ui.screens.streams.StreamsViewModel$StreamStatsUi { *; }

# Room entities
-keep class com.parseable.android.data.local.** { *; }

# OkHttp (library ships its own consumer rules; only suppress warnings)
-dontwarn okhttp3.**
-dontwarn okio.**

# Keep the custom X509TrustManager used for insecure TLS config
-keep class com.parseable.android.data.api.ParseableApiClient { *; }

# EncryptedSharedPreferences (androidx.security:security-crypto)
-keep class androidx.security.crypto.** { *; }
-keep class * extends android.content.SharedPreferences { *; }

# Google Tink (transitive dependency of androidx.security:security-crypto)
# Tink's complex class hierarchy and use of Protobuf causes R8 crashes
# in security-crypto alpha versions that ship incomplete consumer rules.
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# Protobuf Lite (Tink dependency)
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# Annotation libraries referenced by Tink/Google libs but not always on classpath
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn org.checkerframework.**
-dontwarn com.google.api.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn sun.misc.Unsafe
