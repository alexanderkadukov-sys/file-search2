package com.ugraremkomp.openchat;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.net.http.SslError;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Small, isolated Android shell for the existing public OpenChat website.
 * No Supabase service credentials or native JavaScript bridge are exposed.
 * Private cross-messenger inboxes are intentionally NOT implemented here.
 */
public final class MainActivity extends Activity {
    private static final String HOME_URL =
            "https://alexanderkadukov-sys.github.io/file-search2/chat/";
    private static final String TRUSTED_HOST = "alexanderkadukov-sys.github.io";
    private static final String TRUSTED_PATH = "/file-search2/chat";
    private static final int FILE_CHOOSER_REQUEST = 1001;
    private static final int NAVY = Color.rgb(23, 33, 43);

    private WebView webView;
    private ProgressBar progress;
    private LinearLayout errorView;
    private ValueCallback<Uri[]> pendingFileCallback;
    private Uri pendingCameraUri;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(NAVY);
        getWindow().setNavigationBarColor(NAVY);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(NAVY);

        webView = new WebView(this);
        webView.setBackgroundColor(NAVY);
        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                Color.rgb(51, 144, 236)));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.TOP);
        root.addView(progress, progressParams);

        errorView = createErrorView();
        errorView.setVisibility(View.GONE);
        root.addView(errorView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        setupWebView();

        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(HOME_URL);
        }
    }

    private void setupWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true); // Required by the existing Supabase frontend.
        settings.setDomStorageEnabled(true); // Preserve nickname and dark/light theme.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true); // Android photo/document picker URI.
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setSupportMultipleWindows(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setGeolocationEnabled(false);
        webView.setWebContentsDebuggingEnabled(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri uri = request.getUrl();
                if (isTrusted(uri)) return false;
                openExternal(uri);
                return true;
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                errorView.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(
                    WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showLoadError("Не удаётся открыть OpenChat. Проверьте интернет.");
                }
            }

            @Override
            public void onReceivedSslError(
                    WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); // Never bypass certificate validation.
                showLoadError("Ошибка защищённого соединения.");
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                if (newProgress >= 100) progress.setVisibility(View.GONE);
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                if (pendingFileCallback != null) pendingFileCallback.onReceiveValue(null);
                pendingFileCallback = filePathCallback;
                clearUnusedCameraUri();

                try {
                    Intent files = fileChooserParams.createIntent();
                    Intent chooser = Intent.createChooser(files, "Выбрать файл");
                    if (acceptsImages(fileChooserParams.getAcceptTypes())) {
                        Intent camera = createCameraIntent();
                        if (camera != null) {
                            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS,
                                    new Parcelable[]{camera});
                        }
                    }
                    startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception ex) {
                    pendingFileCallback = null;
                    clearUnusedCameraUri();
                    filePathCallback.onReceiveValue(null);
                    Toast.makeText(MainActivity.this,
                            "Не удалось открыть выбор файла", Toast.LENGTH_LONG).show();
                    return true;
                }
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent,
                    String contentDisposition, String mimeType, long contentLength) {
                try {
                    Uri uri = Uri.parse(url);
                    if (!"https".equalsIgnoreCase(uri.getScheme())) {
                        throw new IllegalArgumentException("Небезопасная загрузка");
                    }
                    DownloadManager.Request request = new DownloadManager.Request(uri);
                    request.setNotificationVisibility(
                            DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                    String filename = URLUtil.guessFileName(url, contentDisposition, mimeType);
                    request.setTitle(filename);
                    if (mimeType != null) request.setMimeType(mimeType);
                    String cookies = CookieManager.getInstance().getCookie(url);
                    if (cookies != null) request.addRequestHeader("Cookie", cookies);
                    request.setDestinationInExternalPublicDir(
                            Environment.DIRECTORY_DOWNLOADS, filename);
                    DownloadManager manager = (DownloadManager)
                            getSystemService(Context.DOWNLOAD_SERVICE);
                    if (manager == null) throw new IllegalStateException("DownloadManager");
                    manager.enqueue(request);
                    Toast.makeText(MainActivity.this, "Загрузка началась",
                            Toast.LENGTH_SHORT).show();
                } catch (Exception ex) {
                    Toast.makeText(MainActivity.this,
                            "Не удалось скачать файл", Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    private boolean isTrusted(Uri uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme())) return false;
        if (!TRUSTED_HOST.equalsIgnoreCase(uri.getHost())) return false;
        String path = uri.getPath();
        return path != null && (path.equals(TRUSTED_PATH)
                || path.startsWith(TRUSTED_PATH + "/"));
    }

    private void openExternal(Uri uri) {
        try {
            String scheme = uri.getScheme();
            if (scheme == null) return;
            if (!scheme.equalsIgnoreCase("https")
                    && !scheme.equalsIgnoreCase("http")
                    && !scheme.equalsIgnoreCase("mailto")
                    && !scheme.equalsIgnoreCase("tel")) return;
            startActivity(new Intent(Intent.ACTION_VIEW, uri)
                    .addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException ex) {
            Toast.makeText(this, "Нет приложения для открытия ссылки",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private boolean acceptsImages(String[] types) {
        if (types == null || types.length == 0) return false;
        for (String type : types) {
            if (type != null && (type.startsWith("image/") || type.contains("image"))) {
                return true;
            }
        }
        return false;
    }

    private Intent createCameraIntent() {
        Intent camera = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (camera.resolveActivity(getPackageManager()) == null) return null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                    "OpenChat_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
            Uri uri = getContentResolver().insert(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) return null;
            pendingCameraUri = uri;
            camera.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            camera.setClipData(ClipData.newRawUri("OpenChat photo", uri));
            camera.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            return camera;
        } catch (Exception error) {
            clearUnusedCameraUri();
            return null;
        }
    }

    private void clearUnusedCameraUri() {
        if (pendingCameraUri == null) return;
        try {
            getContentResolver().delete(pendingCameraUri, null, null);
        } catch (Exception ignored) {
            // A failed cleanup must not interfere with using the chat.
        }
        pendingCameraUri = null;
    }

    @Override
    @SuppressWarnings("deprecation") // WebChromeClient file chooser uses this API.
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST) return;

        ValueCallback<Uri[]> callback = pendingFileCallback;
        pendingFileCallback = null;
        Uri[] result = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        if (resultCode == Activity.RESULT_OK && (result == null || result.length == 0)
                && pendingCameraUri != null) {
            result = new Uri[]{pendingCameraUri};
            pendingCameraUri = null; // Camera output now belongs to the selected message.
        } else {
            clearUnusedCameraUri();
        }
        if (callback != null) callback.onReceiveValue(result);
    }

    private LinearLayout createErrorView() {
        LinearLayout layout = new LinearLayout(this);
        layout.setBackgroundColor(NAVY);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setGravity(Gravity.CENTER);
        layout.setPadding(dp(24), dp(24), dp(24), dp(24));

        TextView title = new TextView(this);
        title.setText("Нет соединения");
        title.setTextSize(22);
        title.setTextColor(Color.WHITE);
        title.setGravity(Gravity.CENTER);
        layout.addView(title);

        TextView message = new TextView(this);
        message.setId(android.R.id.message);
        message.setText("Проверьте подключение к интернету.");
        message.setGravity(Gravity.CENTER);
        message.setTextColor(Color.rgb(178, 193, 208));
        LinearLayout.LayoutParams messageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        messageParams.topMargin = dp(14);
        messageParams.bottomMargin = dp(22);
        layout.addView(message, messageParams);

        Button retry = new Button(this);
        retry.setText("Повторить");
        retry.setOnClickListener(v -> {
            errorView.setVisibility(View.GONE);
            webView.loadUrl(HOME_URL);
        });
        layout.addView(retry);
        return layout;
    }

    private void showLoadError(String message) {
        TextView description = errorView.findViewById(android.R.id.message);
        if (description != null) description.setText(message);
        errorView.setVisibility(View.VISIBLE);
        progress.setVisibility(View.GONE);
    }

    private int dp(float px) {
        return Math.round(px * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack(); // Dispatches popstate to OpenChat mobile navigation.
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        if (webView != null) webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onDestroy() {
        if (pendingFileCallback != null) {
            pendingFileCallback.onReceiveValue(null);
            pendingFileCallback = null;
        }
        clearUnusedCameraUri();
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
