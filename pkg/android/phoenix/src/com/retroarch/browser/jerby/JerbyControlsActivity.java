package com.retroarch.browser.jerby;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.io.IOException;

/** The only layout editor: drag the six SNES control groups, then play. */
public final class JerbyControlsActivity extends Activity {
    private final TextView[] handles = new TextView[JerbyControls.GROUPS.length];
    private FrameLayout canvas;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(7, 25, 43));
        getWindow().setNavigationBarColor(Color.rgb(7, 25, 43));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        canvas = new FrameLayout(this);
        canvas.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] { Color.rgb(7, 25, 43), Color.rgb(18, 58, 78) }));
        setContentView(canvas);

        TextView title = new TextView(this);
        title.setText("Arrange controls");
        title.setTextSize(24);
        title.setTextColor(Color.WHITE);
        title.setPadding(dp(20), dp(12), 0, 0);
        canvas.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Drag the controls where your hands feel comfortable");
        hint.setTextSize(13);
        hint.setTextColor(Color.rgb(178, 215, 222));
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.LEFT);
        hintParams.leftMargin = dp(21);
        hintParams.topMargin = dp(45);
        canvas.addView(hint, hintParams);

        Button done = new Button(this);
        done.setText("Done");
        done.setAllCaps(false);
        done.setOnClickListener(v -> finishEditor());
        FrameLayout.LayoutParams doneParams = new FrameLayout.LayoutParams(dp(88), dp(50), Gravity.TOP | Gravity.RIGHT);
        doneParams.topMargin = dp(8);
        doneParams.rightMargin = dp(14);
        canvas.addView(done, doneParams);

        Button reset = new Button(this);
        reset.setText("Reset");
        reset.setAllCaps(false);
        reset.setOnClickListener(v -> { JerbyControls.reset(this); positionHandles(); });
        FrameLayout.LayoutParams resetParams = new FrameLayout.LayoutParams(dp(88), dp(50), Gravity.TOP | Gravity.RIGHT);
        resetParams.topMargin = dp(8);
        resetParams.rightMargin = dp(108);
        canvas.addView(reset, resetParams);

        for (int group = 0; group < handles.length; group++) {
            final int index = group;
            TextView handle = new TextView(this);
            handle.setText(group == 0 ? "↑\n←  •  →\n↓" : group == 1 ? "X\nY     A\nB" : JerbyControls.LABELS[group]);
            handle.setTextSize(group < 2 ? 19 : 15);
            handle.setTextColor(Color.WHITE);
            handle.setGravity(Gravity.CENTER);
            GradientDrawable shape = new GradientDrawable();
            shape.setColor(group == 1 ? Color.rgb(145, 65, 108) : Color.rgb(22, 139, 150));
            shape.setCornerRadius(dp(group < 2 ? 28 : 22));
            shape.setStroke(dp(2), Color.rgb(194, 244, 240));
            handle.setBackground(shape);
            int width = dp(group < 2 ? 116 : group < 4 ? 88 : 64);
            int height = dp(group < 2 ? 116 : 50);
            canvas.addView(handle, new FrameLayout.LayoutParams(width, height));
            handles[group] = handle;
            handle.setOnTouchListener((view, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.setTag(new float[] {event.getRawX(), event.getRawY(), view.getX(), view.getY()});
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        float[] offset = (float[])view.getTag();
                        view.setX(Math.max(0, Math.min(canvas.getWidth() - view.getWidth(), offset[2] + event.getRawX() - offset[0])));
                        view.setY(Math.max(0, Math.min(canvas.getHeight() - view.getHeight(), offset[3] + event.getRawY() - offset[1])));
                        return true;
                    case MotionEvent.ACTION_UP:
                        JerbyControls.setPosition(this, index,
                                (view.getX() + view.getWidth() / 2f) / canvas.getWidth(),
                                (view.getY() + view.getHeight() / 2f) / canvas.getHeight());
                        positionHandles();
                        return true;
                    default: return true;
                }
            });
        }
        canvas.post(this::positionHandles);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private void positionHandles() {
        if (canvas.getWidth() == 0 || canvas.getHeight() == 0) return;
        for (int group = 0; group < handles.length; group++) {
            TextView view = handles[group];
            view.setX(JerbyControls.x(this, group) * canvas.getWidth() - view.getWidth() / 2f);
            view.setY(JerbyControls.y(this, group) * canvas.getHeight() - view.getHeight() / 2f);
        }
    }

    private void finishEditor() {
        try { JerbyControls.prepare(this); }
        catch (IOException e) { Toast.makeText(this, "Could not save controls", Toast.LENGTH_LONG).show(); }
        finish();
    }

    @Override public void onBackPressed() { finishEditor(); }
}
