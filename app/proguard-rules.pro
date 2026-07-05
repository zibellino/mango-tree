# Kotlin
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }

# JGit / AppAuth reflection-sensitive classes
-keep class org.eclipse.jgit.** { *; }
-keep class net.openid.appauth.** { *; }

# Keep custom app classes (add your own as needed)
-keep class com.mangotree.** { *; }
