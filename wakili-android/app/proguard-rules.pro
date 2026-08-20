# Retrofit, OkHttp, Coil and kotlinx.serialization all ship their own consumer
# R8 rules — only the documented gap needs covering here: Retrofit's
# kotlinx-serialization converter looks serializers up REFLECTIVELY
# (serializer(Type)), which R8 can't trace. Keep the generated serializers for
# our own @Serializable classes — the official kotlinx.serialization R8
# snippet, scoped to this app's package.
-keepattributes InnerClasses

-if @kotlinx.serialization.Serializable class
com.ahmedgamil.wakili.**
{
    static **$* *;
}
-keepnames class <2>$$serializer {
    static <2>$$serializer INSTANCE;
}

-if @kotlinx.serialization.Serializable class com.ahmedgamil.wakili.** {
    static ** Companion;
}
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

-if @kotlinx.serialization.Serializable class com.ahmedgamil.wakili.** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
