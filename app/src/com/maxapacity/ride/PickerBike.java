package com.maxapacity.ride;

import android.content.Context;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Only route to models represented in the website's picker catalog. */
final class PickerBike {
    private static final String[][] MODELS = {
            {"sur-ron-light-bee-x", "sur ron light bee x", "surron light bee x", "light bee x"},
            {"sur-ron-ultra-bee", "sur ron ultra bee", "surron ultra bee", "ultra bee"},
            {"talaria-mx4", "talaria mx4", "talaria sting r mx4", "sting r mx4"},
            {"talaria-mx3", "talaria mx3", "talaria sting mx3"},
            {"talaria-xxx", "talaria xxx"},
            {"super73-rx", "super73 rx", "super 73 rx"},
            {"super73-s2", "super73 s2", "super 73 s2"},
            {"super73-z1", "super73 z1", "super 73 z1"},
            {"tuttio-soleil01", "tuttio soleil01", "tuttio soleil 01"},
            {"drax-x-pro-72v", "drax x pro", "nexvey storm", "skeri edb01", "skeri voltalon"},
            {"eskute-rt-x", "eskute rt x"},
            {"kovo-rt-x", "kovo rt x"}
    };

    static String path(Context c) {
        Bike bike = Bike.get(c);
        if (bike.demo()) return BikeToolActivity.PICKER;
        return path(bike.controllerName(), bike.batteryName(), Units.prefs(c).getString(TuneBrief.ABOUT, ""));
    }

    static String path(String controller, String battery, String about) {
        Set<String> matches = new HashSet<>();
        collect(controller, matches);
        collect(battery, matches);
        // The note can mention donor parts and other bikes. Only use an explicit leading model
        // as its identity, and reject a note naming multiple catalog models.
        String note = normalize(about).replaceFirst("^(?:my bike is |i ride |bike )(?:an? )?", "");
        Set<String> noteModels = new HashSet<>();
        collect(note, noteModels);
        for (String[] model : MODELS) {
            for (int i = 1; i < model.length; i++) {
                if ((note + " ").startsWith(model[i] + " ")) {
                    matches.addAll(noteModels);
                }
            }
        }
        return matches.size() == 1 ? "/builder/" + matches.iterator().next() : BikeToolActivity.PICKER;
    }

    private static void collect(String text, Set<String> matches) {
        String words = " " + normalize(text) + " ";
        for (String[] model : MODELS) {
            for (int i = 1; i < model.length; i++) {
                if (words.contains(" " + model[i] + " ")) matches.add(model[0]);
            }
        }
    }

    private static String normalize(String text) {
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }
}
