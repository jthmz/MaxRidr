package com.maxapacity.ride;

import static org.junit.Assert.*;
import android.app.Activity;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;
import android.widget.*;
import java.io.InputStream;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, shadows=RideShareTest.HostImageSource.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RideShareTest {
    // The native host renderer supports byte buffers. Android's native content-URI
    // decoder requires a device file descriptor unavailable to Robolectric on macOS.
    @Implements(value=android.graphics.ImageDecoder.class, callNativeMethodsByDefault=true)
    public static class HostImageSource extends org.robolectric.shadows.ShadowNativeImageDecoder {
        @Implementation
        protected static android.graphics.ImageDecoder.Source createSource(ContentResolver resolver, Uri uri) {
            try (InputStream input = resolver.openInputStream(uri)) {
                return android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(input.readAllBytes()));
            } catch (java.io.IOException e) { throw new IllegalStateException(e); }
        }
    }

    @Test public void photoPickerPreviewAndShareProduceFreshCard() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Intent ride = RideShareActivity.forRide(c, new JSONObject().put("distanceM", 1500).put("movingMs", 300000));
        try (ActivityController<RideShareActivity> controller = Robolectric.buildActivity(RideShareActivity.class, ride).setup()) {
            RideShareActivity screen = controller.get();
            View root = screen.getWindow().getDecorView();
            CheckBox distance = ScreensTest.find(root, CheckBox.class, null);
            distance.performClick();
            assertFalse(distance.isChecked());
            click(root, "Choose photo");
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, Shadows.shadowOf(screen).getNextStartedActivityForResult().intent.getAction());
            Bitmap photo = Bitmap.createBitmap(70, 60, Bitmap.Config.ARGB_8888);
            photo.eraseColor(android.graphics.Color.MAGENTA);
            Uri original = RideCardProvider.save(c, photo);
            screen.onActivityResult(1, Activity.RESULT_OK, new Intent().setData(original));
            View remove = button(root, "Remove photo");
            for (int i=0; i<150 && remove.getVisibility()!=View.VISIBLE; i++) {
                Thread.sleep(20);
                ScreensTest.idle(50);
            }
            if (remove.getVisibility() != View.VISIBLE) {
                for (org.robolectric.shadows.ShadowLog.LogItem log : org.robolectric.shadows.ShadowLog.getLogsForTag("MaxRidr")) {
                    if (log.throwable != null) throw new AssertionError(log.msg, log.throwable);
                }
            }
            assertEquals(View.VISIBLE, remove.getVisibility());
            click(root, "Share card");
            Intent chooser = null;
            for (int i=0; i<150 && chooser==null; i++) {
                Thread.sleep(20);
                ScreensTest.idle(50);
                chooser = Shadows.shadowOf(screen).getNextStartedActivity();
                if (chooser != null && !Intent.ACTION_CHOOSER.equals(chooser.getAction())) chooser = null;
            }
            assertNotNull(chooser);
            Intent send = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
            Uri card = send.getParcelableExtra(Intent.EXTRA_STREAM);
            assertNotEquals(original, card);
            assertEquals(card, send.getClipData().getItemAt(0).getUri());
            assertEquals("image/jpeg", send.getType());
            assertTrue((send.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            try (InputStream input = c.getContentResolver().openInputStream(card)) {
                Bitmap decoded = BitmapFactory.decodeStream(input);
                assertEquals(960, decoded.getWidth());
                int pixel = decoded.getPixel(480, 380);
                assertTrue(android.graphics.Color.red(pixel)>220 && android.graphics.Color.blue(pixel)>220);
            }
        }
    }

    private static View button(View root, String text) {
        View label = ScreensTest.find(root, TextView.class, text);
        while (!label.hasOnClickListeners()) label = (View)label.getParent();
        return label;
    }
    private static void click(View root, String text) { button(root,text).performClick(); }
}
