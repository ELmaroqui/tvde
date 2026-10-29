package app.minimaltv.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Keeps a small local cache of nature pictures (Wikimedia Commons featured pictures,
 * freely licensed) and hands them out in random order. Everything is cached on the TV,
 * so showing a wallpaper never waits for the network. The cache is refreshed weekly.
 */
final class Wallpapers {

    private static final String UA = "MinimalTvLauncher/1.0 (personal home screen; Android TV)";

    // Commons categories tried in order. A category that does not exist is simply ignored.
    private static final String[] CATEGORIES = {
            "Featured_pictures_of_landscapes",
            "Featured_pictures_of_nature",
            "Featured_pictures_of_mountains",
            "Featured_pictures_of_forests",
            "Featured_pictures_of_waterfalls",
            "Featured_pictures_of_beaches",
    };

    private static final long MAX_AGE_MS = 7L * 24 * 3600 * 1000;
    private static final int WANTED = 12;
    private static final long MAX_FILE_BYTES = 12000000L;

    private final File dir;
    private final Random rnd = new Random();
    private final ArrayList<File> queue = new ArrayList<>();

    Wallpapers(Context ctx) {
        dir = new File(ctx.getFilesDir(), "wallpapers");
        dir.mkdirs();
    }

    private File[] files() {
        File[] f = dir.listFiles((d, name) -> name.endsWith(".jpg"));
        return f == null ? new File[0] : f;
    }

    /** Next cached picture, decoded to roughly 1920 px wide. Call from a background thread. */
    Bitmap nextBitmap() {
        if (queue.isEmpty()) {
            Collections.addAll(queue, files());
            Collections.shuffle(queue, rnd);
        }
        while (!queue.isEmpty()) {
            File f = queue.remove(queue.size() - 1);
            Bitmap b = decode(f);
            if (b != null) return b;
            f.delete();
        }
        return null;
    }

    private Bitmap decode(File f) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), o);
            if (o.outWidth <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= 1920) sample *= 2;
            BitmapFactory.Options d = new BitmapFactory.Options();
            d.inSampleSize = sample;
            d.inPreferredConfig = Bitmap.Config.ARGB_8888;
            return BitmapFactory.decodeFile(f.getPath(), d);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Downloads a fresh set if the cache is empty or older than a week. Background thread only. */
    void refreshIfNeeded() {
        try {
            File marker = new File(dir, "last_refresh");
            boolean fresh = marker.exists()
                    && System.currentTimeMillis() - marker.lastModified() < MAX_AGE_MS;
            if (fresh && files().length >= 4) return;

            List<String> urls = fetchUrls();
            if (urls.size() < 3) return; // offline or categories empty: keep what we have
            Collections.shuffle(urls, rnd);

            Set<String> keep = new HashSet<>();
            int ok = 0;
            for (String u : urls) {
                if (ok >= WANTED) break;
                File out = new File(dir, "w" + System.currentTimeMillis() + "_" + ok + ".jpg");
                if (download(u, out)) {
                    keep.add(out.getName());
                    ok++;
                }
            }
            if (ok >= 3) {
                for (File f : files()) {
                    if (!keep.contains(f.getName())) f.delete();
                }
                marker.createNewFile();
                marker.setLastModified(System.currentTimeMillis());
            }
        } catch (Exception ignored) {
        }
    }

    private List<String> fetchUrls() {
        List<String> urls = new ArrayList<>();
        for (String cat : CATEGORIES) {
            try {
                String api = "https://commons.wikimedia.org/w/api.php?action=query&format=json"
                        + "&generator=categorymembers"
                        + "&gcmtitle=" + URLEncoder.encode("Category:" + cat, "UTF-8")
                        + "&gcmtype=file&gcmlimit=50&prop=imageinfo"
                        + "&iiprop=url%7Csize%7Cmime&iiurlwidth=1920";
                String body = httpGet(api);
                if (body == null) continue;
                JSONObject q = new JSONObject(body).optJSONObject("query");
                if (q == null) continue;
                JSONObject pages = q.optJSONObject("pages");
                if (pages == null) continue;
                Iterator<String> it = pages.keys();
                while (it.hasNext()) {
                    JSONObject p = pages.getJSONObject(it.next());
                    JSONArray info = p.optJSONArray("imageinfo");
                    if (info == null || info.length() == 0) continue;
                    JSONObject ii = info.getJSONObject(0);
                    if (!"image/jpeg".equals(ii.optString("mime"))) continue;
                    int w = ii.optInt("thumbwidth", 0);
                    int h = ii.optInt("thumbheight", 0);
                    String u = ii.optString("thumburl", "");
                    if (w >= 1500 && w > h && !u.isEmpty()) urls.add(u);
                }
            } catch (Exception ignored) {
            }
        }
        return urls;
    }

    private String httpGet(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return null;
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private boolean download(String address, File out) {
        HttpURLConnection c = null;
        File tmp = new File(out.getPath() + ".part");
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setRequestProperty("User-Agent", UA);
            if (c.getResponseCode() != 200) return false;
            long total = 0;
            try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    os.write(buf, 0, n);
                    total += n;
                    if (total > MAX_FILE_BYTES) throw new IOException("file too large");
                }
            }
            return tmp.renameTo(out);
        } catch (Exception e) {
            tmp.delete();
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
