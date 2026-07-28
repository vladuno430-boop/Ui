# The game is JavaScript; there is nothing on the Kotlin side worth obfuscating.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
