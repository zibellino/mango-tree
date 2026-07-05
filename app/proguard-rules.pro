# Kotlin
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }

# JGit / AppAuth reflection-sensitive classes
-keep class org.eclipse.jgit.** { *; }
-keep class net.openid.appauth.** { *; }

# JGit references these optional integrations conditionally; we don't ship
# them, so R8 just needs to stop treating the missing classes as errors.
-dontwarn org.slf4j.**
-dontwarn com.jcraft.jsch.**
-dontwarn org.apache.sshd.**
-dontwarn com.googlecode.javaewah.**
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn javax.naming.**
-dontwarn sun.security.**
-dontwarn org.ietf.jgss.**

# JGit's JMX monitoring (WindowCache/GC PID lock) uses desktop-JDK-only APIs
# that don't exist on Android; we never invoke this path.
-dontwarn java.lang.ProcessHandle
-dontwarn java.lang.management.**
-dontwarn javax.management.**

# Tink (pulled in transitively) references the JSR-305 annotations as
# compile-only; they're not needed at runtime.
-dontwarn javax.annotation.Nullable
-dontwarn javax.annotation.concurrent.GuardedBy

# Keep custom app classes (add your own as needed)
-keep class com.mangotree.** { *; }
