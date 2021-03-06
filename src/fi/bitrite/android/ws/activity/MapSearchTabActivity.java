package fi.bitrite.android.ws.activity;

import android.app.Dialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.maps.*;
import com.google.gson.Gson;
import com.google.inject.Inject;
import de.android1.overlaymanager.*;
import de.android1.overlaymanager.ManagedOverlayGestureDetector.OnOverlayGestureListener;
import de.android1.overlaymanager.lazyload.LazyLoadCallback;
import de.android1.overlaymanager.lazyload.LazyLoadException;
import fi.bitrite.android.ws.R;
import fi.bitrite.android.ws.WSAndroidApplication;
import fi.bitrite.android.ws.host.Search;
import fi.bitrite.android.ws.host.SearchFactory;
import fi.bitrite.android.ws.host.impl.TooManyHostsException;
import fi.bitrite.android.ws.model.Host;
import fi.bitrite.android.ws.model.HostBriefInfo;
import fi.bitrite.android.ws.util.MapAnimator;
import fi.bitrite.android.ws.util.http.HttpException;
import fi.bitrite.android.ws.view.ScaleBarOverlay;
import roboguice.activity.RoboMapActivity;
import roboguice.inject.InjectView;

import java.util.ArrayList;
import java.util.List;

public class MapSearchTabActivity extends RoboMapActivity {

    private static final String HOST_OVERLAY = "hostoverlay";
    private static final int NUM_HOSTS_CUTOFF = 150;
    private static final int MIN_ZOOM_LEVEL = 8;
    private static final int HOST_FOCUS_ZOOM_LEVEL = 16;

    @InjectView(R.id.mapView)
    MapView mapView;
    @InjectView(R.id.layoutZoom)
    LinearLayout zoomControls;
    @InjectView(R.id.lblBigNumber)
    TextView lblBigNumber;
    @InjectView(R.id.lblStatusMessage)
    TextView lblStatusMessage;

    @Inject
    SearchFactory searchFactory;

    @Inject
    MapAnimator mapAnimator;

    private MapController mapController;
    private OverlayManager overlayManager;
    private MyLocationOverlay locationOverlay;
    private ScaleBarOverlay scaleBarOverlay;

    private Gson gson;
    private HostBriefInfo host;
    private Dialog hostPopup;

