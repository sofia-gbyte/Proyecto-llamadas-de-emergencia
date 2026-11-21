package cl.codes.mobile;

import android.app.Activity;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslCertificate;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int SERVER_PORT = 8443;
    private static final int NAVY = Color.rgb(10, 20, 40);
    private static final int SURFACE = Color.rgb(15, 28, 48);
    private static final int TEXT = Color.rgb(232, 238, 251);
    private static final int MUTED = Color.rgb(146, 167, 207);
    private static final int ACCENT = Color.rgb(0, 200, 176);

    private LinearLayout root;
    private WebView webView;
    private TextView connectionStatus;
    private String serverAddress;
    private String expectedFingerprint;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        SecureServerConfig.Values config;
        try {
            config = SecureServerConfig.load(this);
        } catch (GeneralSecurityException | IOException exception) {
            String message = "No se pudo descifrar la configuración del servidor. Vuelve a ingresarla.";
            try {
                SecureServerConfig.clear(this);
            } catch (GeneralSecurityException | IOException clearException) {
                message += " Tampoco se pudo limpiar el almacén; revisa el almacenamiento seguro del dispositivo.";
            }
            showConfiguration(message);
            return;
        }
        serverAddress = config.address;
        expectedFingerprint = config.fingerprint;
        if (isValidConfiguration(serverAddress, expectedFingerprint)) {
            showWebView();
        } else {
            showConfiguration("");
        }
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.stopLoading();
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
            webView.destroy();
            webView = null;
        }
        WebStorage.getInstance().deleteAllData();
        CookieManager.getInstance().removeAllCookies(null);
        CookieManager.getInstance().flush();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
    }

    private void showConfiguration(String initialError) {
        if (webView != null) {
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        root.setBackgroundColor(NAVY);

        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.codes_logo_image);
        logo.setContentDescription("Logo de CODES");
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        LinearLayout.LayoutParams logoLayout = new LinearLayout.LayoutParams(dp(84), dp(84));
        logoLayout.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(logo, logoLayout);

        TextView title = text("CODES", 28, TEXT, true);
        title.setLetterSpacing(0.18f);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(title);
        TextView subtitle = text("OPERACIONES · CONEXIÓN SEGURA", 11, ACCENT, true);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(subtitle);
        TextView explanation = text(
                "En CODES para escritorio, abre «Conexión móvil» y copia la dirección HTTPS y la huella SHA-256. "
                        + "El teléfono y el servidor deben estar en la misma red privada.",
                14, MUTED, false);
        explanation.setPadding(0, 0, 0, dp(14));
        root.addView(explanation);

        root.addView(text("Dirección del servidor", 13, TEXT, true));
        EditText addressField = input("192.168.1.20", InputType.TYPE_CLASS_TEXT);
        addressField.setText(serverAddress);
        root.addView(addressField, matchWidth());

        TextView fingerprintLabel = text("Huella del certificado SHA-256", 13, TEXT, true);
        fingerprintLabel.setPadding(0, dp(14), 0, 0);
        root.addView(fingerprintLabel);
        EditText fingerprintField = input("AA:BB:… (64 caracteres hexadecimales)",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fingerprintField.setText(expectedFingerprint);
        root.addView(fingerprintField, matchWidth());

        TextView error = text(initialError, 13, Color.rgb(255, 157, 177), false);
        error.setPadding(0, dp(12), 0, dp(4));
        error.setVisibility(initialError.isEmpty() ? View.GONE : View.VISIBLE);
        root.addView(error);

        Button connect = button("Conectar a CODES");
        connect.setOnClickListener(view -> {
            String address = addressField.getText().toString().trim();
            String fingerprint = normalizeFingerprint(fingerprintField.getText().toString());
            if (!isValidConfiguration(address, fingerprint)) {
                error.setText(R.string.invalid_server_config);
                error.setVisibility(View.VISIBLE);
                return;
            }
            try {
                SecureServerConfig.save(this, address, fingerprint);
                serverAddress = address;
                expectedFingerprint = fingerprint;
                showWebView();
            } catch (GeneralSecurityException | IOException exception) {
                error.setText(R.string.save_server_config_error);
                error.setVisibility(View.VISIBLE);
            }
        });
        root.addView(connect, matchWidth());
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(NAVY);
        scrollView.addView(root);
        setContentView(scrollView);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void showWebView() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(NAVY);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(5), dp(12), dp(5));
        toolbar.setBackgroundColor(NAVY);
        connectionStatus = text("Conectando de forma segura…", 12, MUTED, false);
        toolbar.addView(connectionStatus, new LinearLayout.LayoutParams(0, dp(42), 1));
        Button retry = compactButton("Reintentar");
        retry.setOnClickListener(view -> {
            if (webView != null) webView.reload();
        });
        toolbar.addView(retry);
        Button settings = compactButton("Servidor");
        settings.setOnClickListener(view -> showConfiguration(""));
        toolbar.addView(settings);
        root.addView(toolbar, matchWidth());

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        root.addView(progress, matchWidth());

        webView = new WebView(this);
        webView.setBackgroundColor(NAVY);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(false);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        webView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        webView.getSettings().setSupportMultipleWindows(false);
        webView.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false);
        WebStorage.getInstance().deleteAllData();
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("https".equalsIgnoreCase(uri.getScheme())
                        && serverAddress.equals(uri.getHost())
                        && uri.getPort() == SERVER_PORT) {
                    return false;
                }
                if ("google.navigation".equalsIgnoreCase(uri.getScheme())
                        || "geo".equalsIgnoreCase(uri.getScheme())) {
                    openNavigation(uri);
                    return true;
                }
                return true;
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                // This local server uses a private certificate; proceed only after an exact, valid SHA-256 pin match.
                if (error != null && isPinnedCertificate(error.getCertificate(), error.getUrl())) handler.proceed();
                else {
                    handler.cancel();
                    setConnectionStatus("Certificado no reconocido. Verifica la huella en CODES.");
                }
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                // WebView may not expose the peer certificate until TLS negotiation completes.
                setConnectionStatus("Conectando de forma segura…");
                progress.setVisibility(View.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!isPinnedCertificate(view.getCertificate(), url)) {
                    view.stopLoading();
                    setConnectionStatus("Certificado no reconocido. Verifica la huella en CODES.");
                    progress.setVisibility(View.GONE);
                    return;
                }
                setConnectionStatus("Conectado a CODES");
                progress.setVisibility(View.GONE);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) {
                    setConnectionStatus("Sin conexión. Comprueba el servidor y la red local.");
                    progress.setVisibility(View.GONE);
                }
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        webView.loadUrl("https://" + serverAddress + ":" + SERVER_PORT + "/mobile.html");
    }

    private boolean isPinnedCertificate(SslCertificate certificate, String url) {
        try {
            if (certificate == null || url == null) return false;
            Uri source = Uri.parse(url);
            if (source == null
                    || !"https".equalsIgnoreCase(source.getScheme())
                    || !serverAddress.equals(source.getHost())
                    || source.getPort() != SERVER_PORT) return false;
            BundleState certificateState = encodedCertificate(certificate);
            if (certificateState.encoded == null) return false;
            X509Certificate parsedCertificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certificateState.encoded));
            parsedCertificate.checkValidity();
            byte[] fingerprint = MessageDigest.getInstance("SHA-256").digest(parsedCertificate.getEncoded());
            return expectedFingerprint.equalsIgnoreCase(toHex(fingerprint));
        } catch (GeneralSecurityException exception) {
            return false;
        }
    }

    private static BundleState encodedCertificate(SslCertificate certificate) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                X509Certificate x509 = certificate.getX509Certificate();
                return new BundleState(x509 == null ? null : x509.getEncoded());
            } catch (java.security.cert.CertificateEncodingException exception) {
                return new BundleState(null);
            }
        }
        android.os.Bundle bundle = SslCertificate.saveState(certificate);
        return new BundleState(bundle == null ? null : bundle.getByteArray("x509-certificate"));
    }

    private void openNavigation(Uri uri) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (android.content.ActivityNotFoundException exception) {
            if ("google.navigation".equalsIgnoreCase(uri.getScheme())) {
                String destination = uri.getQueryParameter("q");
                Uri fallback = Uri.parse("geo:0,0?q=" + Uri.encode(destination == null ? "" : destination));
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, fallback));
                    return;
                } catch (android.content.ActivityNotFoundException ignored) {
                    Toast.makeText(this, "No hay una aplicación de mapas instalada.", Toast.LENGTH_LONG).show();
                    return;
                }
            }
            Toast.makeText(this, "No hay una aplicación de mapas instalada.", Toast.LENGTH_LONG).show();
        }
    }

    private void setConnectionStatus(String message) {
        if (connectionStatus != null) connectionStatus.setText(message);
    }

    private boolean isValidConfiguration(String address, String fingerprint) {
        return isPrivateOrTailscaleIpv4(address)
                && fingerprint != null
                && fingerprint.matches("[0-9A-Fa-f]{64}");
    }

    private static boolean isPrivateOrTailscaleIpv4(String value) {
        if (value == null || !value.matches("\\d{1,3}(?:\\.\\d{1,3}){3}")) return false;
        String[] parts = value.split("\\.");
        int[] octets = new int[4];
        try {
            for (int i = 0; i < parts.length; i++) {
                octets[i] = Integer.parseInt(parts[i]);
                if (octets[i] > 255) return false;
            }
        } catch (NumberFormatException exception) {
            return false;
        }
        boolean privateLan = octets[0] == 10
                || octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31
                || octets[0] == 192 && octets[1] == 168;
        boolean tailscale = octets[0] == 100 && octets[1] >= 64 && octets[1] <= 127;
        return privateLan || tailscale;
    }

    private static String normalizeFingerprint(String input) {
        return input == null ? "" : input.replace(":", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    }

    private static String toHex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        return result.toString();
    }

    private EditText input(String hint, int inputType) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextColor(TEXT);
        input.setHintTextColor(MUTED);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setPadding(dp(12), dp(10), dp(12), dp(10));
        input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        return input;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(this);
        text.setText(value);
        text.setTextSize(size);
        text.setTextColor(color);
        if (bold) text.setTypeface(null, android.graphics.Typeface.BOLD);
        return text;
    }

    private Button button(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextColor(NAVY);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        return button;
    }

    private Button compactButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(11);
        button.setTextColor(ACCENT);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setPadding(dp(5), 0, dp(5), 0);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(SURFACE));
        return button;
    }

    private LinearLayout.LayoutParams matchWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class BundleState {
        private final byte[] encoded;

        private BundleState(byte[] encoded) {
            this.encoded = encoded;
        }
    }
}
