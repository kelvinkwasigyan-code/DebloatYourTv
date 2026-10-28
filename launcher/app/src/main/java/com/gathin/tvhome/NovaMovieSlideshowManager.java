package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.LruCache;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages the dynamic movie poster and recommendation background slideshow that
 * activates ONLY when the navigation/focus card for "Nova Video Player" is selected.
 */
public final class NovaMovieSlideshowManager {
    private static final String TAG = "TvHome.NovaSlideshow";
    private static final long SLIDE_INTERVAL_MS = 4000L;
    private static final long CROSSFADE_DURATION_MS = 800L;
    private static final long LAYER_FADE_IN_MS = 450L;
    private static final long LAYER_FADE_OUT_MS = 350L;

    public static class MovieItem {
        public final String title;
        public final String recommendation;
        public final String asset;
        public final String url;

        public MovieItem(String title, String recommendation, String asset, String url) {
            this.title = title;
            this.recommendation = recommendation;
            this.asset = asset;
            this.url = url;
        }
    }

    private final Context context;
    private final FrameLayout layer;
    private final ImageView backdropA;
    private final ImageView backdropB;
    private final View infoContainer;
    private final TextView textBadge;
    private final TextView textTitle;

    private final List<MovieItem> items = new ArrayList<>();
    private final LruCache<String, Bitmap> bitmapCache;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final int screenWidth;
    private final int screenHeight;
    private final File diskCacheDir;

    private boolean isNovaFocused = false;
    private boolean usingA = true;
    private int currentIndex = 0;

    private final Runnable slideshowTick = new Runnable() {
        @Override
        public void run() {
            if (!isNovaFocused || items.isEmpty()) return;
            nextSlide();
            handler.postDelayed(this, SLIDE_INTERVAL_MS);
        }
    };

