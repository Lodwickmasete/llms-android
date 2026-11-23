package com.lodwickmasete.llms;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;

/** Small view helpers shared by every screen (colors, cards, rows, toggles, avatars). */
final class Ui {

    static final int BG = 0xFF000000;
    static final int CARD = 0xFF0D0D0D;
    static final int BORDER = 0xFF373737;
    static final int ACCENT = 0xFF87CEFA;
    static final int TEXT = 0xFFFFFFFF;
    static final int DIM = 0xFF9E9E9E;
    static final int FAINT = 0xFF757575;

    private Ui() {
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable box(Context c, int radiusDp, int fill, int strokeColor, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(c, radiusDp));
        d.setColor(fill);
        if (strokeDp > 0) d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    static GradientDrawable circle(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static GradientDrawable blueCircle() {
        GradientDrawable d = new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF00BFFF, 0xFF1E90FF, 0xFF5DADEC, 0xFF87CEFA});
        d.setShape(GradientDrawable.OVAL);
        return d;
    }

    static void applyGradient(TextView tv) {
        float width = tv.getPaint().measureText(tv.getText().toString());
        tv.getPaint().setShader(new LinearGradient(0, 0, width, tv.getTextSize(),
                new int[]{0xFF00BFFF, 0xFF1E90FF, 0xFF5DADEC, 0xFF87CEFA},
                null, Shader.TileMode.CLAMP));
        tv.invalidate();
    }

    static void clearGradient(TextView tv) {
        tv.getPaint().setShader(null);
        tv.invalidate();
    }

    // ── Names, time ───────────────────────────────────────────────────────────
    /** "qwen2.5-1.5b-q4.gguf" -> "qwen2.5-1.5b-q4" */
    static String botName(String model) {
        if (model == null || model.length() == 0) return "Assistant";
        String n = model;
        if (n.toLowerCase(Locale.ROOT).endsWith(".gguf")) n = n.substring(0, n.length() - 5);
        return n;
    }

    static String clock(long ts) {
        return new SimpleDateFormat("HH:mm", Locale.ENGLISH).format(new Date(ts));
    }

    /** Time today, "Yesterday", or "12 Mar" - for the chat list. */
    static String day(long ts) {
        if (ts <= 0) return "";
        Calendar now = Calendar.getInstance();
        Calendar then = Calendar.getInstance();
        then.setTimeInMillis(ts);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return clock(ts);
        }
        now.add(Calendar.DAY_OF_YEAR, -1);
        if (now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
                && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)) {
            return "Yesterday";
        }
        return new SimpleDateFormat("d MMM", Locale.ENGLISH).format(new Date(ts));
    }

    // ── Avatars (generated letter circles; bots are blue, people get a stable hue) ──
    private static final HashMap<String, Bitmap> AVATARS = new HashMap<String, Bitmap>();

    static Bitmap avatar(String seed, String label, boolean bot, int px) {
        String key = seed + "|" + label + "|" + bot + "|" + px;
        Bitmap cached = AVATARS.get(key);
        if (cached != null) return cached;

        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (bot) {
            p.setShader(new LinearGradient(0, px, px, 0, 0xFF00BFFF, 0xFF1E90FF, Shader.TileMode.CLAMP));
        } else {
            float[] hsv = new float[]{(seed.hashCode() & 0x7fffffff) % 360, 0.45f, 0.55f};
            p.setColor(Color.HSVToColor(hsv));
        }
        c.drawCircle(px / 2f, px / 2f, px / 2f, p);

        String initial = "?";
        for (int i = 0; i < label.length(); i++) {
            if (Character.isLetterOrDigit(label.charAt(i))) {
                initial = String.valueOf(Character.toUpperCase(label.charAt(i)));
                break;
            }
        }
        p.setShader(null);
        p.setColor(Color.WHITE);
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextSize(px * 0.44f);
        Paint.FontMetrics fm = p.getFontMetrics();
        c.drawText(initial, px / 2f, px / 2f - (fm.ascent + fm.descent) / 2f, p);

        AVATARS.put(key, b);
        return b;
    }

    // ── Grouped settings UI: title + card of rows ─────────────────────────────
    static TextView sectionTitle(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(ACCENT);
        t.setTextSize(14);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, 22);
        lp.bottomMargin = dp(c, 8);
        lp.leftMargin = dp(c, 4);
        t.setLayoutParams(lp);
        return t;
    }

    static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackground(box(c, 14, CARD, BORDER, 1));
        l.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    /** Adds a row to a card, with a thin divider above it when it is not the first. */
    static void addRow(Context c, LinearLayout card, View row) {
        if (card.getChildCount() > 0) {
            View d = new View(c);
            d.setBackgroundColor(0xFF1F1F1F);
            card.addView(d, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        card.addView(row);
    }

    private static LinearLayout textColumn(Context c, String title, String sub, boolean accentSub) {
        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(c);
        t.setText(title);
        t.setTextColor(TEXT);
        t.setTextSize(15);
        col.addView(t);
        if (sub != null && sub.length() > 0) {
            TextView s = new TextView(c);
            s.setText(sub);
            s.setTextColor(accentSub ? ACCENT : DIM);
            s.setTextSize(12);
            s.setPadding(0, dp(c, 2), 0, 0);
            col.addView(s);
        }
        return col;
    }

    static View row(Context c, String title, String sub, boolean accentSub, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        row.addView(textColumn(c, title, sub, accentSub));
        if (click != null) row.setOnClickListener(click);
        return row;
    }

    static View toggleRow(Context c, String title, String sub, boolean checked,
                          CompoundButton.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));

        LinearLayout col = textColumn(c, title, sub, false);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final Switch sw = new Switch(c);
        sw.setChecked(checked);
        sw.setOnCheckedChangeListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(c, 12);
        row.addView(sw, lp);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                sw.toggle();
            }
        });
        return row;
    }

    static TextView note(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(FAINT);
        t.setTextSize(12);
        t.setPadding(dp(c, 6), dp(c, 8), dp(c, 6), 0);
        return t;
    }

    static TextView hintRow(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(DIM);
        t.setTextSize(12);
        t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        t.setPadding(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        return t;
    }
}
