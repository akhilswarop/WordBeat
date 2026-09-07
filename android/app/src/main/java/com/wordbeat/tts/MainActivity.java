package com.wordbeat.tts;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import android.graphics.Insets;
import android.os.Build;
import android.view.WindowInsets;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Hosts the web app and wires the native TTS bridge into it.
 *
 * The page is loaded from assets rather than the network, so the app works
 * offline and ships the exact HTML in this repository.
 */
public class MainActivity extends Activity {

    private WebView web;
    private TtsBridge bridge;

    /*
     * A plain WebView has no file-chooser UI of its own — that is Chrome's
     * behaviour, not the WebView component's. Without a WebChromeClient
     * implementing onShowFileChooser, clicking the page's <input
     * type="file"> does nothing at all, which is exactly the symptom this
     * fixes. Using the pre-androidx Activity.startActivityForResult here
     * rather than the newer Activity Result API, consistent with the rest
     * of this project's choice to depend on nothing beyond the platform SDK.
     */
    private ValueCallback<Uri[]> pendingFileChoice;
    private static final int FILE_CHOOSER_REQUEST = 51;

    /*
     * Insets usually arrive before the page has finished loading, so the
     * script is held here and replayed in onPageFinished. Without that the
     * first layout renders under the status bar until something else
     * triggers a fresh inset pass.
     */
    private String insetScript;

    /*
     * A share from another app's own Share button arrives as an intent, not
     * a DOM event, and can arrive before the page has finished loading (cold
     * start) or after (the app was already open). Held here and flushed once
     * onPageFinished confirms handlePastedText actually exists to call.
     */
    private boolean pageReady;
    private String pendingSharedText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // API 35 enforces edge-to-edge and ignores fitsSystemWindows; the
        // window must explicitly say it is handling insets itself, or the
        // listener below still fires but the content behind it is wrong.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
        }

        web = new WebView(this);

        /*
         * Debug builds only: exposes the WebView to Chrome DevTools over adb,
         * which is the only practical way to see console errors or inspect
         * the rendered DOM on a device. Gated on the debuggable flag so a
         * release APK never opens the inspector.
         */
        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        // The page never reads local files itself; only the asset loader does.
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);

        /*
         * A JavaScript interface is exposed on this WebView, so any page
         * loaded into it can reach the bridge. Navigation is therefore pinned
         * to the bundled asset — anything else is refused rather than opened
         * in place.
         */
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                return !url.startsWith("file:///android_asset/");
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (insetScript != null) view.evaluateJavascript(insetScript, null);
                pageReady = true;
                // onResume() already fired once before this, on the cold-start
                // path — too early for pageReady to be true yet, so a fresh
                // launch would otherwise never get the one check that matters
                // most (you copied something, then opened the app for it).
                // Skipped when a Share just delivered content on this same
                // launch — surfacing an unrelated "paste this?" banner right
                // on top of content the user just deliberately shared in
                // would read as noise, not help.
                boolean hadShare = pendingSharedText != null;
                deliverPendingShare();
                if (!hadShare) view.evaluateJavascript("checkClipboardOnResume()", null);
            }
        });

        /*
         * The page is laid out edge to edge so its backgrounds reach the
         * screen edges, and the system bar sizes are handed to CSS instead
         * of being applied as view padding. That keeps the paper and the
         * player painting behind the status and gesture bars while their
         * content stays clear of them.
         */
        web.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            int top, bottom, left, right;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = bars.top;
                bottom = bars.bottom;
                left = bars.left;
                right = bars.right;
            } else {
                // Pre-30 has no typed insets; the deprecated accessors are the
                // only option and already fold the cutout into the top inset.
                top = windowInsets.getSystemWindowInsetTop();
                bottom = windowInsets.getSystemWindowInsetBottom();
                left = windowInsets.getSystemWindowInsetLeft();
                right = windowInsets.getSystemWindowInsetRight();
            }

            float density = getResources().getDisplayMetrics().density;

            insetScript = String.format(Locale.US,
                    "(function(s){" +
                    "s.setProperty('--safe-top','%.2fpx');" +
                    "s.setProperty('--safe-bottom','%.2fpx');" +
                    "s.setProperty('--safe-left','%.2fpx');" +
                    "s.setProperty('--safe-right','%.2fpx');" +
                    "})(document.documentElement.style)",
                    top / density, bottom / density,
                    left / density, right / density);

            ((WebView) view).evaluateJavascript(insetScript, null);
            return windowInsets;
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback,
                                              FileChooserParams params) {
                // A second chooser request while one is already pending would
                // otherwise leak the first callback; the contract requires
                // every callback to be resolved exactly once.
                if (pendingFileChoice != null) pendingFileChoice.onReceiveValue(null);
                pendingFileChoice = callback;

                try {
                    startActivityForResult(params.createIntent(), FILE_CHOOSER_REQUEST);
                } catch (ActivityNotFoundException e) {
                    pendingFileChoice = null;
                    return false;
                }
                return true;
            }
        });

        bridge = new TtsBridge(web, this);
        web.addJavascriptInterface(bridge, "AndroidTTS");

        web.loadUrl("file:///android_asset/index.html");
        setContentView(web);

        handleIncomingIntent(getIntent());
    }

    /*
     * android:launchMode="singleTask" routes a share into the already-running
     * instance here instead of spawning a second Activity — without it, the
     * app would silently duplicate itself every time something was shared to
     * it while already open.
     */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    /**
     * A share from another app's own Share button — as opposed to a
     * copy/paste — arrives as an ACTION_SEND intent carrying one plain-text
     * string. Handed to handleSharedText, not handlePastedText directly:
     * sharing a bare web link (the common case from a browser's Share
     * sheet) should fetch and read that page, not read the URL string
     * aloud as text — handleSharedText tells the two apart and only falls
     * through to the identical Markdown/table detection paste already uses
     * when it isn't a link.
     */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.isEmpty()) return;
        pendingSharedText = text;
        deliverPendingShare();
    }

    private void deliverPendingShare() {
        if (!pageReady || pendingSharedText == null) return;
        String text = pendingSharedText;
        pendingSharedText = null;
        // JSONObject.quote wraps the string as a JSON string literal —
        // already valid JS syntax, and unlike hand-rolled escaping it
        // handles quotes, backslashes, and newlines correctly.
        web.evaluateJavascript("handleSharedText(" + JSONObject.quote(text) + ")", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || pendingFileChoice == null) return;
        pendingFileChoice.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(resultCode, data));
        pendingFileChoice = null;
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    /*
     * Checked on every return to the foreground, not just a cold launch —
     * checkClipboardOnResume() only ever offers a paste, never performs one
     * on its own, so there is nothing destructive about asking often. The
     * page itself decides whether anything has actually changed since the
     * last time it asked.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady) web.evaluateJavascript("checkClipboardOnResume()", null);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Speech should not keep running once the app leaves the foreground.
        if (bridge != null) bridge.stop();
    }

    @Override
    protected void onDestroy() {
        if (bridge != null) bridge.shutdown();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
