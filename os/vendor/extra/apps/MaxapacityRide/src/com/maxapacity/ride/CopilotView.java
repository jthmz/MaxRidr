package com.maxapacity.ride;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Copilot: chat with Gemini, grounded in the current ride. Questions can be
 * typed or spoken; spoken questions get a spoken answer.
 */
final class CopilotView extends Panel {

    private static final String[] STARTERS = {
            "How is this ride going so far?",
            "What tire pressure should I run on loose dirt?",
            "How do I bed in new brake pads?",
    };

    private final Host host;
    private final LinearLayout transcript;
    private final ScrollView scroll;
    private final EditText input;
    private final FrameLayout emptySlot;
    private final List<Gemini.Turn> conversation = new ArrayList<>();
    private View thinking;
    private boolean busy;
    private TextToSpeech tts;
    private boolean ttsReady;

    CopilotView(Context context, Host host) {
        super(context, "Copilot");
        this.host = host;

        View reset = Ui.iconButton(context, Icons.ROTATE_CCW, "New conversation");
        reset.setOnClickListener(v -> newConversation());
        setHeaderAction(reset);

        emptySlot = new FrameLayout(context);
        addView(emptySlot, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        transcript = Ui.column(context);
        transcript.setPadding(0, dp(8), 0, dp(8));
        scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(transcript, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroll, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // Composer: the field, then voice, then send.
        LinearLayout bar = Ui.row(context);
        input = Ui.input(context, "Ask about your ride or bike", false);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setOnEditorActionListener((v, actionId, event) -> {
            submitTyped();
            return true;
        });
        bar.addView(input, new LayoutParams(0, dp(48), 1f));
        ImageView talk = roundButton(Icons.MIC, "Ask by voice", false);
        talk.setOnClickListener(v -> {
            if (Gemini.hasKey(getContext())) host.listen();
            else host.editGeminiKey();
        });
        bar.addView(talk, Ui.left(context, new LayoutParams(dp(48), dp(48)), 8));
        ImageView send = roundButton(Icons.ARROW_UP, "Send", true);
        send.setOnClickListener(v -> submitTyped());
        bar.addView(send, Ui.left(context, new LayoutParams(dp(48), dp(48)), 8));
        LayoutParams barLp = Ui.matchWrap();
        barLp.topMargin = dp(8);
        barLp.bottomMargin = dp(12);
        addView(bar, barLp);

        refreshEmptyState();
    }

    private ImageView roundButton(String icon, String description, boolean primary) {
        Context c = getContext();
        ImageView b = new ImageView(c);
        b.setImageDrawable(Icons.of(c, icon, primary ? Ui.BG : Ui.TEXT));
        b.setPadding(dp(14), dp(14), dp(14), dp(14));
        b.setBackground(primary ? Ui.pressable(c, Ui.ACCENT, 0, 24)
                : Ui.pressable(c, Ui.SURFACE_HI, Ui.HAIRLINE_HI, 24));
        b.setContentDescription(description);
        Ui.press(b);
        return b;
    }

    @Override
    protected void onOpen() {
        refreshEmptyState();
    }

    @Override
    protected void onClose() {
        if (tts != null) tts.stop();
    }

    void shutdown() {
        if (tts != null) tts.shutdown();
    }

    private void newConversation() {
        if (busy || conversation.isEmpty()) return;
        final List<Gemini.Turn> before = new ArrayList<>(conversation);
        final List<View> bubbles = new ArrayList<>();
        for (int i = 0; i < transcript.getChildCount(); i++) bubbles.add(transcript.getChildAt(i));
        conversation.clear();
        transcript.removeAllViews();
        if (tts != null) tts.stop();
        refreshEmptyState();
        host.say("Conversation cleared", "UNDO", () -> {
            if (!conversation.isEmpty()) return;
            conversation.addAll(before);
            for (View b : bubbles) transcript.addView(b);
            refreshEmptyState();
        });
    }

    /** Shows the pitch until there is both a key and a first message. */
    void refreshEmptyState() {
        Context c = getContext();
        boolean hasKey = Gemini.hasKey(c);
        boolean empty = conversation.isEmpty();
        status.set(hasKey ? "READY" : "NO KEY", hasKey ? Ui.OK : Ui.WARN);
        // "New conversation" only means something once there is one.
        headerAction.setVisibility(empty ? GONE : VISIBLE);
        emptySlot.removeAllViews();
        emptySlot.setVisibility(empty ? VISIBLE : GONE);
        scroll.setVisibility(empty ? GONE : VISIBLE);
        if (!empty) return;
        if (!hasKey) {
            emptySlot.addView(Ui.emptyState(c, Icons.SPARKLES, "A riding expert on call",
                    "Ask about your ride, your bike or the trail, by typing or by voice."
                            + " Copilot runs on Google Gemini and needs a free API key.",
                    "Add Gemini key", Ui.PRIMARY, v -> host.editGeminiKey()));
            return;
        }
        LinearLayout box = Ui.emptyState(c, Icons.SPARKLES, "Ask Copilot",
                "It knows your current speed, distance and ride time.", null, 0, null);
        for (final String s : STARTERS) {
            TextView chip = Ui.small(c, s, Ui.TEXT);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(16), dp(12), dp(16), dp(12));
            chip.setBackground(Ui.pressable(c, Ui.SURFACE, Ui.HAIRLINE, 10));
            chip.setOnClickListener(v -> ask(s, false));
            Ui.press(chip);
            box.addView(chip, Ui.top(c, Ui.matchWrap(), box.getChildCount() == 3 ? 24 : 8));
        }
        emptySlot.addView(box);
    }

    private void submitTyped() {
        String q = input.getText().toString().trim();
        if (q.isEmpty()) return;
        if (busy) return;
        input.setText("");
        ask(q, false);
    }

    /** @param spoken true when the question came from voice: the answer is read aloud */
    void ask(String question, final boolean spoken) {
        if (busy) return;
        if (!Gemini.hasKey(getContext())) {
            host.editGeminiKey();
            return;
        }
        if (!isOpen()) open();
        conversation.add(new Gemini.Turn(true, question));
        addBubble(question, true, false);
        refreshEmptyState();
        busy = true;
        status.set("THINKING", Ui.WARN);
        thinking = Ui.skeleton(getContext(), 48);
        LayoutParams tlp = new LayoutParams(dp(96), dp(44));
        tlp.topMargin = dp(8);
        transcript.addView(thinking, tlp);
        scrollToEnd();

        Gemini.ask(getContext(), new ArrayList<>(conversation), (text, ok) -> {
            busy = false;
            transcript.removeView(thinking);
            status.set("READY", Ui.OK);
            if (ok) {
                conversation.add(new Gemini.Turn(false, text));
                if (spoken) speak(text);
            } else if (!conversation.isEmpty()) {
                // Drop the unanswered question so a retry does not send it twice.
                conversation.remove(conversation.size() - 1);
            }
            addBubble(text, false, !ok);
        });
    }

    private void addBubble(String text, boolean fromRider, boolean error) {
        Context c = getContext();
        TextView t = Ui.body(c, text, error ? Ui.DANGER : Ui.TEXT);
        t.setTextIsSelectable(true);
        t.setPadding(dp(16), dp(12), dp(16), dp(12));
        // The rider's side carries the accent tint; answers are plain surfaces.
        t.setBackground(fromRider ? Ui.panel(c, Ui.ACCENT_DIM, 0, 16)
                : Ui.panel(c, Ui.SURFACE, error ? 0x66F87171 : Ui.HAIRLINE, 16));
        LayoutParams lp = Ui.wrap();
        lp.gravity = fromRider ? Gravity.END : Gravity.START;
        lp.topMargin = dp(8);
        if (fromRider) lp.leftMargin = dp(48);
        else lp.rightMargin = dp(48);
        transcript.addView(t, lp);
        scrollToEnd();
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
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
}
