# kotlinx.serialization: mantém serializers gerados
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers @kotlinx.serialization.Serializable class ** { *** Companion; }
-keepclasseswithmembers class **$$serializer { *; }
-keep,includedescriptorclasses class br.escritorio.central.**$$serializer { *; }
-keepclassmembers class br.escritorio.central.** { *** Companion; kotlinx.serialization.KSerializer serializer(...); }
