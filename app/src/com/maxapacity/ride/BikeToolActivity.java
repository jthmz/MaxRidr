package com.maxapacity.ride;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;

/** The website owns the catalog and gearing math. Keep its bike tools together in the app. */
public final class BikeToolActivity extends Activity {
    static final String PICKER = "/picker";
    static final String SPROCKET = "/tools/sprocket-calculator";
    private WebView page;

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
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        root.addView(progress, new LinearLayout.LayoutParams(-1, Ui.dp(this, 3)));
        page = new WebView(this);
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
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri destination = request.getUrl();
                if (onSite(destination)) return false;
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
                if (request.isForMainFrame()) retry.setVisibility(View.VISIBLE);
            }
        });
        setContentView(root);
        if (state == null || page.restoreState(state) == null) page.loadUrl(Site.HOME + path);
    }

    static boolean onSite(Uri uri) {
        return "https".equals(uri.getScheme()) && ("maxapacitybikes.com".equalsIgnoreCase(uri.getHost())
                || "www.maxapacitybikes.com".equalsIgnoreCase(uri.getHost()));
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (page != null) page.saveState(state);
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
