package com.maxapacity.ride;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
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
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Full-screen app drawer: search box over a four-column icon grid.
 * Tap opens an app; long-press offers pin, app info and uninstall.
 */
final class AppDrawer extends LinearLayout {

    interface Host {
        void launch(Intent intent);

        void pin(ComponentName app);
    }

    private static final class App {
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

    private static final int SCRIM = 0xF2131316;
    private static final int SURFACE = 0xFF1C1C21;
    private static final int HAIRLINE = 0xFF2E2E36;
    private static final int TEXT = 0xFFF7F5F0;
    private static final int TEXT_DIM = 0xFF9A9AA3;

    private final Host host;
    private final float density;
    private final EditText search;
    private final GridView grid;
    private final TextView empty;
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
            TextView cell = (TextView) convertView;
            if (cell == null) {
                cell = new TextView(getContext());
                cell.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
                cell.setTextColor(TEXT);
                cell.setGravity(Gravity.CENTER_HORIZONTAL);
                cell.setSingleLine(true);
                cell.setEllipsize(TextUtils.TruncateAt.END);
                cell.setCompoundDrawablePadding(dp(8));
                cell.setPadding(dp(4), dp(12), dp(4), dp(12));
            }
            App app = shown.get(position);
            app.icon.setBounds(0, 0, dp(48), dp(48));
            cell.setCompoundDrawables(null, app.icon, null, null);
            cell.setText(app.label);
            return cell;
        }
    };

    AppDrawer(Context context, Host host) {
        super(context);
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(SCRIM);
        setClickable(true); // swallow touches so the dashboard underneath stays put
        setVisibility(GONE);

        search = new EditText(context);
        search.setHint("Search apps");
        search.setHintTextColor(TEXT_DIM);
        search.setTextColor(TEXT);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        search.setTypeface(Typeface.MONOSPACE);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setImeOptions(EditorInfo.IME_ACTION_GO);
        search.setPadding(dp(16), 0, dp(16), 0);
        GradientDrawable box = new GradientDrawable();
        box.setColor(SURFACE);
        box.setCornerRadius(dp(10));
        box.setStroke(Math.max(1, dp(1)), HAIRLINE);
        search.setBackground(box);
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
        addView(search, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));

        empty = new TextView(context);
        empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        empty.setTextColor(TEXT_DIM);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(0, dp(48), 0, 0);
        empty.setVisibility(GONE);
        addView(empty, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        grid = new GridView(context);
        grid.setNumColumns(4);
        grid.setVerticalSpacing(dp(4));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0x22FFFFFF));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> open(shown.get(position)));
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            showMenu(view, shown.get(position));
            return true;
        });
        LayoutParams gridLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = dp(16);
        addView(grid, gridLp);
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    /** True when the grid is scrolled to the top, so a downward swipe may close it. */
    boolean atTop() {
        return !grid.canScrollVertically(-1);
    }

    void open() {
        if (isOpen()) return;
        if (!loaded) load();
        search.setText("");
        filter();
        grid.setSelection(0);
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(dp(48));
        animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    void close() {
        if (!isOpen()) return;
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
        search.clearFocus();
        animate().alpha(0f).translationY(dp(48)).setDuration(150)
                .withEndAction(() -> setVisibility(GONE)).start();
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
        String q = search.getText().toString().trim().toLowerCase(Locale.getDefault());
        shown.clear();
        for (App app : all) {
            if (q.isEmpty() || app.key.contains(q)) shown.add(app);
        }
        adapter.notifyDataSetChanged();
        if (shown.isEmpty()) {
            empty.setText(all.isEmpty() ? "No apps installed yet"
                    : "No app matches \"" + search.getText().toString().trim() + "\"");
        }
        empty.setVisibility(shown.isEmpty() ? VISIBLE : GONE);
    }

    private void open(App app) {
        host.launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        close();
    }

    private void showMenu(View anchor, final App app) {
        PopupMenu menu = new PopupMenu(getContext(), anchor);
        menu.getMenu().add(0, 1, 0, "Pin to ride apps");
        menu.getMenu().add(0, 2, 1, "App info");
        menu.getMenu().add(0, 3, 2, "Uninstall");
        menu.setOnMenuItemClickListener(item -> {
            Uri pkg = Uri.fromParts("package", app.component.getPackageName(), null);
            switch (item.getItemId()) {
                case 1:
                    host.pin(app.component);
                    break;
                case 2:
                    host.launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg));
                    break;
                default:
                    // The system asks for confirmation and refuses built-in apps.
                    host.launch(new Intent(Intent.ACTION_DELETE, pkg));
                    break;
            }
            return true;
        });
        menu.show();
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
