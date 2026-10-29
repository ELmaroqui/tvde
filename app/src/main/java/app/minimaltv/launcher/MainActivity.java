package app.minimaltv.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Minimal Apple TV-style home screen: rotating nature wallpaper, clock, and a dock of favourite apps. */
public class MainActivity extends Activity {

    private static final long ROTATE_MS = 10 * 60 * 1000L;

    // {package, label}. A package that is missing or disabled is skipped automatically.
    private static final String[][] FAVORITES = {
            {"com.mediatek.wwtv.tvcenter", "TV"},
            {"com.netflix.ninja", "Netflix"},
            {"com.google.android.youtube.tv", "YouTube"},
            {"com.amazon.amazonvideo.livingroom", "Prime Video"},
            {"com.disney.disneyplus", "Disney+"},
            {"com.crunchyroll.crunchyroid", "Crunchyroll"},
            {"tv.twitch.android.app", "Twitch"},
            {"be.rtbf.auvio", "Auvio"},
            {"com.android.tv.settings", "R\u00e9glages"},
    };

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkIo = Executors.newSingleThreadExecutor();
    private final ExecutorService decodeIo = Executors.newSingleThreadExecutor();

    private Wallpapers wallpapers;
    private ImageView imgA;
    private ImageView imgB;
    private boolean nextOnB = true;
    private LinearLayout dock;
    private FrameLayout overlay;
    private LinearLayout appsRows;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            showNextWallpaper();
            handler.postDelayed(this, ROTATE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        wallpapers = new Wallpapers(this);
        buildUi();
        dock.post(new Runnable() {
            @Override
            public void run() {
                if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
            }
        });
        // Download pictures in the background if needed, then show one.
        networkIo.execute(new Runnable() {
            @Override
            public void run() {
                wallpapers.refreshIfNeeded();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        showNextWallpaper();
                    }
                });
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.removeCallbacks(rotate);
        handler.post(rotate);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(rotate);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        networkIo.shutdownNow();
        decodeIo.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home key pressed while already on the home screen.
        if (overlay.getVisibility() == View.VISIBLE) overlay.setVisibility(View.GONE);
        if (dock.getChildCount() > 0) dock.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        if (overlay.getVisibility() == View.VISIBLE) {
            hideApps();
        }
        // Otherwise stay on the home screen.
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0F14);
        root.setClipChildren(false);

