package com.maxapacity.ride;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Copilot: full-screen chat with Gemini, grounded in the current ride.
 * Questions can be typed or spoken; spoken questions get a spoken answer.
 */
final class CopilotView extends LinearLayout {

    interface Host {
        /** Start speech recognition; the result comes back through ask(). */
        void listen();

        void addGeminiKey();
    }

    private static final int SCRIM = 0xF2131316;
    private static final int SURFACE = 0xFF1C1C21;
    private static final int HAIRLINE = 0xFF2E2E36;
    private static final int TEXT = 0xFFF7F5F0;
    private static final int TEXT_DIM = 0xFF9A9AA3;
    private static final int ACCENT = 0xFFFFEB1A;
    private static final int BG = 0xFF131316;
    private static final int DANGER = 0xFFF87171;

    private final Host host;
    private final float density;
    private final LinearLayout transcript;
    private final ScrollView scroll;
    private final EditText input;
    private final LinearLayout emptyState;
    private final List<Gemini.Turn> conversation = new ArrayList<>();
    private TextView thinking;
    private boolean busy;
    private TextToSpeech tts;
    private boolean ttsReady;

    CopilotView(Context context, Host host) {
        super(context);
        this.host = host;
        density = getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setBackgroundColor(SCRIM);
        setClickable(true);
        setVisibility(GONE);

        TextView title = new TextView(context);
        title.setText("COPILOT");
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        title.setTextColor(TEXT_DIM);
        title.setLetterSpacing(0.12f);
        title.setTypeface(Typeface.MONOSPACE);
        title.setGravity(Gravity.CENTER_VERTICAL);
        addView(title, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(32)));

        // Empty state: one-line value prop and the action that unlocks it.
        emptyState = new LinearLayout(context);
        emptyState.setOrientation(VERTICAL);
        emptyState.setGravity(Gravity.CENTER);
        TextView pitch = new TextView(context);
        pitch.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        pitch.setTextColor(TEXT_DIM);
        pitch.setGravity(Gravity.CENTER);
        emptyState.addView(pitch, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView addKey = button("ADD GEMINI KEY", true);
        addKey.setOnClickListener(v -> host.addGeminiKey());
        LayoutParams keyLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48));
        keyLp.topMargin = dp(16);
        emptyState.addView(addKey, keyLp);
        addView(emptyState, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        transcript = new LinearLayout(context);
        transcript.setOrientation(VERTICAL);
        scroll = new ScrollView(context);
        scroll.addView(transcript, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(HORIZONTAL);
        input = new EditText(context);
        input.setHint("Ask about your ride or bike");
        input.setHintTextColor(TEXT_DIM);
        input.setTextColor(TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(dp(16), 0, dp(16), 0);
        input.setBackground(panel(SURFACE, HAIRLINE));
        input.setOnEditorActionListener((v, actionId, event) -> {
            submitTyped();
            return true;
        });
        bar.addView(input, new LayoutParams(0, dp(48), 1f));
        TextView talk = button("TALK", false);
        talk.setOnClickListener(v -> {
            if (Gemini.hasKey(getContext())) host.listen();
            else host.addGeminiKey();
        });
        LayoutParams talkLp = new LayoutParams(dp(72), dp(48));
        talkLp.leftMargin = dp(8);
        bar.addView(talk, talkLp);
        TextView send = button("SEND", true);
        send.setOnClickListener(v -> submitTyped());
        LayoutParams sendLp = new LayoutParams(dp(72), dp(48));
        sendLp.leftMargin = dp(8);
        bar.addView(send, sendLp);
        LayoutParams barLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(8);
        addView(bar, barLp);

        ((TextView) emptyState.getChildAt(0)).setText(
                "Ask anything about your ride, your bike or the trail,\nby typing or by voice.");
        refreshEmptyState();
    }

    boolean isOpen() {
        return getVisibility() == VISIBLE;
    }

    void open() {
        if (isOpen()) return;
        refreshEmptyState();
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(dp(48));
        animate().alpha(1f).translationY(0f).setDuration(180).start();
    }

    void close() {
        if (!isOpen()) return;
        if (tts != null) tts.stop();
        InputMethodManager imm = (InputMethodManager)
                getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(getWindowToken(), 0);
        animate().alpha(0f).translationY(dp(48)).setDuration(150)
                .withEndAction(() -> setVisibility(GONE)).start();
    }

    void shutdown() {
        if (tts != null) tts.shutdown();
    }

    /** Shows the pitch until there is both a key and a first message. */
    void refreshEmptyState() {
        boolean hasKey = Gemini.hasKey(getContext());
        boolean empty = conversation.isEmpty();
        emptyState.setVisibility(empty ? VISIBLE : GONE);
        emptyState.getChildAt(1).setVisibility(hasKey ? GONE : VISIBLE);
        scroll.setVisibility(empty ? GONE : VISIBLE);
    }

    private void submitTyped() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        input.setText("");
        ask(q, false);
    }

    /** @param spoken true when the question came from voice: the answer is read aloud */
    void ask(String question, final boolean spoken) {
        if (busy) return;
        if (!Gemini.hasKey(getContext())) {
            host.addGeminiKey();
            return;
        }
        conversation.add(new Gemini.Turn(true, question));
        addBubble(question, true, false);
        refreshEmptyState();
        busy = true;
        thinking = addBubble("Thinking", false, false);
        thinking.setTextColor(TEXT_DIM);

        Gemini.ask(getContext(), new ArrayList<>(conversation), (text, ok) -> {
            busy = false;
            transcript.removeView(thinking);
            if (ok) {
                conversation.add(new Gemini.Turn(false, text));
                if (spoken) speak(text);
            } else {
                // Drop the unanswered question so a retry does not send it twice.
                conversation.remove(conversation.size() - 1);
            }
            addBubble(text, false, !ok);
        });
    }

    private TextView addBubble(String text, boolean fromRider, boolean error) {
        TextView t = new TextView(getContext());
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setTextColor(error ? DANGER : TEXT);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        t.setBackground(panel(fromRider ? BG : SURFACE, fromRider ? ACCENT : HAIRLINE));
        LayoutParams lp = new LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = fromRider ? Gravity.END : Gravity.START;
        lp.topMargin = dp(8);
        if (fromRider) lp.leftMargin = dp(48);
        else lp.rightMargin = dp(48);
        transcript.addView(t, lp);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        return t;
    }

    private void speak(final String text) {
        if (tts == null) {
            tts = new TextToSpeech(getContext().getApplicationContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "copilot");
            });
        } else if (ttsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "copilot");
        }
    }

    private TextView button(String label, boolean primary) {
        TextView b = new TextView(getContext());
        b.setText(label);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        b.setLetterSpacing(0.08f);
        b.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        b.setGravity(Gravity.CENTER);
        b.setTextColor(primary ? BG : TEXT);
        b.setBackground(primary ? panel(ACCENT, ACCENT) : panel(SURFACE, HAIRLINE));
        return b;
    }

    private GradientDrawable panel(int fill, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(10));
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
