package com.equimaps.capacitor_background_geolocation;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import com.getcapacitor.Logger;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationAvailability;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import java.util.HashSet;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

// ── WORKPAY NATIVE PUSH FIX ──────────────────────────────────────────────
// Added imports for direct-to-Supabase HTTP push. No new Gradle
// dependencies — java.net.HttpURLConnection and org.json are both built
// into Android already.
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;
// ──────────────────────────────────────────────────────────────────────

// A bound and started service that is promoted to a foreground service
// (showing a persistent notification) when the first background watcher is
// added, and demoted when the last background watcher is removed.
public class BackgroundGeolocationService extends Service {
    static final String ACTION_BROADCAST = (
        BackgroundGeolocationService.class.getPackage().getName() + ".broadcast"
    );

    private final IBinder binder = new LocalBinder();

    // Must be unique for this application.
    private static final int NOTIFICATION_ID = 28351;

    private class Watcher {
        public String id;
        public FusedLocationProviderClient client;
        public LocationRequest locationRequest;
        public LocationCallback locationCallback;
        public Notification backgroundNotification;

        // ── WORKPAY NATIVE PUSH FIX ──────────────────────────────────────
        // Everything this watcher needs to push a fix straight to Supabase
        // without going through the WebView/Capacitor bridge at all. These
        // come from the addWatcher() call in index.html (via
        // BackgroundGeolocation.java) and can be refreshed later by
        // updateGeofence() below, e.g. if Labour Activity changes the
        // worker's site/radius mid-shift.
        public String workerId;
        public String bizId;
        public String supabaseUrl;
        public String supabaseKey;
        public double geofenceLat;
        public double geofenceLon;
        public float geofenceRadius;
        // ──────────────────────────────────────────────────────────────
    }

    private HashSet<Watcher> watchers = new HashSet<Watcher>();

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // Some devices allow a foreground service to outlive the application's main
    // activity, leading to nasty crashes as reported in issue #59. If we learn
    // that the application has been killed, all watchers are stopped and the
    // service is terminated immediately.
    @Override
    public boolean onUnbind(Intent intent) {
        for (Watcher watcher : watchers) {
            watcher.client.removeLocationUpdates(watcher.locationCallback);
        }
        watchers = new HashSet<Watcher>();
        stopSelf();
        return false;
    }

    Notification getNotification() {
        for (Watcher watcher : watchers) {
            if (watcher.backgroundNotification != null) {
                return watcher.backgroundNotification;
            }
        }
        return null;
    }

