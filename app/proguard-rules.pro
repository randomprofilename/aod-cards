# LSPosed loads this class by name from META-INF/xposed/java_init.list.
-keep class com.aodcards.xposed.AodTouchModule { *; }
# These compile-only classes are supplied by the framework at runtime.
-dontwarn io.github.libxposed.api.**
-dontwarn io.github.libxposed.annotation.**