        imgA = newWallpaperView();
        imgB = newWallpaperView();
        imgB.setAlpha(0f);
        root.addView(imgA, new FrameLayout.LayoutParams(MATCH, MATCH));
        root.addView(imgB, new FrameLayout.LayoutParams(MATCH, MATCH));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00000000, 0xCC000000}));
        root.addView(shade, new FrameLayout.LayoutParams(MATCH, dp(380), Gravity.BOTTOM));

        // Clock, top right
        LinearLayout clockBox = new LinearLayout(this);
        clockBox.setOrientation(LinearLayout.VERTICAL);
        clockBox.setGravity(Gravity.END);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(44);
        time.setShadowLayer(10, 0, 2, 0x99000000);
        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d MMMM");
        date.setFormat12Hour("EEEE d MMMM");
        date.setTextColor(0xDDFFFFFF);
        date.setTextSize(18);
        date.setShadowLayer(8, 0, 2, 0x99000000);
        clockBox.addView(time, new LinearLayout.LayoutParams(WRAP, WRAP));
        clockBox.addView(date, new LinearLayout.LayoutParams(WRAP, WRAP));
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.END);
        clp.setMargins(0, dp(36), dp(56), 0);
        root.addView(clockBox, clp);

        // Dock, bottom centre
        dock = new LinearLayout(this);
        dock.setOrientation(LinearLayout.HORIZONTAL);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.setClipChildren(false);
        dock.setClipToPadding(false);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x66000000);
        pill.setCornerRadius(dp(34));
        dock.setBackground(pill);
        dock.setPadding(dp(20), dp(14), dp(20), dp(8));
        populateDock();
        FrameLayout.LayoutParams dlp = new FrameLayout.LayoutParams(WRAP, WRAP,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        dlp.bottomMargin = dp(48);
        root.addView(dock, dlp);

        // "All apps" overlay
        overlay = new FrameLayout(this);
        overlay.setBackgroundColor(0xF2101418);
        overlay.setVisibility(View.GONE);
        overlay.setClickable(true);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setClipChildren(false);
        appsRows = new LinearLayout(this);
        appsRows.setOrientation(LinearLayout.VERTICAL);
        appsRows.setGravity(Gravity.CENTER_HORIZONTAL);
        appsRows.setClipChildren(false);
        appsRows.setPadding(dp(60), dp(110), dp(60), dp(60));
        sv.addView(appsRows, new FrameLayout.LayoutParams(MATCH, WRAP));
        overlay.addView(sv, new FrameLayout.LayoutParams(MATCH, MATCH));
        TextView title = new TextView(this);
        title.setText("Applications");
        title.setTextColor(Color.WHITE);
        title.setTextSize(26);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP | Gravity.START);
        tlp.setMargins(dp(60), dp(40), 0, 0);
        overlay.addView(title, tlp);
        root.addView(overlay, new FrameLayout.LayoutParams(MATCH, MATCH));

        setContentView(root);
    }

    private ImageView newWallpaperView() {
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return iv;
    }

    private void populateDock() {
        PackageManager pm = getPackageManager();
        dock.removeAllViews();
        for (String[] f : FAVORITES) {
            final Intent launch = launchIntent(pm, f[0]);
            if (launch == null) continue;
            Drawable icon;
            try {
                icon = pm.getApplicationIcon(f[0]);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            dock.addView(makeItem(icon, f[1], new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    start(launch);
                }
            }), itemParams());
        }
        dock.addView(makeItem(new GridIcon(), "Applis", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showApps();
            }
        }), itemParams());
    }

    private LinearLayout.LayoutParams itemParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.setMargins(dp(3), 0, dp(3), 0);
        return lp;
    }

    private View makeItem(Drawable icon, String label, View.OnClickListener click) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER_HORIZONTAL);
        item.setFocusable(true);
        item.setClickable(true);
        item.setPadding(dp(8), dp(8), dp(8), dp(8));
        item.setClipChildren(false);

        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icon);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(18));
            }
        });
        iv.setClipToOutline(true);
        item.addView(iv, new LinearLayout.LayoutParams(dp(80), dp(80)));

        final TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setAlpha(0f);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(96), WRAP);
        tlp.topMargin = dp(6);
        item.addView(tv, tlp);

        item.setOnClickListener(click);
        item.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? 1.2f : 1f;
                v.animate().scaleX(s).scaleY(s).setDuration(140).start();
                tv.animate().alpha(hasFocus ? 1f : 0f).setDuration(140).start();
            }
        });
        return item;
    }

    private Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    private void start(Intent i) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Impossible d'ouvrir l'application", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- all apps overlay

    private void showApps() {
        PackageManager pm = getPackageManager();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> list = pm.queryIntentActivities(q, 0);
        Collections.sort(list, new ResolveInfo.DisplayNameComparator(pm));

        appsRows.removeAllViews();
        LinearLayout row = null;
        int col = 0;
        View first = null;
        for (ResolveInfo ri : list) {
            if (getPackageName().equals(ri.activityInfo.packageName)) continue;
            if (row == null || col == 7) {
                row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setClipChildren(false);
                appsRows.addView(row, new LinearLayout.LayoutParams(WRAP, WRAP));
                col = 0;
            }
            final Intent i = new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                    .setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
            View item = makeItem(ri.loadIcon(pm), String.valueOf(ri.loadLabel(pm)),
                    new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            hideApps();
                            start(i);
                        }
                    });
            row.addView(item, itemParams());
            if (first == null) first = item;
            col++;
        }
        overlay.setVisibility(View.VISIBLE);
        if (first != null) first.requestFocus();
    }

    private void hideApps() {
        overlay.setVisibility(View.GONE);
        if (dock.getChildCount() > 0) dock.getChildAt(dock.getChildCount() - 1).requestFocus();
    }

    // ---------------------------------------------------------------- wallpapers

    private void showNextWallpaper() {
        decodeIo.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = wallpapers.nextBitmap();
                if (bmp != null) {
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            crossfadeTo(bmp);
                        }
                    });
                }
            }
        });
    }

    private void crossfadeTo(Bitmap bmp) {
        if (nextOnB) {
            imgB.setImageBitmap(bmp);
            imgB.animate().alpha(1f).setDuration(1500).start();
        } else {
            imgA.setImageBitmap(bmp);
            imgB.animate().alpha(0f).setDuration(1500).start();
        }
        nextOnB = !nextOnB;
    }

    // ---------------------------------------------------------------- helpers

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Simple 3x3 dots icon for the "all apps" tile. */
    private static final class GridIcon extends Drawable {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);

        GridIcon() {
            bg.setColor(0x55FFFFFF);
            dot.setColor(Color.WHITE);
        }

        @Override
        public void draw(Canvas c) {
            Rect b = getBounds();
            float w = b.width();
            c.drawRoundRect(new RectF(b), w * 0.22f, w * 0.22f, bg);
            for (int r = 0; r < 3; r++) {
                for (int k = 0; k < 3; k++) {
                    float cx = b.left + w * (0.25f + 0.25f * k);
                    float cy = b.top + b.height() * (0.25f + 0.25f * r);
                    c.drawCircle(cx, cy, w * 0.06f, dot);
                }
            }
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