    // ── WORKPAY NATIVE PUSH FIX ──────────────────────────────────────────
    // Haversine distance in metres — same formula as _geoDistanceMeters()
    // in index.html, kept in sync deliberately so Labour Activity sees the
    // exact same numbers whether they came from the foreground JS path or
    // this native path.
    private static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
        final double R = 6371000; // Earth radius, metres
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    // Mirrors _nativePushLocationForWorker() in index.html: GET the current
    // workpay_data row, merge just this worker's activeSessions fields in,
    // POST it back as an upsert. Runs on a background thread — this method
    // is called from onLocationResult, which itself runs on the main
    // looper, and HttpURLConnection throws NetworkOnMainThreadException if
    // called there directly.
    private static void pushLocationDirectly(final Watcher w, final Location location) {
        if (w.supabaseUrl == null || w.supabaseKey == null || w.bizId == null || w.workerId == null) {
            return; // config not passed in yet — nothing we can do
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection getConn = null;
                HttpURLConnection postConn = null;
                try {
                    String bizIdEnc = URLEncoder.encode(w.bizId, "UTF-8");

                    // 1) GET current row
                    URL getUrl = new URL(
                        w.supabaseUrl + "/rest/v1/workpay_data?id=eq." + bizIdEnc + "&select=data"
                    );
                    getConn = (HttpURLConnection) getUrl.openConnection();
                    getConn.setRequestMethod("GET");
                    getConn.setRequestProperty("apikey", w.supabaseKey);
                    getConn.setRequestProperty("Authorization", "Bearer " + w.supabaseKey);
                    getConn.setConnectTimeout(15000);
                    getConn.setReadTimeout(15000);

                    BufferedReader reader = new BufferedReader(new InputStreamReader(getConn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    reader.close();

                    JSONArray rows = new JSONArray(sb.toString());
                    JSONObject data = (rows.length() > 0 && !rows.getJSONObject(0).isNull("data"))
                        ? rows.getJSONObject(0).getJSONObject("data")
                        : new JSONObject();

                    JSONObject activeSessions = data.has("activeSessions")
                        ? data.getJSONObject("activeSessions")
                        : new JSONObject();

                    // If this worker was punched out since the fix was queued
                    // (mirrors the JS version's same check), don't resurrect them.
                    if (!activeSessions.has(w.workerId)) {
                        getConn.disconnect();
                        return;
                    }

                    JSONObject session = activeSessions.getJSONObject(w.workerId);
                    boolean hasGeofence = w.geofenceRadius > 0;
                    double dist = hasGeofence
                        ? distanceMeters(location.getLatitude(), location.getLongitude(), w.geofenceLat, w.geofenceLon)
                        : -1;
                    boolean wasOutside = session.optBoolean("outsideGeofence", false);
                    boolean isOutside = hasGeofence && dist > w.geofenceRadius;

                    session.put("lastLat", location.getLatitude());
                    session.put("lastLon", location.getLongitude());
                    if (hasGeofence) session.put("lastDist", Math.round(dist));
                    session.put("lastAccuracy", location.hasAccuracy() ? Math.round(location.getAccuracy()) : JSONObject.NULL);
                    session.put("lastGeoCheckAt", System.currentTimeMillis());
                    if (hasGeofence) {
                        session.put("outsideGeofence", isOutside);
                        if (isOutside && !wasOutside) session.put("outsideSince", System.currentTimeMillis());
                        else if (!isOutside && wasOutside) session.put("outsideSince", JSONObject.NULL);
                    }

                    activeSessions.put(w.workerId, session);
                    data.put("activeSessions", activeSessions);
                    data.put("_ts", System.currentTimeMillis());

                    JSONObject body = new JSONObject();
                    body.put("id", w.bizId);
                    body.put("data", data);
                    body.put("updated_at", System.currentTimeMillis());

                    // 2) POST as upsert (merge-duplicates on the id primary key —
                    // same pattern index.html's setDoc() already uses elsewhere).
                    URL postUrl = new URL(w.supabaseUrl + "/rest/v1/workpay_data");
                    postConn = (HttpURLConnection) postUrl.openConnection();
                    postConn.setRequestMethod("POST");
                    postConn.setRequestProperty("apikey", w.supabaseKey);
                    postConn.setRequestProperty("Authorization", "Bearer " + w.supabaseKey);
                    postConn.setRequestProperty("Content-Type", "application/json");
                    postConn.setRequestProperty("Prefer", "resolution=merge-duplicates,return=minimal");
                    postConn.setConnectTimeout(15000);
                    postConn.setReadTimeout(15000);
                    postConn.setDoOutput(true);

                    OutputStream os = postConn.getOutputStream();
                    os.write(body.toString().getBytes("UTF-8"));
                    os.flush();
                    os.close();

                    int code = postConn.getResponseCode();
                    Logger.debug("[BackgroundGeolocation] native push for " + w.workerId + " -> HTTP " + code);
                } catch (Exception e) {
                    Logger.error("[BackgroundGeolocation] native push failed (will retry on next fix)", e);
                } finally {
                    if (getConn != null) getConn.disconnect();
                    if (postConn != null) postConn.disconnect();
                }
            }
        }).start();
    }
    // ──────────────────────────────────────────────────────────────────

