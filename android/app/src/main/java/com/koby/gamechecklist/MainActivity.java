package com.koby.gamechecklist;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.webkit.WebViewAssetLoader;

import com.google.mlkit.vision.barcode.common.Barcode;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanner;
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions;
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning;

import org.json.JSONArray;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import java.io.BufferedReader;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends AppCompatActivity {
    private WebView web;
    private final ExecutorService artQ = Executors.newSingleThreadExecutor();
    private SharedPreferences sec() { return getSharedPreferences("igdb", MODE_PRIVATE); }

    private File remoteDir() {
        File d = new File(getFilesDir(), "remote");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private SharedPreferences ghp() { return getSharedPreferences("gh", MODE_PRIVATE); }

    private static long stamp(java.io.InputStream in) {
        try {
            byte[] buf = new byte[4096];
            int n = in.read(buf);
            in.close();
            if (n <= 0) return 0;
            Matcher m = Pattern.compile("gc-build:(\\d+)").matcher(new String(buf, 0, n, "UTF-8"));
            return m.find() ? Long.parseLong(m.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String startUrl() {
        String base = "https://appassets.androidplatform.net/";
        try {
            File f = new File(remoteDir(), "index.html");
            if (f.exists() && stamp(new java.io.FileInputStream(f)) >= stamp(getAssets().open("index.html")))
                return base + "remote/index.html";
        } catch (Exception e) { /* fall back to bundled */ }
        return base + "assets/index.html";
    }

    private String[] ghRequest(String method, String url, String accept, String body) throws Exception {
        SharedPreferences g = ghp();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(10000);
        c.setReadTimeout(30000);
        if (!g.getString("tok", "").isEmpty()) c.setRequestProperty("Authorization", "Bearer " + g.getString("tok", ""));
        c.setRequestProperty("Accept", accept == null || accept.isEmpty() ? "application/vnd.github+json" : accept);
        c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
        c.setRequestProperty("User-Agent", "ChamberlaCollector");
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes("UTF-8"));
            os.close();
        }
        int code = c.getResponseCode();
        java.io.InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            in.close();
        }
        return new String[]{String.valueOf(code), bo.toString("UTF-8")};
    }

    private static final String PUB_BASE = "https://api.github.com/repos/FantaFreak04/chamberla-collector-app/";

    private String updBase() {
        SharedPreferences g = ghp();
        boolean own = !g.getString("tok", "").isEmpty() && !g.getString("owner", "").isEmpty();
        return own ? ghBase() : PUB_BASE;
    }

    private String ghBase() {
        SharedPreferences g = ghp();
        return "https://api.github.com/repos/" + g.getString("owner", "") + "/" + g.getString("repo", "") + "/";
    }

    private static final String UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36";
    private static final Pattern ROW = Pattern.compile(
            "<td class=\"title\"[^>]*>\\s*<a[^>]*>(.*?)</a>.*?used_price\">\\s*<span class=\"js-price\">(.*?)</span>.*?cib_price\">\\s*<span class=\"js-price\">(.*?)</span>.*?new_price\">\\s*<span class=\"js-price\">(.*?)</span>",
            Pattern.DOTALL);

    private static JSONArray row(String name, String a, String b, String c) throws Exception {
        JSONArray r = new JSONArray();
        r.put(android.text.Html.fromHtml(name, 0).toString().trim());
        for (String v : new String[]{a, b, c}) {
            String n = v == null ? "" : v.replaceAll("[^0-9.]", "");
            if (n.isEmpty()) r.put(JSONObject.NULL); else r.put(Double.parseDouble(n));
        }
        return r;
    }

    private static String read(HttpURLConnection c) throws Exception {
        java.io.InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        BufferedReader r = new BufferedReader(new InputStreamReader(in));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) sb.append(line);
        return sb.toString();
    }

    private String token() throws Exception {
        SharedPreferences p = sec();
        long now = System.currentTimeMillis();
        String t = p.getString("tok", "");
        if (!t.isEmpty() && p.getLong("exp", 0) > now + 60000) return t;
        String id = p.getString("id", ""), sc = p.getString("sec", "");
        if (id.isEmpty() || sc.isEmpty()) throw new Exception("no credentials");
        URL u = new URL("https://id.twitch.tv/oauth2/token?client_id=" + java.net.URLEncoder.encode(id, "UTF-8")
                + "&client_secret=" + java.net.URLEncoder.encode(sc, "UTF-8") + "&grant_type=client_credentials");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setRequestMethod("POST");
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        JSONObject j = new JSONObject(read(c));
        if (!j.has("access_token")) throw new Exception("bad credentials");
        t = j.getString("access_token");
        p.edit().putString("tok", t).putLong("exp", now + j.optLong("expires_in", 3600) * 1000L).apply();
        return t;
    }

    private android.webkit.ValueCallback<android.net.Uri[]> fileCb;

    @Override
    protected void onActivityResult(int req, int res, android.content.Intent data) {
        if (req == 4711) {
            android.net.Uri[] r = null;
            if (res == RESULT_OK && data != null && data.getData() != null) r = new android.net.Uri[]{data.getData()};
            if (fileCb != null) fileCb.onReceiveValue(r);
            fileCb = null;
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        web = new WebView(this);
        setContentView(web);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .addPathHandler("/remote/", new WebViewAssetLoader.InternalStoragePathHandler(this, remoteDir()))
                .build();
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, android.webkit.ValueCallback<android.net.Uri[]> cb, FileChooserParams p) {
                if (fileCb != null) fileCb.onReceiveValue(null);
                fileCb = cb;
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT);
                i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(android.content.Intent.createChooser(i, "Choose your GAMEYE export (.csv)"), 4711);
                } catch (Exception e) {
                    fileCb = null;
                    return false;
                }
                return true;
            }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return loader.shouldInterceptRequest(r.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                android.net.Uri u = r.getUrl();
                if ("appassets.androidplatform.net".equals(u.getHost())) return false;
                try {
                    startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, u));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "No app to open this link", Toast.LENGTH_SHORT).show();
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "Android");
        web.loadUrl(startUrl());
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    private void js(final String code) {
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private class Bridge {
        @JavascriptInterface
        public boolean hasIgdb() {
            return !sec().getString("id", "").isEmpty() && !sec().getString("sec", "").isEmpty();
        }

        @JavascriptInterface
        public void setIgdb(String id, String secret) {
            sec().edit().putString("id", id.trim()).putString("sec", secret.trim()).remove("tok").remove("exp").apply();
        }

        @JavascriptInterface
        public void clearIgdb() {
            sec().edit().clear().apply();
        }

        @JavascriptInterface
        public void libList(final String system) {
            new Thread(() -> {
                String out = "";
                String err = "";
                try {
                    File dir = new File(getFilesDir(), "lib");
                    dir.mkdirs();
                    File f = new File(dir, system.replaceAll("[^A-Za-z0-9]", "_") + ".txt");
                    if (f.exists() && f.length() > 0) {
                        java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
                        java.io.InputStream in = new java.io.FileInputStream(f);
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                        in.close();
                        out = bo.toString("UTF-8");
                    } else {
                        String u = "https://thumbnails.libretro.com/" + java.net.URLEncoder.encode(system, "UTF-8").replace("+", "%20") + "/Named_Boxarts/";
                        HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(40000);
                        c.setRequestProperty("User-Agent", UA);
                        String html = read(c);
                        if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
                        StringBuilder sb = new StringBuilder();
                        Matcher m = Pattern.compile("href=\"([^\"]+?)\\.png\"").matcher(html);
                        while (m.find()) {
                            String name = java.net.URLDecoder.decode(m.group(1).replace("+", "%2B"), "UTF-8");
                            sb.append(name).append('\n');
                        }
                        out = sb.toString();
                        if (out.isEmpty()) throw new Exception("empty list");
                        FileOutputStream fo = new FileOutputStream(f);
                        fo.write(out.getBytes("UTF-8"));
                        fo.close();
                    }
                } catch (Exception e) {
                    err = String.valueOf(e.getMessage());
                    out = "";
                }
                js("window.onLib(" + JSONObject.quote(system) + "," + JSONObject.quote(out) + "," + JSONObject.quote(err) + ")");
            }).start();
        }

        @JavascriptInterface
        public void art(final String key, final String title) {
            artQ.execute(() -> {
                String out = "[]", err = "";
                try {
                    String q = "search \"" + title.replace("\"", " ").replace("\\", " ")
                            + "\"; fields name,summary,cover.image_id,platforms.name,aggregated_rating; limit 10;";
                    HttpURLConnection c = (HttpURLConnection) new URL("https://api.igdb.com/v4/games").openConnection();
                    c.setRequestMethod("POST");
                    c.setDoOutput(true);
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(10000);
                    c.setRequestProperty("Client-ID", sec().getString("id", ""));
                    c.setRequestProperty("Authorization", "Bearer " + token());
                    OutputStream os = c.getOutputStream();
                    os.write(q.getBytes("UTF-8"));
                    os.close();
                    int code = c.getResponseCode();
                    String body = read(c);
                    if (code == 401) sec().edit().remove("tok").remove("exp").apply();
                    if (code >= 400) err = "IGDB error " + code; else out = body;
                    Thread.sleep(260);
                } catch (Exception e) {
                    err = e.getMessage() == null ? "error" : e.getMessage();
                }
                js("window.onArt(" + JSONObject.quote(key) + "," + (err.isEmpty() ? out : "[]") + "," + JSONObject.quote(err) + ")");
            });
        }

        @JavascriptInterface
        public String ghInfo() {
            SharedPreferences g = ghp();
            try {
                JSONObject o = new JSONObject();
                o.put("owner", g.getString("owner", ""));
                o.put("repo", g.getString("repo", ""));
                o.put("has", !g.getString("tok", "").isEmpty() && !g.getString("owner", "").isEmpty());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void ghSet(String owner, String repo, String tok) {
            SharedPreferences.Editor e = ghp().edit().putString("owner", owner.trim()).putString("repo", repo.trim());
            if (tok != null && !tok.trim().isEmpty()) e.putString("tok", tok.trim());
            e.apply();
        }

        @JavascriptInterface
        public void ghClear() {
            ghp().edit().clear().apply();
        }

        @JavascriptInterface
        public void ghCall(final String id, final String method, final String path, final String accept, final String body) {
            new Thread(() -> {
                String code = "0", text = "";
                try {
                    String[] r = ghRequest(method, ghBase() + path, accept, body);
                    code = r[0];
                    text = r[1];
                } catch (Exception e) {
                    text = e.getMessage() == null ? "error" : e.getMessage();
                }
                js("window.onGh(" + JSONObject.quote(id) + "," + code + "," + JSONObject.quote(text) + ")");
            }).start();
        }

        @JavascriptInterface
        public void ghUpdate() {
            new Thread(() -> {
                String res = "same", msg = "";
                try {
                    String[] r = ghRequest("GET", updBase() + "commits?path=index.html&per_page=1", null, null);
                    if (!r[0].equals("200")) throw new Exception("GitHub " + r[0]);
                    JSONArray a = new JSONArray(r[1]);
                    if (a.length() == 0) throw new Exception("index.html not in repo yet");
                    String sha = a.getJSONObject(0).getString("sha");
                    File f = new File(remoteDir(), "index.html");
                    if (!sha.equals(ghp().getString("sha", "")) || !f.exists()) {
                        String[] d = ghRequest("GET", updBase() + "contents/index.html", "application/vnd.github.raw", null);
                        if (!d[0].equals("200") || d[1].length() < 100000 || !d[1].contains("</html>"))
                            throw new Exception("download failed (" + d[0] + ")");
                        File t = new File(remoteDir(), "index.html.tmp");
                        FileOutputStream o = new FileOutputStream(t);
                        o.write(d[1].getBytes("UTF-8"));
                        o.close();
                        if (f.exists()) f.delete();
                        t.renameTo(f);
                        ghp().edit().putString("sha", sha).apply();
                        res = "ready";
                    }
                } catch (Exception e) {
                    res = "err";
                    msg = e.getMessage() == null ? "error" : e.getMessage();
                }
                js("window.onUpdate(" + JSONObject.quote(res) + "," + JSONObject.quote(msg) + ")");
            }).start();
        }

        @JavascriptInterface
        public void reloadApp() {
            runOnUiThread(() -> web.loadUrl(startUrl()));
        }

        @JavascriptInterface
        public void prices(final String slug, final String plat) {
            new Thread(() -> {
                JSONArray out = new JSONArray();
                String err = "";
                try {
                    String base = "https://www.pricecharting.com/console/" + slug
                            + "?sort=name&exclude-hardware=true&exclude-variants=false";
                    HttpURLConnection c = (HttpURLConnection) new URL(base).openConnection();
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(20000);
                    c.setRequestProperty("User-Agent", UA);
                    String html = read(c);
                    if (c.getResponseCode() >= 400) throw new Exception("HTTP " + c.getResponseCode());
                    Matcher m = ROW.matcher(html);
                    while (m.find()) out.put(row(m.group(1), m.group(2), m.group(3), m.group(4)));
                    Matcher fm = Pattern.compile("<form[^>]*js-next-page[^>]*>(.*?)</form>", Pattern.DOTALL).matcher(html);
                    if (fm.find()) {
                        StringBuilder form = new StringBuilder();
                        String cursor = "";
                        Matcher im = Pattern.compile("<input[^>]*name=\"([^\"]*)\"[^>]*value=\"([^\"]*)\"").matcher(fm.group(1));
                        while (im.find()) {
                            if (im.group(1).equals("cursor")) { cursor = im.group(2); continue; }
                            if (im.group(1).equals("format")) continue;
                            form.append(im.group(1)).append('=').append(java.net.URLEncoder.encode(im.group(2), "UTF-8")).append('&');
                        }
                        int guard = 0;
                        while (!cursor.isEmpty() && guard++ < 80) {
                            HttpURLConnection pc = (HttpURLConnection) new URL(base).openConnection();
                            pc.setRequestMethod("POST");
                            pc.setDoOutput(true);
                            pc.setConnectTimeout(10000);
                            pc.setReadTimeout(20000);
                            pc.setRequestProperty("User-Agent", UA);
                            pc.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                            OutputStream os = pc.getOutputStream();
                            os.write((form + "cursor=" + cursor + "&format=json").getBytes("UTF-8"));
                            os.close();
                            String body = read(pc);
                            JSONObject j;
                            try { j = new JSONObject(body); } catch (Exception e) { Thread.sleep(4000); continue; }
                            JSONArray ps = j.optJSONArray("products");
                            if (ps == null || ps.length() == 0) break;
                            for (int i = 0; i < ps.length(); i++) {
                                JSONObject o = ps.getJSONObject(i);
                                out.put(row(o.optString("productName"), o.optString("price1"), o.optString("price2"), o.optString("price3")));
                            }
                            cursor = j.optString("cursor", "");
                            Thread.sleep(600);
                        }
                    }
                } catch (Exception e) {
                    err = e.getMessage() == null ? "error" : e.getMessage();
                }
                js("window.onPrices(" + JSONObject.quote(plat) + "," + out.toString() + "," + JSONObject.quote(err) + ")");
            }).start();
        }

        @JavascriptInterface
        public void scan() {
            runOnUiThread(() -> {
                GmsBarcodeScannerOptions o = new GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E,
                                Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8)
                        .build();
                GmsBarcodeScanner sc = GmsBarcodeScanning.getClient(MainActivity.this, o);
                sc.startScan()
                        .addOnSuccessListener(code -> {
                            String v = code.getRawValue();
                            if (v != null) js("window.onUpc(" + JSONObject.quote(v) + ")");
                        })
                        .addOnFailureListener(e -> Toast.makeText(MainActivity.this,
                                "Scan failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            });
        }

        @JavascriptInterface
        public void lookup(final String upc) {
            new Thread(() -> {
                String title = "";
                try {
                    URL u = new URL("https://api.upcitemdb.com/prod/trial/lookup?upc=" + upc);
                    HttpURLConnection c = (HttpURLConnection) u.openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line);
                    JSONObject j = new JSONObject(sb.toString());
                    if (j.has("items") && j.getJSONArray("items").length() > 0)
                        title = j.getJSONArray("items").getJSONObject(0).optString("title", "");
                } catch (Exception e) {
                    title = "";
                }
                js("window.onTitle(" + JSONObject.quote(upc) + "," + JSONObject.quote(title) + ")");
            }).start();
        }
    }
}