    @Override
    @SuppressWarnings("deprecation")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.map_tab);
        mapView.setBuiltInZoomControls(true);
        overlayManager = new OverlayManager(this, mapView);
        mapController = mapView.getController();

        View zoomView = mapView.getZoomControls();
        zoomControls.addView(zoomView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        mapView.setBuiltInZoomControls(false);

        gson = new Gson();
        setupHostPopup();
    }

    private void setupHostPopup() {
        hostPopup = new Dialog(this);
        hostPopup.setContentView(R.layout.map_popup);
        hostPopup.setCancelable(true);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
        lp.copyFrom(hostPopup.getWindow().getAttributes());
        lp.width = WindowManager.LayoutParams.FILL_PARENT;
        hostPopup.getWindow().setAttributes(lp);

        TextView close = (TextView) hostPopup.findViewById(R.id.lblMapPopupClose);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hostPopup.dismiss();
            }
        });

        TextView details = (TextView) hostPopup.findViewById(R.id.lblMapPopupViewDetails);
        details.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hostPopup.dismiss();
                Intent i = new Intent(MapSearchTabActivity.this, HostInformationActivity.class);
                i.putExtra("host", Host.createFromBriefInfo(host));
                i.putExtra("id", host.getId());
                startActivityForResult(i, 0);
            }
        });

    }

    @Override
    public void onStart() {
        super.onStart();

        Drawable marker = getResources().getDrawable(R.drawable.shower);
        ManagedOverlay managedOverlay = overlayManager.createOverlay(HOST_OVERLAY, marker);
        managedOverlay.setLazyLoadCallback(createLazyLoadCallback());
        managedOverlay.setOnOverlayGestureListener(createOnOverlayGestureListener());

        // registers the ManagedOverlayer to the MapView
        overlayManager.populate();

        managedOverlay.invokeLazyLoad(500);

        locationOverlay = new MyLocationOverlay(this, mapView);
        locationOverlay.enableMyLocation();
        mapView.getOverlays().add(locationOverlay);

        scaleBarOverlay = new ScaleBarOverlay(this, this, mapView);
        if (usingMetricSystem()) {
            scaleBarOverlay.setMetric();
        } else {
            scaleBarOverlay.setImperial();
        }

        List<Overlay> overlays = mapView.getOverlays();
        overlays.add(scaleBarOverlay);
    }

    private LazyLoadCallback createLazyLoadCallback() {
        return new LazyLoadCallback() {
            public List<ManagedOverlayItem> lazyload(GeoPoint topLeft, GeoPoint bottomRight, ManagedOverlay overlay)
                    throws LazyLoadException {
                List<ManagedOverlayItem> overlayItems = new ArrayList<ManagedOverlayItem>();
                try {
                    hideBigNumber();

                    if (overlay.getZoomlevel() < MIN_ZOOM_LEVEL) {
                        sendMessage(getResources().getString(R.string.zoom_in_error), true);
                        showBigNumber(getResources().getString(R.string.zoom_in));
                        return overlayItems;
                    }

                    sendMessage(getResources().getString(R.string.loading_hosts), false);

                    Search search = searchFactory.createMapSearch(topLeft, bottomRight, NUM_HOSTS_CUTOFF);
                    try {
                        List<HostBriefInfo> hosts = search.doSearch();
                        sendMessage(getResources().getString(R.string.hosts_in_area, hosts.size()), false);

                        for (HostBriefInfo host : hosts) {
                            GeoPoint point = host.getGeoPoint();
                            ManagedOverlayItem item = new ManagedOverlayItem(point, host.getFullname(), gson.toJson(host));
                            overlayItems.add(item);
                        }
                    } catch (TooManyHostsException e) {
                        int n = e.getNumHosts();
                        showBigNumber((n > 1000) ? "1000+" : Integer.toString(n));
                        sendMessage(getResources().getString(R.string.too_many_hosts), true);
                    } catch (HttpException e) {
                        Log.e(WSAndroidApplication.TAG, e.getMessage(), e);
                        sendMessage(getResources().getString(R.string.error_loading_hosts), true);
                    }
                } catch (Exception e) {
                    throw new LazyLoadException(e);
                }

                return overlayItems;
            }

            private void hideBigNumber() {
                mapView.post(new Runnable() {
                    public void run() {
                        hideBigNumberOfHosts();
                    }
                });
            }

            private void showBigNumber(final String number) {
                mapView.post(new Runnable() {
                    public void run() {
                        showBigNumberOfHosts(number);
                    }
                });
            }

            private void sendMessage(final String message, final boolean error) {
                mapView.post(new Runnable() {
                    public void run() {
                        updateStatusMessage(message, error);
                    }
                });
            }
        };

    }

    private OnOverlayGestureListener createOnOverlayGestureListener() {
        return new ManagedOverlayGestureDetector.OnOverlayGestureListener() {

            public boolean onSingleTap(MotionEvent e, ManagedOverlay overlay, GeoPoint point, ManagedOverlayItem item) {
                if (item != null) {
                    showHostPopup(item.getSnippet());
                }
                return true;
            }

            public boolean onDoubleTap(MotionEvent e, ManagedOverlay overlay, GeoPoint point, ManagedOverlayItem item) {
                mapController.animateTo(point);
                mapController.zoomIn();
                return true;
            }

            public boolean onScrolled(MotionEvent e1, MotionEvent e2, float distX, float distY, ManagedOverlay overlay) {
                return false;
            }

            public boolean onZoom(ZoomEvent zoom, ManagedOverlay overlay) {
                return false;
            }

            public void onLongPress(MotionEvent e, ManagedOverlay overlay) {
            }

            public void onLongPressFinished(MotionEvent e, ManagedOverlay overlay, GeoPoint point,
                                            ManagedOverlayItem item) {
            }
        };
    }

    private void showHostPopup(String snippet) {
        host = gson.fromJson(snippet, HostBriefInfo.class);
        hostPopup.setTitle(host.getFullname());
        TextView location = (TextView) hostPopup.findViewById(R.id.lblMapPopupLocation);
        location.setText(host.getLocation());
        TextView distance = (TextView) hostPopup.findViewById(R.id.lblMapPopupDistance);

        Location loc1 = locationOverlay.getLastFix();
        GeoPoint hostPoint = host.getGeoPoint();
        Location loc2 = new Location("");
        loc2.setLatitude((float) hostPoint.getLatitudeE6() / 1.0e6);
        loc2.setLongitude((float) hostPoint.getLongitudeE6() / 1.0e6);

        if (loc1 == null) {
            distance.setVisibility(View.GONE);
        } else {
            String text = getDistanceText(loc1.distanceTo(loc2));
            distance.setText(text);
            distance.setVisibility(View.VISIBLE);
        }

        hostPopup.show();
    }

    private String getDistanceText(float d) {
        if (usingMetricSystem()) {
            float temp = d / 100.0f;
            return (Math.round(temp)) / 10.0f + " " + getResources().getString(R.string.km_distance);
        } else {
            float temp = d / 160.9344f;
            return (Math.round(temp)) / 10.0f + " " + getResources().getString(R.string.mi_distance);
        }
    }

    private boolean usingMetricSystem() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String unit = prefs.getString("distance_unit", "km");
        return unit.equals("km");
    }

    private void updateStatusMessage(String message, boolean error) {
        if (error) {
            lblStatusMessage.setTextColor(0xFFFF0000);
        } else {
            lblStatusMessage.setTextColor(0xFF000000);
        }

        lblStatusMessage.setText(message);
    }

    private void showBigNumberOfHosts(String number) {
        lblBigNumber.setText(number);
        lblBigNumber.setVisibility(View.VISIBLE);
    }

    private void hideBigNumberOfHosts() {
        lblBigNumber.setVisibility(View.GONE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == HostInformationActivity.RESULT_SHOW_HOST_ON_MAP) {
            MainActivity parent = (MainActivity) getParent();
            parent.stashHost(data, 2);
            int lat = data.getIntExtra("lat", mapView.getMapCenter().getLatitudeE6());
            int lon = data.getIntExtra("lon", mapView.getMapCenter().getLongitudeE6());
            GeoPoint point = new GeoPoint(lat, lon);
            mapController.animateTo(point);
            mapController.setZoom(16);
            mapView.invalidate();
        }
    }

    @Override
    public void onBackPressed() {
        MainActivity parent = (MainActivity) getParent();
        if (parent.hasStashedHost()) {
            Intent i = new Intent(MapSearchTabActivity.this, HostInformationActivity.class);
            i = parent.popStashedHost(i);
            startActivityForResult(i, 0);
        } else {
            super.onBackPressed();
        }
    }

    public void zoomToCurrentLocation(View view) {
        if (locationOverlay.getMyLocation() != null) {
            mapController.animateTo(locationOverlay.getMyLocation());
            mapController.setZoom(16);
        }
    }

    @Override
    protected boolean isRouteDisplayed() {
        return false;
    }


    private void animateToMapTargetIfNeeded() {

        GeoPoint target = mapAnimator.getTarget();
        if (target != null && target.getLatitudeE6() != 0 && target.getLongitudeE6() != 0) {
            mapController.animateTo(target);
            mapController.setZoom(HOST_FOCUS_ZOOM_LEVEL);
            mapView.invalidate();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        locationOverlay.enableMyLocation();
        animateToMapTargetIfNeeded();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mapAnimator.clearTarget();
        locationOverlay.disableMyLocation();
    }
}
