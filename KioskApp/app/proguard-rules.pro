# ─── WebView JS Bridge ────────────────────────────────────────────────────────
# Keep all @JavascriptInterface methods — ProGuard must not rename or strip them
# or window.KioskBridge.* calls from JS will silently fail.
-keepclassmembers class com.campsite.kiosk.JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# ─── Kotlin metadata ─────────────────────────────────────────────────────────
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod

# ─── Stripe Terminal (Steps 4–7) ─────────────────────────────────────────────
-keep class com.stripe.stripeterminal.** { *; }

# ─── Crash reporting (add your SDK keep rules here) ──────────────────────────
# -keep class com.google.firebase.crashlytics.** { *; }