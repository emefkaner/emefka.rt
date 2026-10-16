package rt.emefka.gallery;

import android.Manifest;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.Base64;
import android.util.Size;
import android.webkit.MimeTypeMap;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;

/**
 * Zugriff auf die Galerie fuer Ausmisten: auflisten, Vorschauen und Bilder liefern,
 * Videos bereitstellen, in den Papierkorb verschieben und Favoriten setzen.
 */
@CapacitorPlugin(
    name = "Gallery",
    permissions = {
        @Permission(alias = "media", strings = { Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO }),
        @Permission(alias = "selected", strings = { "android.permission.READ_MEDIA_VISUAL_USER_SELECTED" }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE }),
        @Permission(alias = "write", strings = { Manifest.permission.WRITE_EXTERNAL_STORAGE })
    }
)
public class GalleryPlugin extends Plugin {

    private static final long MAX_VIDEO_COPY = 400L * 1024 * 1024;

    private final ExecutorService executor = Executors.newFixedThreadPool(3);
    private final List<File> videoCopies = new ArrayList<>();
    private ActivityResultLauncher<IntentSenderRequest> systemRequest;
    private PluginCall pendingCall;
    private JSONArray pendingIds;
    private boolean pendingIsDelete;

    @Override
    public void load() {
        systemRequest = getActivity().registerForActivityResult(
            new ActivityResultContracts.StartIntentSenderForResult(),
            result -> {
                PluginCall call = pendingCall;
                pendingCall = null;
                if (call == null) return;
                boolean ok = result.getResultCode() == Activity.RESULT_OK;
                JSObject r = new JSObject();
                if (pendingIsDelete) {
                    r.put("deleted", ok ? pendingIds : new JSONArray());
                    if (!ok) r.put("cancelled", true);
                } else {
                    r.put("ok", ok);
                }
                call.resolve(r);
            }
        );
    }

    // ---------- Zugriff ----------

    private boolean granted(String alias) {
        return getPermissionState(alias) == PermissionState.GRANTED;
    }

