# JobRadar Release R8 / ProGuard keep rules

-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes *Annotation*, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations, AnnotationDefault

# Kotlinx Serialization (1.9.0) — preserve serializers, companions, and synthetic constructors
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
    public <init>(int, ..., kotlinx.serialization.internal.SerializationConstructorMarker);
}
-if @kotlinx.serialization.Serializable class **
-keep class <1>$$serializer { *; }
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    public static final <1> INSTANCE;
}

# Preserve API envelopes, wire DTOs, and type-safe Navigation routes
-keep,includedescriptorclasses class com.jobradar.app.data.remote.dto.** { *; }
-keep,includedescriptorclasses class com.jobradar.app.core.network.ApiResponse { *; }
-keep,includedescriptorclasses class com.jobradar.app.core.network.ApiResponse$** { *; }
-keep,includedescriptorclasses class com.jobradar.app.core.network.ApiError { *; }
-keep,includedescriptorclasses class com.jobradar.app.core.network.ApiError$** { *; }
-keep,includedescriptorclasses class com.jobradar.app.ui.navigation.*Route { *; }
-keep,includedescriptorclasses class com.jobradar.app.ui.navigation.*Route$** { *; }

# Retrofit (3.0.0) service interface and HTTP method annotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-keep interface com.jobradar.app.data.remote.JobRadarApiService { *; }

# Targeted optional JVM/TLS provider exclusions for Ktor / OkHttp
-dontwarn org.slf4j.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
