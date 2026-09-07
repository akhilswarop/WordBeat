package com.wordbeat.tts;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Bridges Android's native TextToSpeech to the web app's engine interface.
 *
 * The whole reason this class exists: Android WebView does not implement the
 * Web Speech API, so the browser engine the app normally uses is simply
 * absent here. What Android offers instead is better for our purposes —
 * {@link UtteranceProgressListener#onRangeStart} reports the character range
 * of each word as it is spoken, which is exactly the timing track the
 * renderer consumes. The JS side treats this class as just another producer.
 *
 * Offsets reported by onRangeStart are relative to the utterance text, the
 * same contract as the Web Speech API's charIndex, so the JS engine adds the
 * chunk's absolute offset the same way in both cases.
 */
public class TtsBridge {

    private final WebView web;
    private final Context context;
    // Not final: the init callback below reads this field from inside the
    // same expression that assigns it. The callback only ever fires
    // asynchronously after the constructor returns, so tts is always set by
    // then, but javac's definite-assignment check for blank finals can't see
    // that and refuses to compile it as final.
    private TextToSpeech tts;
    private volatile boolean ready = false;

    public TtsBridge(WebView web, Context context) {
        this.web = web;
        this.context = context;
        this.tts = new TextToSpeech(context, status -> {
            ready = status == TextToSpeech.SUCCESS;
            if (ready) {
                tts.setLanguage(Locale.getDefault());
                tts.setOnUtteranceProgressListener(listener);
            }
            emit("ready", "{\"ok\":" + ready + "}");
        });
    }

    private final UtteranceProgressListener listener = new UtteranceProgressListener() {
        @Override
        public void onStart(String utteranceId) {
            emit("start", idPayload(utteranceId));
        }

        @Override
        public void onDone(String utteranceId) {
            emit("done", idPayload(utteranceId));
        }

        @Override
        public void onError(String utteranceId) {
            emit("error", idPayload(utteranceId));
        }

        /** API 26+. start and end are character offsets within this utterance. */
        @Override
        public void onRangeStart(String utteranceId, int start, int end, int frame) {
            emit("range", "{\"id\":\"" + escape(utteranceId) + "\",\"start\":" + start
                    + ",\"end\":" + end + "}");
        }
    };

    /**
     * Utterance ids are numeric strings this class generates, and payload
     * values are ints, so nothing user-supplied is ever interpolated into JS.
     */
    private void emit(String type, String payloadJson) {
        final String js = "window.__androidTts && window.__androidTts.on('"
                + type + "', " + payloadJson + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private static String idPayload(String id) {
        return "{\"id\":\"" + escape(id) + "\"}";
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @JavascriptInterface
    public boolean isReady() {
        return ready;
    }

    /**
     * Queues one chunk. The JS side queues every chunk up front and lets the
     * platform run the queue, using the utterance id to map a range callback
     * back to the chunk it came from.
     */
    @JavascriptInterface
    public void speak(String text, String utteranceId, float rate) {
        if (!ready) return;
        tts.setSpeechRate(rate);
        tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), utteranceId);
    }

    @JavascriptInterface
    public void stop() {
        if (ready) tts.stop();
    }

    /**
     * Android's TextToSpeech has no pause. The JS engine implements pause by
     * stopping here and re-speaking from the last reported word on resume,
     * which is why the engine tracks its own position.
     */
    @JavascriptInterface
    public String voices() {
        JSONArray out = new JSONArray();
        if (!ready) return out.toString();
        try {
            Set<Voice> available = tts.getVoices();
            if (available == null) return out.toString();
            for (Voice v : available) {
                JSONObject o = new JSONObject();
                o.put("name", v.getName());
                o.put("locale", v.getLocale().toString());
                o.put("network", v.isNetworkConnectionRequired());
                out.put(o);
            }
        } catch (Exception ignored) {
            // Some OEM engines throw when enumerating; an empty list is the
            // honest answer and the app falls back to the default voice.
        }
        return out.toString();
    }

    @JavascriptInterface
    public boolean setVoice(String name) {
        if (!ready) return false;
        try {
            for (Voice v : tts.getVoices()) {
                if (v.getName().equals(name)) {
                    return tts.setVoice(v) == TextToSpeech.SUCCESS;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * A plain WebView never shows the clipboard-read permission prompt
     * Chrome does — there is no infobar UI for it to appear in — so
     * navigator.clipboard.readText() just rejects unconditionally here,
     * with no OS-level fallback like a desktop Ctrl+V. Reading through
     * Android's own ClipboardManager sidesteps the web permission entirely,
     * the same fix as speech synthesis: bridge to the platform API instead
     * of waiting on a web capability WebView doesn't implement.
     */
    @JavascriptInterface
    public String readClipboardText() {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence text = clip.getItemAt(0).coerceToText(context);
            return text == null ? "" : text.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Android's ClipData carries an HTML representation alongside its plain
     * text the same way a desktop clipboard does (ClipData.newHtmlText is a
     * standard Android API, and most apps that copy rich content — ChatGPT
     * included — use it). readClipboardText() above only ever coerced the
     * plain-text fallback, which many apps flatten to bare prose with no
     * Markdown syntax left in it at all, so nothing was ever left for this
     * app's own Markdown detection to find. This is the missing other half.
     */
    @JavascriptInterface
    public String readClipboardHtml() {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return "";
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence html = clip.getItemAt(0).getHtmlText();
            return html == null ? "" : html.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static final int FETCH_TIMEOUT_MS = 15000;
    // A generous cap for an article page, not a general-purpose download
    // limit — this exists so a mistaken link to a huge file can't hang the
    // app or blow through memory, not to police legitimate pages.
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024;
    private final ExecutorService fetchExecutor = Executors.newSingleThreadExecutor();

    /**
     * Fetches a URL's raw HTML natively, bypassing the browser's CORS wall
     * entirely — CORS is a JS-in-a-browser restriction, not a limit on a
     * native HTTP request, the same reason readClipboardText() above
     * sidesteps the WebView clipboard-permission wall instead of trying to
     * work around it in JS. Deliberately asynchronous, unlike the clipboard
     * methods: those are instant local OS calls, but a network request can
     * take seconds, and a @JavascriptInterface method blocks the page's JS
     * until it returns, so a synchronous version here would freeze the app.
     * The result comes back later via window.onUrlFetched(url, html, error).
     */
    @JavascriptInterface
    public void fetchUrl(String urlString) {
        fetchExecutor.execute(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(urlString);
                String protocol = url.getProtocol();
                if (!"http".equals(protocol) && !"https".equals(protocol)) {
                    callback(urlString, null, "Only http/https links are supported.");
                    return;
                }

                conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(FETCH_TIMEOUT_MS);
                conn.setReadTimeout(FETCH_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(true);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; WordBeat/1.0)");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml");

                int code = conn.getResponseCode();
                if (code < 200 || code >= 300) {
                    callback(urlString, null, "The page returned an error (HTTP " + code + ").");
                    return;
                }

                String contentType = conn.getContentType();
                if (contentType != null && !contentType.toLowerCase(Locale.US).contains("html")) {
                    callback(urlString, null, "That link isn't a web page — it looks like " + contentType + ".");
                    return;
                }

                byte[] bytes = readWithLimit(conn.getInputStream(), MAX_RESPONSE_BYTES);
                String charset = extractCharset(contentType);
                String html = new String(bytes, charset != null ? charset : "UTF-8");
                callback(urlString, html, null);
            } catch (Exception e) {
                String message = e.getMessage();
                callback(urlString, null, "Couldn't reach that page" + (message != null ? ": " + message : "."));
            } finally {
                if (conn != null) conn.disconnect();
            }
        });
    }

    private void callback(String url, String html, String error) {
        String js = "window.onUrlFetched && window.onUrlFetched("
                + JSONObject.quote(url) + ","
                + (html != null ? JSONObject.quote(html) : "null") + ","
                + (error != null ? JSONObject.quote(error) : "null") + ")";
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private static String extractCharset(String contentType) {
        if (contentType == null) return null;
        for (String part : contentType.split(";")) {
            String trimmed = part.trim();
            if (trimmed.toLowerCase(Locale.US).startsWith("charset=")) {
                return trimmed.substring(8).trim().replace("\"", "");
            }
        }
        return null;
    }

    private static byte[] readWithLimit(InputStream in, int limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0, n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > limit) throw new IOException("That page is too large to fetch.");
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    void shutdown() {
        try {
            tts.stop();
            tts.shutdown();
        } catch (Exception ignored) {
        }
        fetchExecutor.shutdownNow();
    }
}
