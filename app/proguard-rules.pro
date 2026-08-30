# Add project specific ProGuard rules here.

# Hilt
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.lifecycle.HiltViewModel

# OkHttp（无 Retrofit，仅 OkHttp + kotlinx.serialization 走 SSE / JSON）
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**

# 业务 model（Room 实体 + DTO，避免反射序列化丢字段）
-keep class com.llmhub.app.data.model.** { *; }

# Kotlinx Serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.llmhub.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.llmhub.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
