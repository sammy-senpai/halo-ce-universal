package com.halo.decomp;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class TouchSettingsPanel extends ScrollView implements TouchControls.EditListener {
    private static final int ACCENT = 0xFF4FC3F7;
    private static final int DELETE_COLOR = 0xFFFF5252;

    private final TouchControls controls;
    private final float density;

    private final TextView hint;
    private final SeekBar sizeBar, opacityBar, lookBar;
    private final TextView sizeValue, opacityValue, lookValue;
    private final CheckBox enableControlsCheck;
    private final TextView showPill, hapticsPill;
    private final TextView modePill, renamePill, iconPill, deletePill;
    /** one tab for each set of controls (Game, Menu, Cutscene) */
    private final TextView[] setTabs = new TextView[TouchControls.SET_NAMES.length];
    private final TextView skipOnlyPill;

    // Multi-state Key Binding Pills
    private final LinearLayout stateBindingsContainer;
    private final TextView pressKeyPill, longPressKeyPill, toggleOnKeyPill, toggleOffKeyPill;
    private boolean updating;

    public TouchSettingsPanel(Context context, TouchControls controls) {
        super(context);
        this.controls = controls;
        density = context.getResources().getDisplayMetrics().density;

        // Ensure vertical scrolling behavior fits comfortably on landscape screens
        setFillViewport(true);
        setVerticalScrollBarEnabled(true);
        setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);

        // Put the card background on the ScrollView itself so scrolling remains inside the card
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xEB12181E);
        card.setCornerRadius(dp(16));
        card.setStroke(dp(1), 0x40FFFFFF);
        setBackground(card);
        setClickable(true);
        setVisibility(GONE);

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(14), dp(10), dp(14), dp(12));

        // Header: Drag handle title, + Add, Reset, Done
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = text("Edit Controls", 14, true, 0xFFFFFFFF);
        title.setPadding(0, dp(4), dp(6), dp(4));
        header.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView addBtn = pill("+ Add", false);
        TextView reset = pill("Reset", false);
        TextView done = pill("Done", true);

        header.addView(addBtn);
        header.addView(spacing(dp(6)));
        header.addView(reset);
        header.addView(spacing(dp(6)));
        header.addView(done);
        layout.addView(header);
        makeHandle(title);

        // The sets: each has its own controls, shown when the game is in
        // that state (playing, a menu, a cutscene)
        LinearLayout tabs = new LinearLayout(context);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < setTabs.length; i++) {
            final int index = i;
            TextView tab = pill(TouchControls.SET_NAMES[i], false);
            LinearLayout.LayoutParams tabParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) tabParams.leftMargin = dp(6);
            tab.setOnClickListener(v -> {
                controls.setEditingSet(index);
                refresh();
            });
            tabs.addView(tab, tabParams);
            setTabs[i] = tab;
        }
        layout.addView(tabs, margin(0, dp(6), 0, 0));

        hint = text("", 11, false, 0xB3FFFFFF);
        hint.setPadding(0, dp(4), 0, dp(6));
        layout.addView(hint);

        // Master toggle: Enable/Disable On-Screen Controls
        enableControlsCheck = new CheckBox(context);
        enableControlsCheck.setText("Show On-Screen Controls");
        enableControlsCheck.setTextColor(0xFFFFFFFF);
        enableControlsCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        enableControlsCheck.setButtonTintList(ColorStateList.valueOf(ACCENT));
        layout.addView(enableControlsCheck, margin(0, dp(2), 0, dp(4)));

        sizeValue = text("", 11, false, 0xB3FFFFFF);
        opacityValue = text("", 11, false, 0xB3FFFFFF);
        lookValue = text("", 11, false, 0xB3FFFFFF);
        sizeBar = slider(Math.round((TouchControls.SIZE_MAX - TouchControls.SIZE_MIN) * 100f));
        opacityBar = slider(Math.round((TouchControls.OPACITY_MAX - TouchControls.OPACITY_MIN) * 100f));
        lookBar = slider(Math.round((TouchControls.LOOK_MAX - TouchControls.LOOK_MIN) * 100f));

        layout.addView(row("Size", sizeBar, sizeValue));
        layout.addView(row("Opacity", opacityBar, opacityValue));
        layout.addView(row("Look speed", lookBar, lookValue));

        // Global toggles
        LinearLayout toggles = new LinearLayout(context);
        toggles.setOrientation(LinearLayout.HORIZONTAL);
        showPill = pill("Show", true);
        hapticsPill = pill("Haptics", true);
        modePill = pill("Mode: Standard", false);
        toggles.addView(showPill);
        toggles.addView(spacing(dp(6)));
        toggles.addView(hapticsPill);
        toggles.addView(spacing(dp(6)));
        toggles.addView(modePill);
        layout.addView(toggles, margin(0, dp(8), 0, 0));

        // State bindings container
        stateBindingsContainer = new LinearLayout(context);
        stateBindingsContainer.setOrientation(LinearLayout.VERTICAL);

        LinearLayout rowKeys1 = new LinearLayout(context);
        rowKeys1.setOrientation(LinearLayout.HORIZONTAL);
        pressKeyPill = pill("Press: ...", false);
        longPressKeyPill = pill("Hold: ...", false);
        rowKeys1.addView(pressKeyPill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        rowKeys1.addView(spacing(dp(6)));
        rowKeys1.addView(longPressKeyPill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout rowKeys2 = new LinearLayout(context);
        rowKeys2.setOrientation(LinearLayout.HORIZONTAL);
        toggleOnKeyPill = pill("On: ...", false);
        toggleOffKeyPill = pill("Off: ...", false);
        rowKeys2.addView(toggleOnKeyPill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        rowKeys2.addView(spacing(dp(6)));
        rowKeys2.addView(toggleOffKeyPill, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        stateBindingsContainer.addView(rowKeys1);
        stateBindingsContainer.addView(rowKeys2, margin(0, dp(6), 0, 0));
        layout.addView(stateBindingsContainer, margin(0, dp(8), 0, 0));

        // a cutscene's SKIP shows only while the cutscene can be skipped
        skipOnlyPill = pill("Only when skippable: On", false);
        layout.addView(skipOnlyPill, margin(0, dp(8), 0, 0));

        // Custom action / rename label / choose icon / delete (Fully Responsive)
        LinearLayout customRow = new LinearLayout(context);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);

        renamePill = pill("Rename", false);
        iconPill = pill("Icon: Default", false);
        deletePill = pill("Delete", false);
        styleDangerPill(deletePill);

        // Make icon pill dynamically shrink & truncate in the middle when filename is long
        iconPill.setSingleLine(true);
        iconPill.setEllipsize(TextUtils.TruncateAt.MIDDLE);

        renamePill.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        iconParams.setMargins(dp(6), 0, dp(6), 0);
        iconPill.setLayoutParams(iconParams);
        deletePill.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        customRow.addView(renamePill);
        customRow.addView(iconPill);
        customRow.addView(deletePill);
        layout.addView(customRow, margin(0, dp(8), 0, 0));

        addView(layout);

        // Listeners
        enableControlsCheck.setOnCheckedChangeListener((btn, isChecked) -> {
            if (!updating) controls.setControlsEnabled(isChecked);
        });

        sizeBar.setOnSeekBarChangeListener(new Changed() {
            @Override
            void changed(int progress) {
                controls.setSelectedScale(TouchControls.SIZE_MIN + progress / 100f);
                refreshValues();
            }
        });
        opacityBar.setOnSeekBarChangeListener(new Changed() {
            @Override
            void changed(int progress) {
                controls.setOpacity(TouchControls.OPACITY_MIN + progress / 100f);
                refreshValues();
            }
        });
        lookBar.setOnSeekBarChangeListener(new Changed() {
            @Override
            void changed(int progress) {
                controls.setLookSpeed(TouchControls.LOOK_MIN + progress / 100f);
                refreshValues();
            }
        });

        showPill.setOnClickListener(v -> {
            controls.setSelectedVisible(!controls.selectedVisible());
            refresh();
        });
        hapticsPill.setOnClickListener(v -> {
            controls.setHaptics(!controls.haptics());
            refresh();
        });
        reset.setOnClickListener(v -> showResetDialog());
        skipOnlyPill.setOnClickListener(v -> {
            controls.setSelectedOnlySkippable(!controls.selectedOnlySkippable());
            controls.save();
            refresh();
        });
        done.setOnClickListener(v -> controls.setEditMode(false));
        addBtn.setOnClickListener(v -> showAddButtonDialog());
        deletePill.setOnClickListener(v -> {
            controls.removeSelectedControl();
            refresh();
        });

        modePill.setOnClickListener(v -> {
            int current = controls.selectedMode();
            int next = (current + 1) % 3;
            controls.setSelectedMode(next);
            refresh();
            controls.save();
        });

        pressKeyPill.setOnClickListener(v -> showKeycodePicker("press", "Standard Press / Tap Key"));
        longPressKeyPill.setOnClickListener(v -> showKeycodePicker("long", "Long-Press / Hold Key"));
        toggleOnKeyPill.setOnClickListener(v -> showKeycodePicker("toggle_on", "Toggle ON Key"));
        toggleOffKeyPill.setOnClickListener(v -> showKeycodePicker("toggle_off", "Toggle OFF Key"));

        renamePill.setOnClickListener(v -> showRenameDialog());
        iconPill.setOnClickListener(v -> showIconOrStickDialog());

        controls.setEditListener(this);
        refresh();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Constrain height so the panel never exceeds 86% of the screen height in landscape
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        int maxHeight = (int) (dm.heightPixels * 0.86f);
        int constrainedHeight = MeasureSpec.makeMeasureSpec(maxHeight, MeasureSpec.AT_MOST);
        super.onMeasure(widthMeasureSpec, constrainedHeight);
    }

    private void showResetDialog() {
        final int set = controls.editingSet();
        String[] options = {
            "Reset the " + TouchControls.SET_NAMES[set] + " controls",
            "Reset all controls and settings"
        };

        new AlertDialog.Builder(getContext())
            .setTitle("Reset")
            .setItems(options, (dialog, which) -> {
                if (which == 0) controls.resetSet(set);
                else controls.resetLayout();
                refresh();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showAddButtonDialog() {
        Context ctx = getContext();
        LinearLayout dialogLayout = new LinearLayout(ctx);
        dialogLayout.setOrientation(LinearLayout.VERTICAL);
        dialogLayout.setPadding(dp(18), dp(8), dp(18), dp(8));

        final EditText idInput = new EditText(ctx);
        idInput.setHint("Button ID (e.g. fire_extra, b_jump)");
        idInput.setSingleLine(true);
        dialogLayout.addView(idInput);

        final EditText labelInput = new EditText(ctx);
        labelInput.setHint("Display Label (e.g. EXTRA)");
        labelInput.setSingleLine(true);
        dialogLayout.addView(labelInput);

        new AlertDialog.Builder(ctx)
            .setTitle("Create Custom Button (" + TouchControls.SET_NAMES[controls.editingSet()] + ")")
            .setView(dialogLayout)
            .setPositiveButton("Create", (dialog, which) -> {
                String id = idInput.getText().toString().trim().toLowerCase();
                String label = labelInput.getText().toString().trim();

                if (!id.matches("^[a-z0-9_]+$")) {
                    Toast.makeText(ctx, "Invalid ID. Only lowercase letters, numbers, and '_' allowed.", Toast.LENGTH_LONG).show();
                    return;
                }
                if (!controls.isIdAvailable(id)) {
                    Toast.makeText(ctx, "Error: A control with ID '" + id + "' already exists!", Toast.LENGTH_LONG).show();
                    return;
                }
                if (label.isEmpty()) label = id.toUpperCase();

                controls.addNewButtonWithId(id, label);
                refresh();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showIconOrStickDialog() {
        TouchControls.Control sel = controls.getSelected();
        if (sel == null) return;

        if (sel.type == TouchControls.TYPE_STICK) {
            String[] options = { "Set Stick Base Drawable", "Set Stick Knob Drawable" };
            new AlertDialog.Builder(getContext())
                .setTitle("Customize Joystick")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showIconFilePicker("Select Joystick Base", false);
                    else showIconFilePicker("Select Joystick Knob", true);
                })
                .show();
            return;
        }

        showIconFilePicker("Select Icon Drawable", false);
    }

    private void showIconFilePicker(String title, boolean isKnob) {
        List<String> files = controls.listAvailableCustomIcons();
        final String[] options = new String[files.size() + 2];
        options[0] = "Default / Built-in";
        TouchControls.Control current = controls.getSelected();
        options[1] = isKnob ? "Auto (stick_knob.png)"
            : current != null ? "Auto (" + current.setId + "_" + current.id + ".png or " + current.id + ".png)" : "Auto";
        for (int i = 0; i < files.size(); i++) {
            options[i + 2] = files.get(i);
        }

        new AlertDialog.Builder(getContext())
            .setTitle(title)
            .setItems(options, (dialog, which) -> {
                String targetFile;
                if (which == 0) {
                    targetFile = "";
                } else if (which == 1) {
                    targetFile = isKnob ? "stick_knob.png" : (controls.getSelected().id + ".png");
                } else {
                    targetFile = files.get(which - 2);
                }

                if (isKnob) {
                    controls.setStickKnobFile(targetFile);
                } else {
                    controls.setSelectedIconFile(targetFile);
                }
                controls.save();
                refresh();
            })
            .show();
    }

    private void showKeycodePicker(final String stateType, String title) {
        final String[] names = {
            "None (Disabled)", "Fire (Mouse Left)", "Jump (Space)", "Reload (R)",
            "Melee (F)", "Crouch (C)", "Grenade (G)", "Zoom (Z)", "Switch Weapon (1)",
            "Switch Grenade (X)", "Flashlight (Q)", "Pause Menu (Esc)", "Show Scores (Tab)",
            "D-pad Up", "D-pad Down", "D-pad Left", "D-pad Right", "Custom Keycode..."
        };
        final int[] codes = {
            TouchControls.NONE, TouchControls.FIRE, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_R,
            KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_1,
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, -99
        };

        new AlertDialog.Builder(getContext())
            .setTitle(title)
            .setItems(names, (dialog, which) -> {
                if (codes[which] == -99) {
                    showCustomKeycodeDialog(stateType);
                } else {
                    controls.setSelectedKey(stateType, codes[which]);
                    controls.save();
                    refresh();
                }
            })
            .show();
    }

    private void showCustomKeycodeDialog(final String stateType) {
        EditText input = new EditText(getContext());
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setHint("Android KeyEvent code (e.g. 62)");

        new AlertDialog.Builder(getContext())
            .setTitle("Enter Keycode Number")
            .setView(input)
            .setPositiveButton("Set", (dialog, which) -> {
                try {
                    int val = Integer.parseInt(input.getText().toString().trim());
                    controls.setSelectedKey(stateType, val);
                    controls.save();
                    refresh();
                } catch (NumberFormatException ignored) {}
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void showRenameDialog() {
        EditText input = new EditText(getContext());
        input.setText(controls.selectedLabel());

        new AlertDialog.Builder(getContext())
            .setTitle("Button Label")
            .setView(input)
            .setPositiveButton("Save", (dialog, which) -> {
                String str = input.getText().toString().trim();
                if (!str.isEmpty()) {
                    controls.setSelectedLabel(str);
                    controls.save();
                    refresh();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onEditStateChanged(boolean editing) {
        setVisibility(editing ? VISIBLE : GONE);
        if (editing) {
            controls.refreshAllDrawables();
            refresh();
        }
    }

    @Override
    public void onSelectionChanged() {
        refresh();
    }

    private String formatKey(int key) {
        if (key == TouchControls.NONE) return "None";
        if (key == TouchControls.FIRE) return "FIRE";
        if (key == TouchControls.EDIT) return "EDIT";
        String name = KeyEvent.keyCodeToString(key);
        // KEYCODE_DPAD_UP -> DPAD_UP; a code it does not know stays a number
        return name.startsWith("KEYCODE_") ? name.substring(8) : "Key " + key;
    }

    private void refresh() {
        TouchControls.Control sel = controls.getSelected();
        boolean has = sel != null;

        updating = true;
        enableControlsCheck.setChecked(controls.areControlsEnabled());
        int set = controls.editingSet();
        for (int i = 0; i < setTabs.length; i++)
            stylePill(setTabs[i], i == set);
        hint.setText(has ? sel.name + ": drag to move or tap options below"
                         : "Editing the " + TouchControls.SET_NAMES[set].toLowerCase()
                           + " controls. Tap a control to select it, or drag it.");
        sizeBar.setEnabled(has);
        sizeBar.setAlpha(has ? 1f : 0.4f);
        sizeBar.setProgress(Math.round((controls.selectedScale() - TouchControls.SIZE_MIN) * 100f));
        opacityBar.setProgress(Math.round((controls.opacity() - TouchControls.OPACITY_MIN) * 100f));
        lookBar.setProgress(Math.round((controls.lookSpeed() - TouchControls.LOOK_MIN) * 100f));

        boolean canShow = has && !controls.selectedLocked();
        showPill.setEnabled(canShow);
        showPill.setAlpha(canShow ? 1f : 0.4f);
        stylePill(showPill, canShow && controls.selectedVisible());
        stylePill(hapticsPill, controls.haptics());

        boolean canDelete = has && !sel.locked && !"edit".equals(sel.id);
        deletePill.setVisibility(canDelete ? VISIBLE : GONE);

        if (has && sel.type == TouchControls.TYPE_BUTTON) {
            modePill.setVisibility(VISIBLE);
            stateBindingsContainer.setVisibility(VISIBLE);
            renamePill.setVisibility(VISIBLE);
            iconPill.setVisibility(VISIBLE);

            int mode = sel.behaviorMode;
            if (mode == TouchControls.MODE_STANDARD) {
                modePill.setText("Mode: Standard");
                pressKeyPill.setVisibility(VISIBLE);
                longPressKeyPill.setVisibility(GONE);
                toggleOnKeyPill.setVisibility(GONE);
                toggleOffKeyPill.setVisibility(GONE);
            } else if (mode == TouchControls.MODE_TAP_AND_HOLD) {
                modePill.setText("Mode: Tap + Hold");
                pressKeyPill.setVisibility(VISIBLE);
                longPressKeyPill.setVisibility(VISIBLE);
                toggleOnKeyPill.setVisibility(GONE);
                toggleOffKeyPill.setVisibility(GONE);
            } else {
                modePill.setText("Mode: Toggle");
                pressKeyPill.setVisibility(GONE);
                longPressKeyPill.setVisibility(GONE);
                toggleOnKeyPill.setVisibility(VISIBLE);
                toggleOffKeyPill.setVisibility(VISIBLE);
            }

            pressKeyPill.setText("Press: " + formatKey(sel.pressKey));
            longPressKeyPill.setText("Hold: " + formatKey(sel.longPressKey));
            toggleOnKeyPill.setText("On: " + formatKey(sel.toggleOnKey));
            toggleOffKeyPill.setText("Off: " + formatKey(sel.toggleOffKey));

            String iconName = (sel.customIconFile != null && !sel.customIconFile.isEmpty())
                    ? sel.customIconFile : (sel.icon != null ? "Icon" : "None");
            iconPill.setText("Icon: " + iconName);
        } else if (has && sel.type == TouchControls.TYPE_STICK) {
            modePill.setVisibility(GONE);
            stateBindingsContainer.setVisibility(GONE);
            renamePill.setVisibility(GONE);
            iconPill.setVisibility(VISIBLE);
            iconPill.setText("Stick Icons...");
        } else {
            modePill.setVisibility(GONE);
            stateBindingsContainer.setVisibility(GONE);
            renamePill.setVisibility(GONE);
            iconPill.setVisibility(GONE);
        }

        boolean canSkipOnly = has && !sel.locked && sel.type == TouchControls.TYPE_BUTTON
            && set == TouchControls.SET_CUTSCENE;

        skipOnlyPill.setVisibility(canSkipOnly ? VISIBLE : GONE);
        if (canSkipOnly)
            skipOnlyPill.setText("Only when skippable: " + (sel.onlyWhenSkippable ? "On" : "Off"));
        stylePill(skipOnlyPill, canSkipOnly && sel.onlyWhenSkippable);

        updating = false;
        refreshValues();
    }

    private void refreshValues() {
        sizeValue.setText(controls.selectedName() != null
                ? Math.round(controls.selectedScale() * 100f) + "%" : "");
        opacityValue.setText(Math.round(controls.opacity() * 100f) + "%");
        lookValue.setText(Math.round(controls.lookSpeed() * 100f) + "%");
    }

    private void makeHandle(View handle) {
        handle.setOnTouchListener(new OnTouchListener() {
            private float downX, downY, startX, startY;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = getTranslationX();
                        startY = getTranslationY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        setTranslationX(startX + e.getRawX() - downX);
                        setTranslationY(startY + e.getRawY() - downY);
                        return true;
                    default:
                        return true;
                }
            }
        });
    }

    private abstract class Changed implements SeekBar.OnSeekBarChangeListener {
        abstract void changed(int progress);
        @Override public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
            if (fromUser && !updating) changed(progress);
        }
        @Override public void onStartTrackingTouch(SeekBar bar) {}
        @Override public void onStopTrackingTouch(SeekBar bar) { controls.save(); }
    }

    private int dp(float value) { return Math.round(value * density); }

    private View spacing(int width) {
        View v = new View(getContext());
        v.setLayoutParams(new LinearLayout.LayoutParams(width, 1));
        return v;
    }

    private LinearLayout.LayoutParams margin(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(l, t, r, b);
        return p;
    }

    private TextView text(String s, float sp, boolean bold, int color) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView pill(String s, boolean filled) {
        TextView t = text(s, 11, true, 0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(10), dp(5), dp(10), dp(5));
        t.setClickable(true);
        stylePill(t, filled);
        return t;
    }

    private void stylePill(TextView t, boolean filled) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        if (filled) {
            g.setColor(ACCENT);
            t.setTextColor(0xFF06222E);
        } else {
            g.setColor(0x22FFFFFF);
            g.setStroke(dp(1), 0x66FFFFFF);
            t.setTextColor(0xFFFFFFFF);
        }
        t.setBackground(g);
    }

    private void styleDangerPill(TextView t) {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(16));
        g.setColor(0x33FF5252);
        g.setStroke(dp(1), DELETE_COLOR);
        t.setTextColor(DELETE_COLOR);
        t.setBackground(g);
    }

    private SeekBar slider(int max) {
        SeekBar bar = new SeekBar(getContext());
        bar.setMax(max);
        bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
        bar.setThumbTintList(ColorStateList.valueOf(ACCENT));
        return bar;
    }

    private LinearLayout row(String label, SeekBar bar, TextView value) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 12, false, 0xFFFFFFFF);
        row.addView(name, new LinearLayout.LayoutParams(dp(78), LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(bar, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        value.setGravity(Gravity.END);
        row.addView(value, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));
        return row;
    }
}
