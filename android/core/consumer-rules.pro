# kotlinx.serialization — preserve @Serializable classes and their companion serializers.
-keep,includedescriptorclasses class com.backroadstatus.sdk.core.**$$serializer { *; }
-keepclassmembers class com.backroadstatus.sdk.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.backroadstatus.sdk.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
