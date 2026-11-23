package com.maxapacity.ride;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * App drawer: search, the MAXAPACITY site tools, then every installed app in a
 * four-column grid. Tap opens an app; press and hold offers pin, app info and
 * uninstall.
 */
final class AppDrawer extends Panel {

    static final class App {
        final String label;
        final String key;
        final Drawable icon;
        final ComponentName component;

        App(String label, Drawable icon, ComponentName component) {
            this.label = label;
            this.key = label.toLowerCase(Locale.getDefault());
            this.icon = icon;
            this.component = component;
        }
    }

    private static final String SITE = "https://maxapacity.com";

    private final Host host;
    private final EditText search;
    private final GridView grid;
    private final FrameLayout emptySlot;
    private final LinearLayout tools;
    private final List<App> all = new ArrayList<>();
    private final List<App> shown = new ArrayList<>();
    private boolean loaded;

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override
        public int getCount() {
            return shown.size();
        }

        @Override
        public Object getItem(int position) {
            return shown.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convertView;
            if (cell == null) {
                Context c = getContext();
                cell = Ui.column(c);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setPadding(dp(4), dp(12), dp(4), dp(12));
                cell.addView(new ImageView(c), new LinearLayout.LayoutParams(dp(48), dp(48)));
                TextView name = Ui.small(c, "", Ui.TEXT_2);
                name.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_LABEL);
                name.setGravity(Gravity.CENTER_HORIZONTAL);
                name.setSingleLine(true);
                name.setEllipsize(TextUtils.TruncateAt.END);
                cell.addView(name, Ui.top(c, Ui.matchWrap(), 8));
            }
            App app = shown.get(position);
            ((ImageView) cell.getChildAt(0)).setImageDrawable(app.icon);
            ((TextView) cell.getChildAt(1)).setText(app.label);
            return cell;
        }
    };

    AppDrawer(Context context, Host host) {
        super(context, "Apps");
        this.host = host;

        // Search field with a leading icon.
        LinearLayout box = Ui.row(context);
        box.setBackground(Ui.panel(context, Ui.SURFACE, Ui.HAIRLINE, 10));
        box.setPadding(dp(16), 0, dp(4), 0);
        box.addView(Ui.icon(context, Icons.SEARCH, 18, Ui.TEXT_3));
        search = new EditText(context);
        search.setHint("Search apps");
        search.setHintTextColor(Ui.TEXT_3);
        search.setTextColor(Ui.TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, Ui.T_BODY);
        search.setTypeface(Ui.sans);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setBackground(null);
        search.setPadding(dp(12), 0, dp(12), 0);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                filter();
            }
        });
        // Enter opens the top match, so "ma" + Enter is the fastest way to maps.
        search.setOnEditorActionListener((v, actionId, event) -> {
            if (!shown.isEmpty()) open(shown.get(0));
            return true;
        });
        box.addView(search, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        final View clear = Ui.iconButton(context, Icons.X, "Clear search");
        clear.setOnClickListener(v -> search.setText(""));
        box.addView(clear, new LinearLayout.LayoutParams(dp(40), dp(40)));
        clear.setPadding(dp(11), dp(11), dp(11), dp(11));
        clear.setVisibility(INVISIBLE);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                clear.setVisibility(s.length() == 0 ? INVISIBLE : VISIBLE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        addView(box, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        // MAXAPACITY site tools.
        tools = Ui.row(context);
        addTool("Garage", Icons.WAREHOUSE, "/garage");
        addTool("Picker", Icons.SLIDERS_HORIZONTAL, "/picker");
        addTool("Guides", Icons.BOOK_OPEN, "/guides");
        addTool("Gearing", Icons.COG, "/tools/sprocket-calculator");
        addView(tools, Ui.top(context, Ui.matchWrap(), 12));

        emptySlot = new FrameLayout(context);
        emptySlot.setVisibility(GONE);
        addView(emptySlot, Ui.matchWrap());

        grid = new GridView(context);
        grid.setNumColumns(4);
        grid.setVerticalScrollBarEnabled(false);
        grid.setSelector(Ui.ghost(context, 16));
        grid.setDrawSelectorOnTop(true);
        grid.setClipToPadding(false);
        grid.setPadding(0, 0, 0, dp(16));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> open(shown.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            showMenu(shown.get(position));
            return true;
        });
        addView(grid, Ui.top(context, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f), 12));
    }

    private void addTool(String name, String icon, final String path) {
        Context c = getContext();
        LinearLayout tile = Ui.row(c);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.HAIRLINE, 10));
        tile.addView(Ui.icon(c, icon, 16, Ui.TEXT_2));
        TextView l = Ui.small(c, name, Ui.TEXT);
        l.setSingleLine(true);
        l.setIncludeFontPadding(false);
        tile.addView(l, Ui.left(c, Ui.wrap(), 6));
        tile.setOnClickListener(v -> {
            host.launch(new Intent(Intent.ACTION_VIEW, Uri.parse(SITE + path)));
            close();
        });
        Ui.press(tile);
        LayoutParams lp = new LayoutParams(0, dp(40), 1f);
        if (tools.getChildCount() > 0) lp.leftMargin = dp(8);
        tools.addView(tile, lp);
    }

    /** True when the grid is scrolled to the top, so a downward swipe may close it. */
    boolean atTop() {
        return !grid.canScrollVertically(-1);
    }

    @Override
    protected void onOpen() {
        if (!loaded) load();
        search.setText("");
        filter();
        grid.setSelection(0);
    }

    @Override
    protected void onClose() {
        search.clearFocus();
    }

    /** Installed apps, alphabetical; loaded on first use. */
    List<App> apps() {
        if (!loaded) load();
        return all;
    }

    /** Forget the cached list after an app is installed, removed or updated. */
    void invalidateApps() {
        loaded = false;
        if (isOpen()) {
            load();
            filter();
        }
    }

    private void load() {
        PackageManager pm = getContext().getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
        Collections.sort(found, new ResolveInfo.DisplayNameComparator(pm));
        all.clear();
        for (ResolveInfo r : found) {
            if (getContext().getPackageName().equals(r.activityInfo.packageName)) continue;
            all.add(new App(r.loadLabel(pm).toString(), r.loadIcon(pm),
                    new ComponentName(r.activityInfo.packageName, r.activityInfo.name)));
        }
        loaded = true;
    }

    private void filter() {
        String typed = search.getText().toString().trim();
        String q = typed.toLowerCase(Locale.getDefault());
        shown.clear();
        for (App app : all) {
            if (q.isEmpty() || app.key.contains(q)) shown.add(app);
        }
        adapter.notifyDataSetChanged();
        status.set(all.isEmpty() ? "" : shown.size() + (shown.size() == 1 ? " APP" : " APPS"),
                Ui.TEXT_3);
        // The site tools are a browsing aid; they step aside while searching.
        tools.setVisibility(q.isEmpty() ? VISIBLE : GONE);
        emptySlot.removeAllViews();
        if (shown.isEmpty()) {
            emptySlot.addView(all.isEmpty()
                    ? Ui.emptyState(getContext(), Icons.LAYOUT_GRID, "No apps yet",
                            "Apps you install show up here.", null, 0, null)
                    : Ui.emptyState(getContext(), Icons.SEARCH, "No match for \"" + typed + "\"",
                            "Check the spelling or clear the search.", "Clear search",
                            Ui.SECONDARY, v -> search.setText("")));
        }
        emptySlot.setVisibility(shown.isEmpty() ? VISIBLE : GONE);
        grid.setVisibility(shown.isEmpty() ? GONE : VISIBLE);
    }

    static Intent launchIntent(ComponentName cn) {
        return new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(cn)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
    }

    private void open(App app) {
        host.launch(launchIntent(app.component));
        close();
    }

    private void showMenu(final App app) {
        final Uri pkg = Uri.fromParts("package", app.component.getPackageName(), null);
        new Sheet(host.frame(), app.label, null)
                .item(Icons.PIN, "Pin to ride apps", "One-tap access from the dashboard", false,
                        () -> host.pin(app.component))
                .item(Icons.INFO, "App info", "Permissions, storage and notifications", false,
                        () -> host.launch(new Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg)))
                // The system asks for confirmation and refuses built-in apps.
                .item(Icons.TRASH_2, "Uninstall", null, true,
                        () -> host.launch(new Intent(Intent.ACTION_DELETE, pkg)))
                .show();
    }
}
