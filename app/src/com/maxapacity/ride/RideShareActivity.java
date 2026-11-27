package com.maxapacity.ride;

import android.app.Activity;
import android.content.*;
import android.graphics.*;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** The share target receives only the pixels the rider previews here. */
public final class RideShareActivity extends Activity {
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private LinearLayout root;
    private ImageView preview;
    private View send, remove;
    private Bitmap photo, card, logo;
    private Uri selected;
    private int revision;
    private final boolean[] include = {true, true, true};
    private final String[] labels = {"Distance", "Moving time", "Gearing"};

    static Intent forRide(Context c, JSONObject ride) {
        Intent intent = new Intent(c, RideShareActivity.class);
        if (ride.has("distanceM")) intent.putExtra("Distance", Units.distance(c, ride.optDouble("distanceM")) + " " + Units.distanceLabel(c));
        if (ride.has("movingMs")) intent.putExtra("Moving time", Units.duration(ride.optLong("movingMs")));
        JSONObject g = ride.optJSONObject("gearing");
        if (g != null && g.optInt("front") > 0 && g.optInt("rear") > 0)
            intent.putExtra("Gearing", g.optInt("front") + "T / " + g.optInt("rear") + "T");
        return intent;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        logo = BitmapFactory.decodeResource(getResources(), R.drawable.maxapacity_lockup);
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        scroll.setBackgroundColor(Ui.BG);
        root = Ui.column(this);
        int pad = Ui.dp(this, 16);
        root.setPadding(pad,pad,pad,pad);
        scroll.addView(root);
        setContentView(scroll);
        root.addView(Ui.title(this, "Share ride card"));
        root.addView(Ui.body(this, "Choose what appears on your card. Your route stays private.", Ui.TEXT_2));
        preview = new ImageView(this);
        preview.setContentDescription("Ride card preview");
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(preview, new LinearLayout.LayoutParams(-1, Ui.dp(this, 256)));
        for (int i = 0; i < labels.length; i++) {
            final int index = i;
            String label = labels[i];
            if (!getIntent().hasExtra(label)) continue;
            CheckBox box = new CheckBox(this);
            box.setText(label + " · " + getIntent().getStringExtra(label));
            box.setTextColor(Ui.TEXT);
            include[i] = state == null || state.getBoolean(label, true);
            box.setChecked(include[i]);
            box.setOnCheckedChangeListener((v, checked) -> { include[index] = checked; render(); });
            root.addView(box);
        }
        action("Choose photo", Icons.FLAG, Ui.SECONDARY, () -> startActivityForResult(
                new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), 1));
        remove = action("Remove photo", Icons.X, Ui.SECONDARY, () -> {
            revision++; selected = null; photo = null; send.setEnabled(true); render();
        });
        send = action("Share card", Icons.ROUTE, Ui.PRIMARY, this::share);
        action("Back to ride", Icons.X, Ui.SECONDARY, this::finish);
        render();
        if (state != null && state.getString("photo") != null) load(Uri.parse(state.getString("photo")));
    }

    private View action(String label, String icon, int style, Runnable handler) {
        View v = Ui.button(this, label, icon, style);
        root.addView(v, Ui.top(this, Ui.matchWrap(), 8));
        v.setOnClickListener(button -> handler.run());
        return v;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (selected != null) state.putString("photo", selected.toString());
        for (int i = 0; i < labels.length; i++) state.putBoolean(labels[i], include[i]);
    }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1 && result == RESULT_OK && data != null && data.getData() != null
                && "content".equals(data.getData().getScheme())) load(data.getData());
    }

    private void load(Uri uri) {
        int version = ++revision;
        selected = uri;
        send.setEnabled(false);
        io.execute(() -> {
            Bitmap decoded = null;
            try {
                decoded = ImageDecoder.decodeBitmap(ImageDecoder.createSource(getContentResolver(), uri), (decoder, info, source) -> {
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    double scale = Math.min(1, 1200.0 / Math.max(w,h));
                    decoder.setTargetSize(Math.max(1,(int)(w*scale)), Math.max(1,(int)(h*scale)));
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (IOException | RuntimeException e) {
                android.util.Log.w("MaxRidr", "Could not decode ride photo", e);
            }
            Bitmap result = decoded;
            runOnUiThread(() -> {
                if (isDestroyed() || version != revision) return;
                photo = result;
                if (photo == null) { selected = null; message("Couldn’t open that photo. Choose another image."); }
                send.setEnabled(true);
                render();
            });
        });
    }

    private void render() {
        card = Bitmap.createBitmap(960, photo == null ? 550 : 1050, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(card);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawColor(Ui.BG);
        canvas.drawBitmap(logo, null, new Rect(48, 32, 332, 110), paint);
        int y = 145;
        if (photo != null) {
            float scale = Math.min(864f/photo.getWidth(), 480f/photo.getHeight());
            float w = photo.getWidth()*scale, h = photo.getHeight()*scale;
            float top = y+(480-h)/2;
            canvas.drawBitmap(photo, null, new RectF((960-w)/2,top,(960+w)/2,top+h), paint);
            y += 500;
        }
        paint.setColor(Ui.ACCENT);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(64);
        canvas.drawText("My ride", 48, y+68, paint);
        y += 90;
        for (int i = 0; i < labels.length; i++) {
            String value = getIntent().getStringExtra(labels[i]);
            if (!include[i] || value == null) continue;
            y += 80;
            paint.setTextSize(36);
            paint.setColor(Ui.TEXT_2);
            canvas.drawText(labels[i], 48, y, paint);
            paint.setColor(Ui.TEXT);
            canvas.drawText(value, 912-paint.measureText(value), y, paint);
        }
        preview.setImageBitmap(card);
        remove.setVisibility(photo == null ? View.GONE : View.VISIBLE);
    }

    private void share() {
        Bitmap snapshot = card;
        send.setEnabled(false);
        io.execute(() -> {
            try {
                Uri uri = RideCardProvider.save(this, snapshot);
                Intent intent = new Intent(Intent.ACTION_SEND).setType("image/jpeg")
                        .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.setClipData(ClipData.newRawUri("MaxRidr ride card", uri));
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) return;
                    send.setEnabled(true);
                    startActivity(Intent.createChooser(intent, "Share ride card"));
                });
            } catch (IOException e) {
                runOnUiThread(() -> { if (!isDestroyed()) { send.setEnabled(true); message("Couldn’t save the card. Try again."); } });
            }
        });
    }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    @Override protected void onDestroy() { io.shutdown(); super.onDestroy(); }
}
