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

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
    // {dataUrl, filename} for a shared photo — read into memory up front
    // (see readSharedImage) since a content:// URI from another app is only
    // guaranteed valid for the lifetime of this intent, and the JS side has
    // no way to resolve one at all.
    private String[] pendingSharedImage;

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
                // The window may already have focus by the time a cold
                // launch's page finishes loading, in which case
                // onWindowFocusChanged(true) below already fired once with
                // pageReady still false — too early to do anything. Without
                // this explicit check here too, a fresh launch would then
                // never get the one check that matters most (you copied
                // something, then opened the app for it). Skipped when a
                // Share just delivered content on this same launch —
                // surfacing an unrelated "paste this?" banner right on top
                // of content the user just deliberately shared in would
                // read as noise, not help.
                boolean hadShare = pendingSharedText != null || pendingSharedImage != null;
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

                // Not params.createIntent(): its default implementation sets
                // the intent's single `type` to the first accept-types entry
                // verbatim (here, "text/plain") rather than the wildcard
                // "*/*" whenever there's more than one type — observed live
                // via `dumpsys activity activities` on a real device. Some
                // document-provider pickers use that concrete `type` to
                // narrow matching beyond EXTRA_MIME_TYPES, which silently
                // disabled markdown files even though EXTRA_MIME_TYPES
                // already listed their MIME type correctly. Building the
                // intent ourselves keeps EXTRA_MIME_TYPES but forces the
                // wildcard type, which fixed it in on-device testing.
                android.util.Log.d("WordBeat", "file chooser accept types: "
                        + java.util.Arrays.toString(params.getAcceptTypes()));
                Intent chooserIntent = new Intent(Intent.ACTION_GET_CONTENT);
                chooserIntent.addCategory(Intent.CATEGORY_OPENABLE);
                chooserIntent.setType("*/*");
                chooserIntent.putExtra(Intent.EXTRA_MIME_TYPES, params.getAcceptTypes());

                try {
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST);
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
     * copy/paste — arrives as an ACTION_SEND intent carrying either one
     * plain-text string or, for a photo, a content:// URI in EXTRA_STREAM.
     * Text is handed to handleSharedText, not handlePastedText directly:
     * sharing a bare web link (the common case from a browser's Share
     * sheet) should fetch and read that page, not read the URL string
     * aloud as text — handleSharedText tells the two apart and only falls
     * through to the identical Markdown/table detection paste already uses
     * when it isn't a link.
     */
    private void handleIncomingIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;

        String type = intent.getType();
        if (type != null && type.startsWith("image/")) {
            Uri imageUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
            if (imageUri == null) return;
            pendingSharedImage = readSharedImage(imageUri, type);
            if (pendingSharedImage != null) deliverPendingShare();
            return;
        }

        String text = intent.getStringExtra(Intent.EXTRA_TEXT);
        if (text == null || text.isEmpty()) return;
        pendingSharedText = text;
        deliverPendingShare();
    }

    /**
     * Reads the shared photo into memory as a base64 data URL up front,
     * rather than handing the JS side the content:// URI itself — WebView's
     * JS has no way to resolve a content:// URI (it isn't http(s) or a
     * bundled asset), and the URI's read grant is only guaranteed to last
     * for the lifetime of this intent, not until whenever the page gets
     * around to asking for it. A phone photo is a few MB at most, well
     * within what evaluateJavascript can carry as one string argument.
     */
    private String[] readSharedImage(Uri uri, String mimeType) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);

            String base64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP);
            String dataUrl = "data:" + mimeType + ";base64," + base64;
            // The real filename isn't worth an extra ContentResolver query
            // for — index.html only inspects the extension (falling back to
            // the MIME type either way) to decide whether it can import a
            // file, and shows this name only briefly in a toast.
            String ext = mimeType.contains("png") ? "png" : "jpg";
            return new String[]{ dataUrl, "shared-photo." + ext };
        } catch (Exception e) {
            return null;
        }
    }

    private void deliverPendingShare() {
        if (!pageReady) return;

        if (pendingSharedImage != null) {
            String[] image = pendingSharedImage;
            pendingSharedImage = null;
            web.evaluateJavascript("handleSharedImage(" + JSONObject.quote(image[0])
                    + "," + JSONObject.quote(image[1]) + ")", null);
            return;
        }

        if (pendingSharedText == null) return;
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
     * Deliberately NOT triggered from onResume(): onResume() runs before the
     * window has actually regained input focus when switching back from
     * another app, and ClipboardManager.getPrimaryClip() can silently return
     * a stale or empty clip for a window that isn't focused yet — the app
     * would work once, right after a cold launch (onPageFinished's own load
     * time incidentally gives the window time to gain focus first), then
     * appear to stop noticing new clipboard content on every later return,
     * because the read kept losing that race. onWindowFocusChanged(true) is
     * the actual, documented signal that focus has landed. Checked every
     * time focus is (re)gained, not just a cold launch — checkClipboardOnResume()
     * only ever offers a paste, never performs one on its own, so there is
     * nothing destructive about asking often; the page itself decides
     * whether anything has actually changed since the last time it asked.
     */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && pageReady) web.evaluateJavascript("checkClipboardOnResume()", null);
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
