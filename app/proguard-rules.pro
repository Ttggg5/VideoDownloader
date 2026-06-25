# Keep the JavaScript bridge so injected page scripts can call back into the app.
-keepclassmembers class com.vdbrowser.app.MainActivity$JsBridge {
    public *;
}
-keepattributes JavascriptInterface
