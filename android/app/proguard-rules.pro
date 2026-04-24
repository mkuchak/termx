# Add project-specific ProGuard / R8 rules here as needed.
# Defaults from `proguard-android-optimize.txt` already apply.

# sshj + BouncyCastle + i2p EdDSA pull in JDK-only classes that don't
# ship on Android. Tell R8 to not warn about missing references; the
# code paths that use them never run on-device.
-dontwarn sun.security.**
-dontwarn net.i2p.crypto.eddsa.**
-dontwarn org.bouncycastle.**
-dontwarn com.hierynomus.**
-dontwarn javax.naming.**
-dontwarn javax.security.auth.**
-dontwarn org.ietf.jgss.**
-dontwarn org.slf4j.**
-dontwarn net.schmizz.sshj.userauth.method.AuthGssApi**

# Keep BouncyCastle providers — registered reflectively via Security.addProvider
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.jcajce.provider.** { *; }

# sshj resolves hostkey/kex/cipher implementations by class name via reflection
-keep class net.schmizz.sshj.** { *; }

# i2p EdDSA — reflective lookups on algorithm providers
-keep class net.i2p.crypto.eddsa.** { *; }

# kotlinx.serialization keeps its @Serializable descriptors via reflection
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