    private String accessState() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (granted("media")) return "granted";
            if (Build.VERSION.SDK_INT >= 34 && granted("selected")) return "limited";
            return getPermissionState("media") == PermissionState.DENIED ? "denied" : "prompt";
        }
        if (granted("storage")) return "granted";
        return getPermissionState("storage") == PermissionState.DENIED ? "denied" : "prompt";
    }

    private void resolveStatus(PluginCall call) {
        JSObject r = new JSObject();
        r.put("status", accessState());
        call.resolve(r);
    }

    private void askForAccess(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissionForAliases(new String[] { "media", "selected" }, call, "accessResult");
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissionForAlias("media", call, "accessResult");
        } else if (Build.VERSION.SDK_INT <= 29) {
            requestPermissionForAliases(new String[] { "storage", "write" }, call, "accessResult");
        } else {
            requestPermissionForAlias("storage", call, "accessResult");
        }
    }

    @PluginMethod
    public void status(PluginCall call) {
        resolveStatus(call);
    }

    @PluginMethod
    public void requestAccess(PluginCall call) {
        if ("granted".equals(accessState())) {
            resolveStatus(call);
            return;
        }
        askForAccess(call);
    }

    /** Android 14: Auswahl der freigegebenen Fotos aendern. */
    @PluginMethod
    public void manageLimited(PluginCall call) {
        askForAccess(call);
    }

    @PermissionCallback
    private void accessResult(PluginCall call) {
        resolveStatus(call);
    }

    @PluginMethod
    public void openSettings(PluginCall call) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", getContext().getPackageName(), null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        call.resolve();
    }

    // ---------- Auflisten ----------

    @PluginMethod
    public void list(PluginCall call) {
        executor.execute(() -> {
            try {
                JSArray items = new JSArray();
                queryInto(items, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false);
                queryInto(items, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true);
                JSObject r = new JSObject();
                r.put("items", items);
                call.resolve(r);
            } catch (Exception e) {
                call.reject("Galerie konnte nicht gelesen werden", e);
            }
        });
    }

    private void queryInto(JSArray out, Uri base, boolean video) {
        ArrayList<String> cols = new ArrayList<>();
        cols.add(MediaStore.MediaColumns._ID);
        cols.add(MediaStore.MediaColumns.DISPLAY_NAME);
        cols.add(MediaStore.MediaColumns.SIZE);
        cols.add("datetaken");
        cols.add(MediaStore.MediaColumns.DATE_MODIFIED);
        cols.add(MediaStore.MediaColumns.WIDTH);
        cols.add(MediaStore.MediaColumns.HEIGHT);
        cols.add(Build.VERSION.SDK_INT >= 29 ? MediaStore.MediaColumns.RELATIVE_PATH : MediaStore.MediaColumns.DATA);
        if (video) cols.add(MediaStore.Video.VideoColumns.DURATION);
        if (Build.VERSION.SDK_INT >= 30) cols.add(MediaStore.MediaColumns.IS_FAVORITE);

        ContentResolver cr = getContext().getContentResolver();
        try (Cursor c = cr.query(base, cols.toArray(new String[0]), null, null, null)) {
            if (c == null) return;
            int iId = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID);
            int iName = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME);
            int iSize = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE);
            int iTaken = c.getColumnIndexOrThrow("datetaken");
            int iMod = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED);
            int iW = c.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH);
            int iH = c.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT);
            int iPath = c.getColumnIndexOrThrow(cols.get(7));
            int iDur = video ? c.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) : -1;
            int iFav = Build.VERSION.SDK_INT >= 30 ? c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_FAVORITE) : -1;
            while (c.moveToNext()) {
                long taken = c.getLong(iTaken);
                if (taken <= 0) taken = c.getLong(iMod) * 1000L;
                String path = c.isNull(iPath) ? "" : c.getString(iPath);
                String name = c.isNull(iName) ? "Foto" : c.getString(iName);
                JSObject o = new JSObject();
                o.put("id", (video ? "v:" : "i:") + c.getLong(iId));
                o.put("name", name);
                o.put("size", c.getLong(iSize));
                o.put("date", (double) taken);
                o.put("video", video);
                o.put("duration", video ? c.getLong(iDur) / 1000.0 : 0);
                o.put("width", c.getInt(iW));
                o.put("height", c.getInt(iH));
                o.put("favorite", iFav >= 0 && c.getInt(iFav) == 1);
                o.put("screenshot", (path + "/" + name).toLowerCase(Locale.ROOT).contains("screenshot"));
                out.put(o);
            }
        }
    }

    // ---------- Bilder ----------

    private Uri uriFor(String id) {
        boolean video = id.startsWith("v:");
        long n = Long.parseLong(id.substring(2));
        return ContentUris.withAppendedId(video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI, n);
    }

    private static String dataUrl(Bitmap bm, int quality) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bm.compress(Bitmap.CompressFormat.JPEG, quality, bos);
        return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }

    private static JSObject dataResult(String data) {
        JSObject r = new JSObject();
        r.put("data", data);
        return r;
    }

    @PluginMethod
    public void thumbnail(PluginCall call) {
        String id = call.getString("id");
        int size = call.getInt("size", 240);
        if (id == null) {
            call.reject("id fehlt");
            return;
        }
        executor.execute(() -> {
            try {
                ContentResolver cr = getContext().getContentResolver();
                Bitmap bm;
                if (Build.VERSION.SDK_INT >= 29) {
                    bm = cr.loadThumbnail(uriFor(id), new Size(size, size), null);
                } else if (id.startsWith("v:")) {
                    bm = MediaStore.Video.Thumbnails.getThumbnail(cr, Long.parseLong(id.substring(2)), MediaStore.Video.Thumbnails.MINI_KIND, null);
                } else {
                    bm = MediaStore.Images.Thumbnails.getThumbnail(cr, Long.parseLong(id.substring(2)), MediaStore.Images.Thumbnails.MINI_KIND, null);
                }
                if (bm == null) {
                    call.reject("Keine Vorschau");
                    return;
                }
                call.resolve(dataResult(dataUrl(bm, 75)));
            } catch (Exception e) {
                call.reject("Keine Vorschau", e);
            }
        });
    }

    @PluginMethod
    public void image(PluginCall call) {
        String id = call.getString("id");
        int max = call.getInt("max", 2048);
        if (id == null) {
            call.reject("id fehlt");
            return;
        }
        executor.execute(() -> {
            try {
                Bitmap bm = Build.VERSION.SDK_INT >= 28 ? decodeModern(uriFor(id), max) : decodeLegacy(uriFor(id), max);
                if (bm == null) {
                    call.reject("Bild nicht lesbar");
                    return;
                }
                call.resolve(dataResult(dataUrl(bm, 85)));
            } catch (Exception e) {
                call.reject("Bild nicht lesbar", e);
            }
        });
    }

    private Bitmap decodeModern(Uri uri, int max) throws Exception {
        ImageDecoder.Source src = ImageDecoder.createSource(getContext().getContentResolver(), uri);
        // ImageDecoder beachtet die EXIF-Ausrichtung selbst.
        return ImageDecoder.decodeBitmap(src, (decoder, info, source) -> {
            int w = info.getSize().getWidth(), h = info.getSize().getHeight();
            float sc = Math.min(1f, max / (float) Math.max(w, h));
            decoder.setTargetSize(Math.max(1, Math.round(w * sc)), Math.max(1, Math.round(h * sc)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    private Bitmap decodeLegacy(Uri uri, int max) throws Exception {
        ContentResolver cr = getContext().getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        try (InputStream in = cr.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, o);
        }
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= max) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap bm;
        try (InputStream in = cr.openInputStream(uri)) {
            bm = BitmapFactory.decodeStream(in, null, o2);
        }
        if (bm == null) return null;
        int rot = 0;
        try (InputStream in = cr.openInputStream(uri)) {
            int ori = new ExifInterface(in).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
        } catch (Exception ignored) {}
        if (rot == 0) return bm;
        Matrix m = new Matrix();
        m.postRotate(rot);
        return Bitmap.createBitmap(bm, 0, 0, bm.getWidth(), bm.getHeight(), m, true);
    }

    // ---------- Videos ----------

    /** Kopiert das Video in den Cache, damit die Web-Ansicht es abspielen kann. */
    @PluginMethod
    public void video(PluginCall call) {
        String id = call.getString("id");
        if (id == null || !id.startsWith("v:")) {
            call.reject("Kein Video");
            return;
        }
        executor.execute(() -> {
            try {
                ContentResolver cr = getContext().getContentResolver();
                Uri uri = uriFor(id);
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(cr.getType(uri));
                File dir = new File(getContext().getCacheDir(), "videos");
                if (!dir.exists() && !dir.mkdirs()) throw new Exception("Cache nicht verfuegbar");
                File dest = new File(dir, "video-" + UUID.randomUUID() + "." + (ext == null ? "mp4" : ext));
                long total = 0;
                try (InputStream in = cr.openInputStream(uri); OutputStream os = new FileOutputStream(dest)) {
                    if (in == null) throw new Exception("Video nicht lesbar");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        if (total > MAX_VIDEO_COPY) throw new Exception("Video zu gross fuer die Vorschau");
                        os.write(buf, 0, n);
                    }
                } catch (Exception e) {
                    dest.delete();
                    throw e;
                }
                synchronized (videoCopies) {
                    videoCopies.add(dest);
                    while (videoCopies.size() > 4) videoCopies.remove(0).delete();
                }
                JSObject r = new JSObject();
                r.put("path", dest.getAbsolutePath());
                call.resolve(r);
            } catch (Exception e) {
                call.reject("Video nicht verfuegbar", e);
            }
        });
    }

    // ---------- Loeschen und Favoriten ----------

    private List<Uri> urisFor(JSONArray ids) throws Exception {
        List<Uri> uris = new ArrayList<>();
        for (int i = 0; i < ids.length(); i++) uris.add(uriFor(ids.getString(i)));
        return uris;
    }

    private void launchSystemRequest(PluginCall call, PendingIntent pi, JSONArray ids, boolean isDelete) {
        pendingCall = call;
        pendingIds = ids;
        pendingIsDelete = isDelete;
        getActivity().runOnUiThread(() -> systemRequest.launch(new IntentSenderRequest.Builder(pi.getIntentSender()).build()));
    }

    /** Ab Android 11 in den Papierkorb der Galerie (30 Tage), mit Rueckfrage des Systems. */
    @PluginMethod
    public void delete(PluginCall call) {
        JSArray ids = call.getArray("ids", new JSArray());
        try {
            List<Uri> uris = urisFor(ids);
            if (uris.isEmpty()) {
                JSObject r = new JSObject();
                r.put("deleted", new JSONArray());
                call.resolve(r);
                return;
            }
            if (Build.VERSION.SDK_INT >= 30) {
                PendingIntent pi = MediaStore.createTrashRequest(getContext().getContentResolver(), uris, true);
                launchSystemRequest(call, pi, ids, true);
                return;
            }
            JSONArray deleted = new JSONArray();
            ContentResolver cr = getContext().getContentResolver();
            for (int i = 0; i < uris.size(); i++) {
                try {
                    if (cr.delete(uris.get(i), null, null) > 0) deleted.put(ids.getString(i));
                } catch (Exception ignored) {}
            }
            JSObject r = new JSObject();
            r.put("deleted", deleted);
            call.resolve(r);
        } catch (Exception e) {
            call.reject("Loeschen fehlgeschlagen", e);
        }
    }

    @PluginMethod
    public void setFavorite(PluginCall call) {
        JSArray ids = call.getArray("ids", new JSArray());
        boolean value = call.getBoolean("value", true);
        if (Build.VERSION.SDK_INT < 30 || ids.length() == 0) {
            JSObject r = new JSObject();
            r.put("ok", Build.VERSION.SDK_INT >= 30);
            call.resolve(r);
            return;
        }
        try {
            PendingIntent pi = MediaStore.createFavoriteRequest(getContext().getContentResolver(), urisFor(ids), value);
            launchSystemRequest(call, pi, ids, false);
        } catch (Exception e) {
            call.reject("Favoriten konnten nicht gesetzt werden", e);
        }
    }
}
