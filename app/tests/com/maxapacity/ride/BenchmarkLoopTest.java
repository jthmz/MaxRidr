package com.maxapacity.ride;

import static org.junit.Assert.*;
import java.util.Arrays;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35)
public class BenchmarkLoopTest {
    @Test public void matchesRouteDespiteRecordingDensityAndSmallDrift() {
        double[] a = {40, -111}, b = {40.01, -111}, c = {40.009, -110.99};
        JSONObject baseline = BenchmarkLoop.shape(Arrays.asList(a,b,c,a));
        assertTrue(BenchmarkLoop.matches(baseline, BenchmarkLoop.shape(Arrays.asList(
                new double[]{40.00003,-111}, new double[]{40.005,-111},b,c,a))));
        assertFalse(BenchmarkLoop.matches(baseline, BenchmarkLoop.shape(Arrays.asList(a,c,b,a))));
        assertFalse(BenchmarkLoop.matches(baseline, BenchmarkLoop.shape(Arrays.asList(a,b,new double[]{40.009,-111.01},a))));
        assertNull(BenchmarkLoop.shape(Arrays.asList(a,a,a)));
    }

    @Test public void shareCardExposesOnlyPreviewFieldsAndReadOnlyImage() throws Exception {
        Context c = RuntimeEnvironment.getApplication();
        Intent intent = RideShareActivity.forRide(c, new JSONObject().put("distanceM", 1200)
                .put("source", "private phone").put("file", "secret.gpx").put("start", 4000));
        assertEquals(java.util.Collections.singleton("Distance"), intent.getExtras().keySet());
        Uri uri = RideCardProvider.save(c, Bitmap.createBitmap(48,48,Bitmap.Config.ARGB_8888));
        ContentResolver resolver = c.getContentResolver();
        assertEquals("image/jpeg",resolver.getType(uri));
        try (java.io.InputStream image = resolver.openInputStream(uri)) { assertEquals(255, image.read()); }
        assertThrows(java.io.FileNotFoundException.class, () -> resolver.openFileDescriptor(uri, "rw"));
        assertThrows(IllegalArgumentException.class, () -> resolver.getType(uri.buildUpon().appendPath("secret").build()));
    }
}
