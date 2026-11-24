package com.maxapacity.ride;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The notes on what updates brought: that the list is in a state the app can
 * show, and that each phone is told what is news to it and nothing twice.
 * The sheets themselves are drawn in {@link ScreensTest}.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public class WhatsNewTest {

  Context c;
  SharedPreferences prefs;

  @Before public void setUp() {
    c = RuntimeEnvironment.getApplication();
    prefs = Units.prefs(c);
    prefs.edit().clear().apply();
  }

  /** As a phone whose app has updated itself since it was installed. */
  void updated() {
    PackageInfo info = Shadows.shadowOf(c.getPackageManager()).getInternalMutablePackageInfo(c.getPackageName());
    info.firstInstallTime = 1000L;
    info.lastUpdateTime = 2000L;
  }

  /** A slip here would show a note twice, or never: the phone only remembers the highest number it has shown. */
  @Test public void theNotesAreNumberedNewestFirstAndEachSaysSomething() {
    assertTrue(WhatsNew.NOTES.length > 0);
    assertEquals(WhatsNew.NOTES[0].id, WhatsNew.latest());
    assertEquals("the oldest note is number 1", 1, WhatsNew.NOTES[WhatsNew.NOTES.length - 1].id);
    Set<String> titles = new HashSet<>();
    for (int i = 0; i < WhatsNew.NOTES.length; i++) {
      WhatsNew.Note note = WhatsNew.NOTES[i];
      if (i > 0) assertEquals("each note is numbered one above the note below it", WhatsNew.NOTES[i - 1].id - 1, note.id);
      assertTrue("note " + note.id + " names a month and a year", note.when.matches("[A-Z][a-z]+ 20\\d\\d"));
      assertTrue("note " + note.id + " has something in it", note.items.length > 0);
      for (WhatsNew.Item item : note.items) {
        assertFalse(item.title.trim().isEmpty());
        assertTrue(item.title + " ends as a sentence", item.detail.trim().endsWith("."));
        assertTrue(item.title + " is said once", titles.add(item.title));
        assertFalse(item.title + " has an icon that draws", Icons.path(item.icon).isEmpty());
      }
    }
  }

  @Test public void someoneWhoJustInstalledHasNoNewsAndIsStillOwedTheTour() {
    WhatsNew.arrive(c);
    assertTrue(WhatsNew.unseen(c).isEmpty());
    assertEquals(WhatsNew.latest(), prefs.getInt(WhatsNew.SEEN, -1));
    assertFalse(prefs.getBoolean(Tour.OFFERED, false));
  }

  @Test public void anUpdateFromBeforeThereWereNotesBringsTheNewestOne() {
    updated();
    WhatsNew.arrive(c);
    List<WhatsNew.Note> news = WhatsNew.unseen(c);
    assertEquals(1, news.size());
    assertEquals(WhatsNew.latest(), news.get(0).id);
    assertTrue("they know their way round: no welcome", prefs.getBoolean(Tour.OFFERED, false));

    WhatsNew.markSeen(c);
    assertTrue(WhatsNew.unseen(c).isEmpty());
    // Settled once: arriving again changes nothing.
    WhatsNew.arrive(c);
    assertTrue(WhatsNew.unseen(c).isEmpty());
  }

  @Test public void aNewRiderWhoseAppThenUpdatesIsStillOwedTheTour() {
    WhatsNew.arrive(c);                 // installed, opened once, welcome not yet answered
    updated();
    WhatsNew.arrive(c);
    assertFalse(prefs.getBoolean(Tour.OFFERED, false));
    assertTrue(WhatsNew.unseen(c).isEmpty());
  }

  @Test public void aPhoneFarBehindIsToldTheLatestFewNewestFirst() {
    prefs.edit().putInt(WhatsNew.SEEN, 0).apply();
    List<WhatsNew.Note> news = WhatsNew.unseen(c);
    assertEquals(Math.min(3, WhatsNew.NOTES.length), news.size());
    for (int i = 0; i < news.size(); i++) assertEquals(WhatsNew.NOTES[i].id, news.get(i).id);

    prefs.edit().putInt(WhatsNew.SEEN, WhatsNew.latest() - 1).apply();
    assertEquals(1, WhatsNew.unseen(c).size());
  }
}
