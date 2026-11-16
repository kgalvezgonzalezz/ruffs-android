# El puente nativo se llama desde JavaScript por nombre: no renombrar ni eliminar.
-keepclassmembers class com.ruffs.companion.NativeBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
