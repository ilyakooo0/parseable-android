# Parseable Android ProGuard Rules

# Keep kotlinx serialization
-keepattributes *Annotation*, InnerClasses
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

# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
# OkHttp platform adapters use reflection for SSL socket factories
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
-dontwarn okhttp3.internal.platform.**
-keep class okhttp3.internal.platform.** { *; }
# Keep custom SSL trust managers used by the insecure TLS config
-keep class javax.net.ssl.** { *; }
-keep class java.security.cert.** { *; }

# Okio
-dontwarn okio.**
-keep class okio.** { *; }

# EncryptedSharedPreferences / AndroidX Security
-keep class androidx.security.crypto.** { *; }
