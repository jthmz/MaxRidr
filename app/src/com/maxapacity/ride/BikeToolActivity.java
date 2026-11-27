package com.maxapacity.ride;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.webkit.WebResourceResponse;
import java.io.IOException;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

/** Local gearing calculator and the live, bike-specific parts catalog. */
public final class BikeToolActivity extends Activity {
    static final String PICKER = "/picker";
    static final String SPROCKET = "/tools/sprocket-calculator";
    private static final String ASSET_HOST = "appassets.androidplatform.net";
    static final String LOCAL_GEARING = "https://" + ASSET_HOST + "/gearing/index.html";

    private String calculatorUrl() {
        String key = InstalledGearing.contextKey(this);
        if (key.equals("manual")) return LOCAL_GEARING;
        return LOCAL_GEARING + "#" + Uri.encode(key, ":");
    }
    private WebView page;
    private String gearingContext;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String path = getIntent().getStringExtra("path");
        if (!PICKER.equals(path) && !SPROCKET.equals(path)) {
            finish();
            return;
        }
        LinearLayout root = Ui.column(this);
        root.setBackgroundColor(Ui.BG);
        root.setFitsSystemWindows(true);
        View done = Ui.button(this, "Back to MaxRidr", Icons.X, Ui.SECONDARY);
        done.setOnClickListener(v -> finish());
        root.addView(done, Ui.matchWrap());
        String pickerPath = PICKER.equals(path) ? PickerBike.path(this) : PICKER;
        if (!PICKER.equals(pickerPath)) {
            View choose = Ui.button(this, "Choose another bike", Icons.SLIDERS_HORIZONTAL, Ui.SECONDARY);
            choose.setOnClickListener(v -> page.loadUrl(Site.HOME + PICKER));
            root.addView(choose, Ui.matchWrap());
        }
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));
        page = new WebView(this);
        boolean calculator = SPROCKET.equals(path);
        if (calculator) {
            page.addJavascriptInterface(new InstalledGearing(this), "RideGearing");
        }
        page.setBackgroundColor(Ui.BG);
        page.getSettings().setJavaScriptEnabled(true);
        page.getSettings().setDomStorageEnabled(true);
        page.getSettings().setAllowFileAccess(false);
        page.getSettings().setAllowContentAccess(false);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1));
        View retry = Ui.button(this, "Page unavailable · Retry", Icons.REFRESH_CW, Ui.SECONDARY);
        root.addView(retry, Ui.matchWrap());
        retry.setVisibility(View.GONE);
        retry.setOnClickListener(v -> page.reload());
        page.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value);
                progress.setVisibility(value < 100 ? View.VISIBLE : View.GONE);
            }
        });
        page.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (!ASSET_HOST.equals(uri.getHost())) {
                    return calculator ? new WebResourceResponse("text/plain", "utf-8", null) : null;
                }
                String file = uri.getPath();
                String type;
                if ("/gearing/index.html".equals(file)) type = "text/html";
                else if ("/gearing/calculator.js".equals(file)) type = "application/javascript";
                else if ("/fonts/SpaceGrotesk.ttf".equals(file) || "/fonts/JetBrainsMono.ttf".equals(file)) type = "font/ttf";
                else return new WebResourceResponse("text/plain", "utf-8", null);
                try {
                    WebResourceResponse response = new WebResourceResponse(type, type.equals("font/ttf") ? null : "utf-8",
                            getAssets().open(file.substring(1)));
                    if (calculator) response.setResponseHeaders(java.util.Collections.singletonMap(
                            "Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-src 'none'; object-src 'none'"));
                    return response;
                } catch (IOException missing) {
                    return new WebResourceResponse("text/plain", "utf-8", null);
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri destination = request.getUrl();
                if (!SPROCKET.equals(path) && onSite(destination)) return false;
                if ("https".equals(destination.getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, destination)); }
                    catch (ActivityNotFoundException e) {
                        Toast.makeText(BikeToolActivity.this, "A browser is needed for that link", Toast.LENGTH_SHORT).show();
                    }
                }
                return true;
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                retry.setVisibility(View.GONE);
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (!request.isForMainFrame()) return;
                if (onSite(request.getUrl()) && SPROCKET.equals(request.getUrl().getPath())) {
                    String query = request.getUrl().getEncodedQuery();
                    view.loadUrl(Uri.parse(calculatorUrl()).buildUpon().encodedQuery(query).build().toString());
                } else {
                    retry.setVisibility(View.VISIBLE);
                }
            }
        });
        setContentView(root);
        if (SPROCKET.equals(path)) {
            gearingContext = calculatorUrl();
            page.loadUrl(gearingContext);
        } else if (state == null || page.restoreState(state) == null) {
            page.loadUrl(Site.HOME + pickerPath);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (gearingContext != null) {
            String current = calculatorUrl();
            if (!current.equals(gearingContext)) {
                gearingContext = current;
                page.loadUrl(current);
            }
        }
    }

    static boolean onSite(Uri uri) {
        return "https".equals(uri.getScheme()) && ("maxapacitybikes.com".equalsIgnoreCase(uri.getHost())
                || "www.maxapacitybikes.com".equalsIgnoreCase(uri.getHost()));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (page != null && gearingContext == null) page.saveState(state);
    }

    @Override public void onBackPressed() {
        if (page != null && page.canGoBack()) page.goBack();
        else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (page != null) {
            ((LinearLayout) page.getParent()).removeView(page);
            page.destroy();
        }
        super.onDestroy();
    }
}
