-keepclasseswithmembernames class * {
    native <methods>;
}

-keep class os.deckbridge.android.NativeEngine { *; }
-keep class os.deckbridge.logic.ControllerCore { *; }

-keep class net.zetetic.database.** { *; }
-keep class net.zetetic.database.sqlcipher.** { *; }
-dontwarn net.zetetic.**

-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**
