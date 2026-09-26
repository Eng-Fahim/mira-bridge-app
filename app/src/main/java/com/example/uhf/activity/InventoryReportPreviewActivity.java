package com.example.uhf.activity;

import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintManager;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import com.example.uhf.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import android.content.Context;

public class InventoryReportPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_REPORT_HTML = "report_html";
    public static final String EXTRA_REPORT_TITLE = "report_title";

    private WebView webView;
    private MaterialToolbar toolbar;
    private ExtendedFloatingActionButton fabPrint;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inventory_report_preview);

        String html = getIntent().getStringExtra(EXTRA_REPORT_HTML);
        String title = getIntent().getStringExtra(EXTRA_REPORT_TITLE);
        if (title == null) title = "Inventory Report";

        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(title);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.setWebViewClient(new WebViewClient());

        if (html != null && !html.isEmpty()) {
            webView.loadDataWithBaseURL(null, html, "text/HTML", "UTF-8", null);
        }

        fabPrint = findViewById(R.id.fabPrint);
        fabPrint.setOnClickListener(v -> printCurrentPage());

        MaterialButton btnShare = findViewById(R.id.btnShare);
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareReport(html, title));
        }
    }

    private void printCurrentPage() {
        try {
            PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            String jobName = getString(R.string.app_name) + " — " 
                    + toolbar.getTitle() + " — " 
                    + System.currentTimeMillis();
            
            if (webView != null) {
                android.print.PrintDocumentAdapter adapter = 
                        webView.createPrintDocumentAdapter(jobName);
                
                PrintAttributes attrs = new PrintAttributes.Builder()
                        .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                        .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                        .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                        .build();
                
                printManager.print(jobName, adapter, attrs);
            }
        } catch (Exception e) {
            android.widget.Toast.makeText(this, 
                    "⚠️ Print failed: " + e.getMessage(), 
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void shareReport(String html, String title) {
        try {
            android.content.Intent intent = new android.content.Intent(
                    android.content.Intent.ACTION_SEND);
            intent.setType("text/html");
            intent.putExtra(android.content.Intent.EXTRA_SUBJECT, title);
            intent.putExtra(android.content.Intent.EXTRA_TEXT, html);
            startActivity(android.content.Intent.createChooser(intent, 
                    getString(R.string.share)));
        } catch (Exception ignored) {}
    }
}