    // Handles requests from the activity.
    public class LocalBinder extends Binder {
        void addWatcher(
            final String id,
            Notification backgroundNotification,
            float distanceFilter,
            // ── WORKPAY NATIVE PUSH FIX — new params, all optional/nullable.
            // If null/0, this watcher behaves exactly as before (broadcast
            // only) and the native push is simply skipped.
            final String workerId,
            final String bizId,
            final String supabaseUrl,
            final String supabaseKey,
            final double geofenceLat,
            final double geofenceLon,
            final float geofenceRadius
        ) {
            FusedLocationProviderClient client = LocationServices.getFusedLocationProviderClient(
                BackgroundGeolocationService.this
            );

            LocationRequest locationRequest = new LocationRequest();
            locationRequest.setMaxWaitTime(1000);
            locationRequest.setInterval(1000);
            locationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
            locationRequest.setSmallestDisplacement(distanceFilter);

            final Watcher watcher = new Watcher();
            watcher.id = id;
            watcher.workerId = workerId;
            watcher.bizId = bizId;
            watcher.supabaseUrl = supabaseUrl;
            watcher.supabaseKey = supabaseKey;
            watcher.geofenceLat = geofenceLat;
            watcher.geofenceLon = geofenceLon;
            watcher.geofenceRadius = geofenceRadius;

            LocationCallback callback = new LocationCallback(){
                @Override
                public void onLocationResult(LocationResult locationResult) {
                    Location location = locationResult.getLastLocation();

                    // Existing behaviour — unchanged. Still useful for when the
                    // app happens to be foregrounded at the moment a fix lands.
                    Intent intent = new Intent(ACTION_BROADCAST);
                    intent.putExtra("location", location);
                    intent.putExtra("id", id);
                    LocalBroadcastManager.getInstance(
                        getApplicationContext()
                    ).sendBroadcast(intent);

                    // ── WORKPAY NATIVE PUSH FIX ──────────────────────────
                    // This is the actual fix: push straight to Supabase from
                    // here, regardless of whether the broadcast above ever
                    // reaches a live JS listener.
                    pushLocationDirectly(watcher, location);
                    // ─────────────────────────────────────────────────────
                }

                @Override
                public void onLocationAvailability(LocationAvailability availability) {
                    if (!availability.isLocationAvailable()) {
                        Logger.debug("Location not available");
                    }
                }
            };

            watcher.locationRequest = locationRequest;
            watcher.locationCallback = callback;
            watcher.backgroundNotification = backgroundNotification;
            watchers.add(watcher);

            // According to Android Studio, this method can throw a Security Exception if
            // permissions are not yet granted. Rather than check the permissions, which is fiddly,
            // we simply ignore the exception.
            try {
                watcher.client = client;
                watcher.client.requestLocationUpdates(
                    watcher.locationRequest,
                    watcher.locationCallback,
                    null
                );
            } catch (SecurityException ignore) {}

            // Promote the service to the foreground if necessary.
            // Ideally we would only call 'startForeground' if the service is not already
            // foregrounded. Unfortunately, 'getForegroundServiceType' was only introduced
            // in API level 29 and seems to behave weirdly, as reported in #120. However,
            // it appears that 'startForeground' is idempotent, so we just call it repeatedly
            // each time a background watcher is added.
            if (backgroundNotification != null) {
                try {
                    // This method has been known to fail due to weird
                    // permission bugs, so we prevent any exceptions from
                    // crashing the app. See issue #86.
                    startForeground(NOTIFICATION_ID, backgroundNotification);
                } catch (Exception exception) {
                    Logger.error("Failed to foreground service", exception);
                }
            }
        }

        // ── WORKPAY NATIVE PUSH FIX ──────────────────────────────────────
        // Call this from index.html (via a new plugin method) whenever
        // Labour Activity changes a clocked-in worker's geofence radius or
        // site location, so the native push keeps computing distance
        // against the current geofence instead of a stale one captured at
        // punch-in time.
        void updateGeofence(String workerId, double lat, double lon, float radius) {
            for (Watcher watcher : watchers) {
                if (workerId.equals(watcher.workerId)) {
                    watcher.geofenceLat = lat;
                    watcher.geofenceLon = lon;
                    watcher.geofenceRadius = radius;
                }
            }
        }
        // ──────────────────────────────────────────────────────────────

        void removeWatcher(String id) {
            for (Watcher watcher : watchers) {
                if (watcher.id.equals(id)) {
                    watcher.client.removeLocationUpdates(watcher.locationCallback);
                    watchers.remove(watcher);
                    if (getNotification() == null) {
                        stopForeground(true);
                    }
                    return;
                }
            }
        }

        void onPermissionsGranted() {
            // If permissions were granted while the app was in the background, for example in
            // the Settings app, the watchers need restarting.
            for (Watcher watcher : watchers) {
                watcher.client.removeLocationUpdates(watcher.locationCallback);
                watcher.client.requestLocationUpdates(
                    watcher.locationRequest,
                    watcher.locationCallback,
                    null
                );
            }
        }

        void stopService() {
            BackgroundGeolocationService.this.stopSelf();
        }
    }
}
