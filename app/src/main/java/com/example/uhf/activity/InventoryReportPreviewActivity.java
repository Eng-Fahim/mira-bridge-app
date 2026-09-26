package com.example.uhf.activity;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.example.uhf.R;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

public class InventoryReportPreviewActivity extends AppCompatActivity {

    public static final String EXTRA_REPORT_HTML = "report_html";
    public static final String EXTRA_REPORT_TITLE = "report_title";

    private WebView webView;
    private MaterialToolbar toolbar;
    private String reportHtml;
    private String reportTitle;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inventory_report_preview);

        reportHtml = getIntent().getStringExtra(EXTRA_REPORT_HTML);
        reportTitle = getIntent().getStringExtra(EXTRA_REPORT_TITLE);
        if (reportTitle == null) reportTitle = "Inventory Report";

        toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(reportTitle);
        toolbar.setNavigationOnClickListener(v -> finish());

        webView = findViewById(R.id.webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setBuiltInZoomControls(true);
        webView.getSettings().setDisplayZoomControls(false);
        webView.setWebViewClient(new WebViewClient());

        if (reportHtml != null && !reportHtml.isEmpty()) {
            webView.loadDataWithBaseURL(null, reportHtml, "text/HTML", "UTF-8", null);
        }

        ExtendedFloatingActionButton fabPrint = findViewById(R.id.fabPrint);
        if (fabPrint != null) {
            fabPrint.setOnClickListener(v -> printCurrentPage());
        }

        MaterialButton btnShare = findViewById(R.id.btnShare);
        if (btnShare != null) {
            btnShare.setOnClickListener(v -> shareReport());
        }
    }

    private void printCurrentPage() {
        try {
            if (webView == null) return;
            PrintManager printManager = (PrintManager) getSystemService(Context.PRINT_SERVICE);
            String jobName = getString(R.string.app_name) + " — "
                    + reportTitle + " — " + System.currentTimeMillis();

            PrintDocumentAdapter adapter = webView.createPrintDocumentAdapter(jobName);
            PrintAttributes attrs = new PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setResolution(new PrintAttributes.Resolution("pdf", "pdf", 600, 600))
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build();

            printManager.print(jobName, adapter, attrs);
            Toast.makeText(this, "🖨️ جاري الطباعة...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "⚠️ Print failed: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void shareReport() {
        try {
            if (reportHtml == null) return;
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/html");
            intent.putExtra(Intent.EXTRA_SUBJECT, reportTitle);
            intent.putExtra(Intent.EXTRA_TEXT, reportHtml);
            startActivity(Intent.createChooser(intent, "مشاركة التقرير"));
        } catch (Exception e) {
            Toast.makeText(this, "⚠️ Share failed", Toast.LENGTH_SHORT).show();
        }
    }
}
