# R8 rules for the minified release build (isMinifyEnabled = true, isShrinkResources = true).
# OkHttp ships its own consumer rules inside its jar; osmdroid and protobuf-java ship none, so they
# are kept conservatively here rather than debugging an obfuscated crash after the fact.

# GTFS-Realtime generated protobuf classes (Atac/Roma TPL vehicle positions + trip updates).
-keep class com.google.transit.realtime.** { *; }

# protobuf-java (full runtime) looks up classes/accessors reflectively, e.g. ExtensionRegistry.
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# osmdroid does tile-provider / configuration reflection and has no consumer rules of its own.
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**
