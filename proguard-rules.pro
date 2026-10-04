# Shrink only, no obfuscation: removes unused code and the @kotlin.Metadata block
# embedded in every .class, without renaming any class/field/method.

-dontobfuscate
-dontoptimize
-verbose

# Keep runtime annotations (e.g. @Service, @State, @Storage, read via reflection by
# the IntelliJ Platform). kotlin.Metadata is NOT explicitly kept, so ProGuard's
# Kotlin-aware handling strips it anyway - this is the main size saving here.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations,AnnotationDefault
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable

# Classes referenced only by FQCN string in plugin.xml.
-keep class com.shutterstar.agenthub.AgentSettingsConfigurable { *; }
-keep class com.shutterstar.agenthub.LlmBrainsStartupActivity { *; }
-keep class com.shutterstar.agenthub.projects.ui.AgentHubToolWindowFactory { *; }
-keep class com.shutterstar.agenthub.LlmBrainsActionGroup { *; }

# PersistentStateComponent state classes - field names matter for XML (de)serialization.
-keep class com.shutterstar.agenthub.AgentSettingsState { *; }
-keep class com.shutterstar.agenthub.AgentSettingsState$* { *; }
-keep class com.shutterstar.agenthub.projects.persistence.** { *; }
-keep class com.shutterstar.agenthub.environment.persistence.** { *; }

# The scoped -libraryjars (compileClasspath) doesn't include every transitive class of
# the IDE/Terminal plugin, so ProGuard would otherwise warn about unresolved references.
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.annotations.**
-dontwarn com.intellij.**
-dontwarn org.jetbrains.**
-dontwarn com.google.**
-dontwarn org.commonmark.**
