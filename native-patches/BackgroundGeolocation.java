// ── FIX: addWatcher() used to fall through to service.addWatcher() (which
// calls startForeground for a location-type service) IMMEDIATELY after
// firing the permission popup, without waiting for the user's answer. On
// newer Android versions, starting a location foreground service before
// permission is actually granted can throw and crash the app straight back
// to the home screen — but only the FIRST time, since every later call
// already has permission granted before reaching this point at all.
//
// Fix: split the actual "start the watcher" logic into its own method, and
// only call it once permission is confirmed. If permission still needs to
// be requested, save the call and return — the async permission callback
// (locationPermissionsCallback) is the ONLY thing that resumes from there.

@PluginMethod(returnType = PluginMethod.RETURN_CALLBACK)
public void addWatcher(final PluginCall call) {
    if (service == null) {
        call.reject("Service not running.");
        return;
    }

    call.setKeepAlive(true);

    if (getPermissionState("location") != PermissionState.GRANTED) {
        if (call.getBoolean("requestPermissions", true)) {
            requestPermissionForAlias("location", call, "locationPermissionsCallback");
            return; // FIX: wait for the callback — do not continue below yet.
        } else {
            call.reject("Permission denied.", "NOT_AUTHORIZED");
            return; // FIX: also stop here — there is nothing more to do.
        }
    }

    if (!isLocationEnabled(getContext())) {
        call.reject("Location services disabled.", "NOT_AUTHORIZED");
        return; // FIX: stop here too — don't start a watcher with location off.
    }

    startWatcherNow(call);
}

@PermissionCallback
private void locationPermissionsCallback(PluginCall call) {
    if (getPermissionState("location") != PermissionState.GRANTED) {
        call.reject("User denied location permission", "NOT_AUTHORIZED");
        return;
    }

    if (service != null) {
        service.onPermissionsGranted();
        stoppedWithoutPermissions = false;
    }

    if (!isLocationEnabled(getContext())) {
        call.reject("Location services disabled.", "NOT_AUTHORIZED");
        return;
    }

    // FIX: this is the only place that resumes a fresh addWatcher() call
    // after permission was just granted for the first time.
    startWatcherNow(call);
}

// FIX: extracted from the old addWatcher() body — everything that used to
// run unconditionally now only runs once permission is already confirmed
// granted, from either addWatcher() directly or the callback above.
private void startWatcherNow(final PluginCall call) {
    if (call.getBoolean("stale", false)) {
        fetchLastLocation(call);
    }

    Notification backgroundNotification = null;
    String backgroundMessage = call.getString("backgroundMessage");

    if (backgroundMessage != null) {
        Notification.Builder builder = new Notification.Builder(getContext())
            .setContentTitle(
                call.getString(
                    "backgroundTitle",
                    "Using your location"
                )
            )
            .setContentText(backgroundMessage)
            .setOngoing(true)
            .setPriority(Notification.PRIORITY_HIGH)
            .setWhen(System.currentTimeMillis());

        try {
            String name = getAppString(
                "capacitor_background_geolocation_notification_icon",
                "mipmap/ic_launcher"
            );
            String[] parts = name.split("/");
            builder.setSmallIcon(
                getAppResourceIdentifier(parts[1], parts[0])
            );
        } catch (Exception e) {
            Logger.error("Could not set notification icon", e);
        }

        try {
            String color = getAppString(
                "capacitor_background_geolocation_notification_color",
                null
            );
            if (color != null) {
                builder.setColor(Color.parseColor(color));
            }
        } catch (Exception e) {
            Logger.error("Could not set notification color", e);
        }

        Intent launchIntent = getContext().getPackageManager().getLaunchIntentForPackage(
            getContext().getPackageName()
        );
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            builder.setContentIntent(
                PendingIntent.getActivity(
                    getContext(),
                    0,
                    launchIntent,
                    PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE
                )
            );
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setChannelId(BackgroundGeolocationService.class.getPackage().getName());
        }

        backgroundNotification = builder.build();
    }

    service.addWatcher(
        call.getCallbackId(),
        backgroundNotification,
        call.getFloat("distanceFilter", 0f),
        call.getString("workerId"),
        call.getString("bizId"),
        call.getString("supabaseUrl"),
        call.getString("supabaseKey"),
        call.getDouble("geofenceLat", 0d),
        call.getDouble("geofenceLon", 0d),
        call.getFloat("geofenceRadius", 0f)
    );
}