    public NovaMovieSlideshowManager(Context context,
                                     FrameLayout layer,
                                     ImageView backdropA,
                                     ImageView backdropB,
                                     View infoContainer,
                                     TextView textBadge,
                                     TextView textTitle) {
        this.context = context;
        this.layer = layer;
        this.backdropA = backdropA;
        this.backdropB = backdropB;
        this.infoContainer = infoContainer;
        this.textBadge = textBadge;
        this.textTitle = textTitle;

        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        this.screenWidth = dm.widthPixels;
        this.screenHeight = dm.heightPixels;
        this.diskCacheDir = new File(context.getCacheDir(), "nova_posters");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }

        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = Math.max(1024, maxMemory / 8);
        this.bitmapCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };

        loadFeed();
        // Pre-decode initial slide so it is instant
        if (!items.isEmpty()) {
            executor.execute(() -> loadBitmap(items.get(0)));
        }
    }

    private void loadFeed() {
        items.clear();
        String json = null;

        // 1. Try external customizable file: /sdcard/Android/data/com.gathin.tvhome/files/nova_movies.json
        File externalFile = new File(context.getExternalFilesDir(null), "nova_movies.json");
        if (externalFile.exists() && externalFile.length() > 0) {
            try (FileInputStream fis = new FileInputStream(externalFile)) {
                byte[] data = new byte[(int) externalFile.length()];
                int read = fis.read(data);
                if (read > 0) json = new String(data, 0, read, StandardCharsets.UTF_8);
            } catch (Exception e) {
                Log.w(TAG, "Error reading external nova_movies.json: " + e.getMessage());
            }
        }

        // 2. Try bundled asset file: assets/nova_movies.json
        if (json == null) {
            try (InputStream is = context.getAssets().open("nova_movies.json")) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int n;
                while ((n = is.read(buffer)) != -1) {
                    baos.write(buffer, 0, n);
                }
                json = baos.toString(StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                Log.w(TAG, "Error reading asset nova_movies.json: " + e.getMessage());
            }
        }

        // 3. Parse JSON array
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    String title = obj.optString("title", "Featured Film");
                    String recommendation = obj.optString("recommendation", "RECOMMENDED ON NOVA");
                    String asset = obj.optString("asset", null);
                    String url = obj.optString("url", null);
                    items.add(new MovieItem(title, recommendation, asset, url));
                }
            } catch (Exception e) {
                Log.w(TAG, "Error parsing nova_movies.json: " + e.getMessage());
            }
        }

        // 4. Fallback defaults if list is empty
        if (items.isEmpty()) {
            items.add(new MovieItem("Dune: Part Two", "TRENDING ON NOVA", "movies/dune_part_two.jpg", null));
            items.add(new MovieItem("Oppenheimer", "ACADEMY AWARD WINNER", "movies/oppenheimer.jpg", null));
            items.add(new MovieItem("Interstellar", "SCI-FI MASTERPIECE", "movies/interstellar.jpg", null));
            items.add(new MovieItem("Blade Runner 2049", "CRITICALLY ACCLAIMED", "movies/blade_runner.jpg", null));
            items.add(new MovieItem("Inception", "MIND-BENDING THRILLER", "movies/inception.jpg", null));
        }
    }

    /**
     * Called when the Nova Video Player card gains focus.
     */
    public void onNovaFocused() {
        if (isNovaFocused) return;
        isNovaFocused = true;
        handler.removeCallbacks(slideshowTick);

        // Cancel any pending animations on the layer
        layer.animate().cancel();
        layer.setVisibility(View.VISIBLE);
        layer.animate()
                .alpha(1.0f)
                .setDuration(LAYER_FADE_IN_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        // Show current slide without crossfade for immediate display
        displaySlide(currentIndex, false);

        // Schedule next slide
        handler.postDelayed(slideshowTick, SLIDE_INTERVAL_MS);
    }

    /**
     * Called when focus leaves the Nova Video Player card.
     */
    public void onNovaUnfocused() {
        if (!isNovaFocused) return;
        isNovaFocused = false;
        handler.removeCallbacks(slideshowTick);

        layer.animate().cancel();
        layer.animate()
                .alpha(0.0f)
                .setDuration(LAYER_FADE_OUT_MS)
                .withEndAction(() -> {
                    if (!isNovaFocused) {
                        layer.setVisibility(View.GONE);
                    }
                })
                .start();
    }

    /**
     * Called when launcher is paused or stopped (e.g. user opens an app).
     */
    public void stop() {
        isNovaFocused = false;
        handler.removeCallbacks(slideshowTick);
        layer.animate().cancel();
        layer.setAlpha(0.0f);
        layer.setVisibility(View.GONE);
    }

    public boolean isNovaFocused() {
        return isNovaFocused;
    }

    private void nextSlide() {
        if (items.isEmpty()) return;
        currentIndex = (currentIndex + 1) % items.size();
        displaySlide(currentIndex, true);
    }

    private void displaySlide(int index, boolean crossfade) {
        if (index < 0 || index >= items.size()) return;
        MovieItem item = items.get(index);

        executor.execute(() -> {
            Bitmap bitmap = loadBitmap(item);
            if (bitmap == null) return;

            handler.post(() -> {
                if (!isNovaFocused) return;
                showBitmapOnView(bitmap, item, crossfade);

                // Pre-decode next slide to ensure seamless rotation
                int nextIndex = (index + 1) % items.size();
                executor.execute(() -> loadBitmap(items.get(nextIndex)));
            });
        });
    }

    private void showBitmapOnView(Bitmap bitmap, MovieItem item, boolean crossfade) {
        if (!crossfade) {
            backdropA.setImageBitmap(bitmap);
            backdropA.setAlpha(1.0f);
            backdropB.setImageBitmap(null);
            backdropB.setAlpha(0.0f);
            usingA = true;

            textBadge.setText(item.recommendation);
            textTitle.setText(item.title);
            infoContainer.setAlpha(1.0f);
            return;
        }

        ImageView incoming = usingA ? backdropB : backdropA;
        ImageView outgoing = usingA ? backdropA : backdropB;
        usingA = !usingA;

        incoming.setImageBitmap(bitmap);
        incoming.animate().cancel();
        outgoing.animate().cancel();

        incoming.setAlpha(0.0f);
        incoming.animate()
                .alpha(1.0f)
                .setDuration(CROSSFADE_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        outgoing.animate()
                .alpha(0.0f)
                .setDuration(CROSSFADE_DURATION_MS)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> outgoing.setImageBitmap(null))
                .start();

        // Smoothly update title and recommendation badge
        infoContainer.animate().cancel();
        infoContainer.animate()
                .alpha(0.0f)
                .setDuration(220)
                .withEndAction(() -> {
                    textBadge.setText(item.recommendation);
                    textTitle.setText(item.title);
                    infoContainer.animate()
                            .alpha(1.0f)
                            .setDuration(320)
                            .start();
                })
                .start();
    }

    private Bitmap loadBitmap(MovieItem item) {
        String cacheKey = (item.asset != null ? item.asset : "") + "|" + (item.url != null ? item.url : "");
        Bitmap cached = bitmapCache.get(cacheKey);
        if (cached != null && !cached.isRecycled()) {
            return cached;
        }

        Bitmap decoded = null;

        // 1. Try URL if present (using disk cache)
        if (item.url != null && !item.url.isEmpty()) {
            String hash = String.valueOf(Math.abs(item.url.hashCode()));
            File diskFile = new File(diskCacheDir, "poster_" + hash + ".jpg");
            if (!diskFile.exists() || diskFile.length() == 0) {
                downloadToFile(item.url, diskFile);
            }
            if (diskFile.exists() && diskFile.length() > 0) {
                decoded = decodeFile(diskFile);
            }
        }

        // 2. Try bundled asset if URL decode failed or not available
        if (decoded == null && item.asset != null && !item.asset.isEmpty()) {
            decoded = decodeAsset(item.asset);
        }

        if (decoded != null) {
            bitmapCache.put(cacheKey, decoded);
        }
        return decoded;
    }

    private void downloadToFile(String urlStr, File destFile) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);
            conn.setRequestProperty("User-Agent", "CleanYourTV/3.0");
            conn.connect();
            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(destFile)) {
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = in.read(buffer)) != -1) {
                        out.write(buffer, 0, len);
                    }
                }
            }
        } catch (Exception e) {
            // Ignore failure, will fall back to asset
            if (destFile.exists()) destFile.delete();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private Bitmap decodeAsset(String assetPath) {
        try {
            BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            try (InputStream is = context.getAssets().open(assetPath)) {
                BitmapFactory.decodeStream(is, null, boundsOpts);
            }
            int w = boundsOpts.outWidth;
            int h = boundsOpts.outHeight;
            if (w <= 0 || h <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = WallpaperSize.sampleSize(w, h, screenWidth, screenHeight);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            try (InputStream is = context.getAssets().open(assetPath)) {
                Bitmap bmp = BitmapFactory.decodeStream(is, null, opts);
                if (bmp == null) return null;
                Bitmap hardware = bmp.copy(Bitmap.Config.HARDWARE, false);
                if (hardware != null) {
                    bmp.recycle();
                    return hardware;
                }
                return bmp;
            }
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "Error decoding asset: " + assetPath + " -> " + e.getMessage());
            return null;
        }
    }

    private Bitmap decodeFile(File file) {
        try {
            BitmapFactory.Options boundsOpts = new BitmapFactory.Options();
            boundsOpts.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), boundsOpts);
            int w = boundsOpts.outWidth;
            int h = boundsOpts.outHeight;
            if (w <= 0 || h <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = WallpaperSize.sampleSize(w, h, screenWidth, screenHeight);
            opts.inPreferredConfig = Bitmap.Config.RGB_565;

            Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), opts);
            if (bmp == null) return null;
            Bitmap hardware = bmp.copy(Bitmap.Config.HARDWARE, false);
            if (hardware != null) {
                bmp.recycle();
                return hardware;
            }
            return bmp;
        } catch (Exception | OutOfMemoryError e) {
            Log.w(TAG, "Error decoding file: " + file.getPath() + " -> " + e.getMessage());
            return null;
        }
    }
}

