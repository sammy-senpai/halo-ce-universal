package com.halo.decomp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import org.libsdl.app.SDLActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TouchControls extends View {
    public interface EditListener {
        void onEditStateChanged(boolean editing);
        void onSelectionChanged();
    }

    public static final int NONE = 0;
    public static final int FIRE = -1;
    public static final int EDIT = -2;

    public static final int MODE_STANDARD = 0;
    public static final int MODE_TAP_AND_HOLD = 1;
    public static final int MODE_TOGGLE = 2;

    private static final float LOOK_BASE = 2.5f;
    private static final float LOOK_ACCEL = 0.8f;
    private static final float LOOK_FAST_DP_PER_MS = 1.2f;

    private static final float STICK_DEAD_ZONE = 0.28f;
    private static final float STICK_DIAGONAL = 0.41f;
    private static final float STICK_SIDE = 0.42f;

    private static final float HIT_SLOP_DP = 10f;
    private static final long EDIT_HOLD_MS = 450;
    public static final long LONG_PRESS_TIMEOUT_MS = 300;
    private static final float PRESS_MS = 80f;
    private static final float SNAP_DP = 4f;
    /** a key is held at least this long: the game looks at its input 30
     *  times a second, and a quicker tap would fall between two looks */
    private static final long MIN_HOLD_MS = 50;
    private static final float FADE_MS = 160f;
    /** how often the game's state is asked for */
    private static final long STATE_POLL_MS = 50;

    /** the control sets: each has its own buttons, layout, keys and icons,
     *  and shows when the game is in the state it is for (GameState) */
    public static final int SET_GAME = 0, SET_MENU = 1, SET_CUTSCENE = 2;
    private static final int SET_COUNT = 3;
    private static final String[] SET_IDS = { "game", "menu", "cutscene" };
    public static final String[] SET_NAMES = { "Game", "Menu", "Cutscene" };
    private static final int FILE_VERSION = 2;

    public static final float SIZE_MIN = 0.6f, SIZE_MAX = 2.0f;
    public static final float OPACITY_MIN = 0.25f, OPACITY_MAX = 1f;
    public static final float LOOK_MIN = 0.5f, LOOK_MAX = 2f;
    private static final float DEFAULT_OPACITY = 1.0f;

    private static final int ACCENT = 0xFF4FC3F7;
    private static final int SCRIM = 0x73000000;

    public static final int TYPE_BUTTON = 0;
    public static final int TYPE_STICK = 1;
    public static final int TL = 0, TR = 1, BL = 2, BR = 3;

    private static final String ICON_PREFIX = "tc_ic_";
    private static final int[] STATE_PRESSED = { android.R.attr.state_pressed };
    private static final int[] STATE_IDLE = new int[0];

    public static final class Control {
        public String id, name, label;
        public int type;
        public int anchor;
        public float defDx, defDy, defScale, radius;
        public boolean defVisible, locked, custom;
        /** the set it is in (its id) */
        public String setId = "game";
        /** shown only while the cutscene can be skipped (SKIP) */
        public boolean onlyWhenSkippable, defOnlySkippable;

        public int behaviorMode = MODE_STANDARD;
        public int pressKey = NONE;
        public int longPressKey = NONE;
        public int toggleOnKey = NONE;
        public int toggleOffKey = NONE;

        public String customIconFile = "";
        public String customKnobFile = ""; // Used if type == TYPE_STICK
        public float dx, dy, scale = 1f;
        public boolean visible;

        // Runtime state
        public float cx, cy, r;
        public int pointer = -1;
        public float press;
        public boolean toggledState = false;
        public boolean longPressTriggered = false;
        public Runnable pendingLongPress = null;
        public Runnable pendingRelease = null;
        public long downAt;
        public Drawable icon;

        Control(String id, String name, String label, int type, int defaultKey,
                int anchor, float dx, float dy, float scale, float radius, boolean visible, boolean locked, boolean custom) {
            this.id = id;
            this.name = name;
            this.label = label;
            this.type = type;
            this.pressKey = defaultKey;
            this.toggleOnKey = defaultKey;
            this.anchor = anchor;
            this.defDx = dx;
            this.defDy = dy;
            this.defScale = scale;
            this.radius = radius;
            this.defVisible = visible;
            this.locked = locked;
            this.custom = custom;
            reset();
        }

        void reset() {
            dx = defDx;
            dy = defDy;
            scale = defScale;
            visible = defVisible;
            onlyWhenSkippable = defOnlySkippable;
            toggledState = false;
            longPressTriggered = false;
            pendingLongPress = null;
            pendingRelease = null;
        }
    }

    /** one set of controls: the game's, a menu's, a cutscene's */
    private static final class ControlSet {
        final int index;
        final String id;
        /** a drag on the free side of the screen turns the view (the game's) */
        final boolean look;
        final List<Control> controls = new ArrayList<>();
        Control stick, editButton;

        ControlSet(int index) {
            this.index = index;
            this.id = SET_IDS[index];
            this.look = index == SET_GAME;
        }
    }

    private final ControlSet[] sets = new ControlSet[SET_COUNT];
    /** the set in use, and its controls, stick and EDIT: the game's state
     *  chooses it, and so does the editor (editSet) */
    private int activeSet = SET_GAME;
    private List<Control> controls;
    private Control stick;
    private Control editButton;

    private static final int MAX_POINTERS = 32;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final float density;
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint holdPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final DashPathEffect dashes;
    private Drawable knob;
    private Drawable stickBase;

    // Movement & Look
    private final float[] lastX = new float[MAX_POINTERS];
    private final float[] lastY = new float[MAX_POINTERS];
    private final long[] lastTime = new long[MAX_POINTERS];
    private int lookPointer = -1;
    private float stickX, stickY;
    private float knobX, knobY;
    private float stickGlow;
    private final boolean[] stickHeld = new boolean[4];
    private final boolean[] stickWant = new boolean[4];
    private static final int[] STICK_KEYS = {
        KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_D
    };

    private float opacity = DEFAULT_OPACITY;
    private float lookSpeed = 1f;
    private boolean haptics = true;
    private boolean controlsEnabled = true;

    // what the game is doing
    private int situation = GameState.GAMEPLAY;
    private boolean skippable;
    /** 0 hidden, 1 shown, eased: sets fade in, and out for a loading map */
    private float uiAlpha = 1f;
    private long lastFade;
    private int editSet = SET_GAME;
    private final Runnable pollState = new Runnable() {
        @Override
        public void run() {
            applyState(GameState.flags());
            postDelayed(this, STATE_POLL_MS);
        }
    };

    private float fit = 1f;
    private int padLeft, padRight;
    private boolean hidden;
    private long lastFrame;

    private boolean editMode;
    private Control selected;
    private Control dragging;
    private float grabDx, grabDy;
    private long editHoldStart;
    private EditListener editListener;
    private final List<Rect> gestureExclusion = new ArrayList<>(1);

    private final Runnable openEditor = () -> {
        editHoldStart = 0;
        setEditMode(true);
    };

    public TouchControls(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        labelPaint.setColor(0xFFFFFFFF);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setLetterSpacing(0.04f);

        outlinePaint.setStyle(Paint.Style.STROKE);
        dashes = new DashPathEffect(new float[] { 6f * density, 5f * density }, 0f);

        holdPaint.setStyle(Paint.Style.STROKE);
        holdPaint.setStrokeCap(Paint.Cap.ROUND);
        holdPaint.setStrokeWidth(3f * density);
        holdPaint.setColor(ACCENT);

        for (int i = 0; i < SET_COUNT; i++)
            sets[i] = new ControlSet(i);
        setupAllDefaults();
        pointAt(SET_GAME);
        load();
        pointAt(SET_GAME);
        refreshAllDrawables();

        setFocusable(false);
        setHapticFeedbackEnabled(true);
    }

    // ---------- the sets' default controls

    private void setupAllDefaults() {
        for (ControlSet set : sets)
            buildDefaults(set);
    }

    /*
     * Positions are in dp inward from the anchor's two edges, on an
     * 800 x 360 dp phone.
     */
    private void buildDefaults(ControlSet set) {
        set.controls.clear();
        set.stick = null;
        set.editButton = null;

        switch (set.index) {
            case SET_MENU:
                // the D-pad, left; the four face buttons, right (A at the
                // bottom, B right, X left, Y top, as on the controller)
                addDefault(set, "dpad_up",    "D-pad up",    "UP",    TYPE_BUTTON, KeyEvent.KEYCODE_DPAD_UP,    BL, 118, 170, 1.00f, 26, true,  false, MODE_STANDARD, KeyEvent.KEYCODE_DPAD_UP,    0, KeyEvent.KEYCODE_DPAD_UP,    0);
                addDefault(set, "dpad_down",  "D-pad down",  "DOWN",  TYPE_BUTTON, KeyEvent.KEYCODE_DPAD_DOWN,  BL, 118,  58, 1.00f, 26, true,  false, MODE_STANDARD, KeyEvent.KEYCODE_DPAD_DOWN,  0, KeyEvent.KEYCODE_DPAD_DOWN,  0);
                addDefault(set, "dpad_left",  "D-pad left",  "LEFT",  TYPE_BUTTON, KeyEvent.KEYCODE_DPAD_LEFT,  BL,  60, 114, 1.00f, 26, true,  false, MODE_STANDARD, KeyEvent.KEYCODE_DPAD_LEFT,  0, KeyEvent.KEYCODE_DPAD_LEFT,  0);
                addDefault(set, "dpad_right", "D-pad right", "RIGHT", TYPE_BUTTON, KeyEvent.KEYCODE_DPAD_RIGHT, BL, 176, 114, 1.00f, 26, true,  false, MODE_STANDARD, KeyEvent.KEYCODE_DPAD_RIGHT, 0, KeyEvent.KEYCODE_DPAD_RIGHT, 0);
                addDefault(set, "ok",         "Accept (A)",  "OK",    TYPE_BUTTON, KeyEvent.KEYCODE_ENTER,      BR, 118,  58, 1.00f, 28, true,  false, MODE_STANDARD, KeyEvent.KEYCODE_ENTER,      0, KeyEvent.KEYCODE_ENTER,      0);
                addDefault(set, "cancel",     "Back (B)",    "BACK",  TYPE_BUTTON, KeyEvent.KEYCODE_BACK,          BR,  58, 114, 1.00f, 28, true,  false, MODE_STANDARD, KeyEvent.KEYCODE_BACK,          0, KeyEvent.KEYCODE_BACK,          0);
                addDefault(set, "x",          "X button",    "X",     TYPE_BUTTON, KeyEvent.KEYCODE_E,          BR, 178, 114, 1.00f, 28, true,  false, MODE_STANDARD, KeyEvent.KEYCODE_E,          0, KeyEvent.KEYCODE_E,          0);
                addDefault(set, "y",          "Y button",    "Y",     TYPE_BUTTON, KeyEvent.KEYCODE_TAB,        BR, 118, 170, 1.00f, 28, true,  false, MODE_STANDARD, KeyEvent.KEYCODE_TAB,        0, KeyEvent.KEYCODE_TAB,        0);
                addDefault(set, "start",      "Start",       "START", TYPE_BUTTON, KeyEvent.KEYCODE_ESCAPE,     TR,  36,  36, 1.00f, 20, true,  false, MODE_STANDARD, KeyEvent.KEYCODE_ESCAPE,     0, KeyEvent.KEYCODE_ESCAPE,     0);
                addDefault(set, "edit",       "Edit button", "EDIT",  TYPE_BUTTON, EDIT,                        TL,  30,  36, 1.00f, 20, true,  true,  MODE_STANDARD, EDIT,                        0, EDIT,                        0);
                break;

            case SET_CUTSCENE: {
                Control skip = addDefault(set, "skip", "Skip cutscene", "SKIP", TYPE_BUTTON, KeyEvent.KEYCODE_SPACE, TR, 88, 36, 1.00f, 20, true, false, MODE_STANDARD, KeyEvent.KEYCODE_SPACE, 0, KeyEvent.KEYCODE_SPACE, 0);
                skip.onlyWhenSkippable = skip.defOnlySkippable = true;
                addDefault(set, "menu", "Pause menu",  "MENU", TYPE_BUTTON, KeyEvent.KEYCODE_ESCAPE, TR,  36, 36, 1.00f, 20, true,  false, MODE_STANDARD, KeyEvent.KEYCODE_ESCAPE, 0, KeyEvent.KEYCODE_ESCAPE, 0);
                addDefault(set, "edit", "Edit button", "EDIT", TYPE_BUTTON, EDIT,                    TL,  30, 36, 1.00f, 20, true,  true,  MODE_STANDARD, EDIT,                    0, EDIT,                    0);
                break;
            }

            default:
                addDefault(set, "fire",    "Fire",           "FIRE",    TYPE_BUTTON, FIRE,                    BR,  92,  92, 1.00f, 44, true,  false, MODE_STANDARD,     FIRE, 0, FIRE, 0);
                addDefault(set, "zoom",    "Zoom",           "ZOOM",    TYPE_BUTTON, KeyEvent.KEYCODE_Z,      BR,  60, 172, 1.00f, 28, true,  false, MODE_STANDARD,     54,   0, 54,   0);
                addDefault(set, "jump",    "Jump",           "JUMP",    TYPE_BUTTON, KeyEvent.KEYCODE_SPACE,  BR, 260,  50, 1.00f, 26, true,  false, MODE_STANDARD,     62,   0, 62,   0);
                addDefault(set, "melee",   "Melee",          "MELEE",   TYPE_BUTTON, KeyEvent.KEYCODE_F,      BR, 176, 116, 1.00f, 28, true,  false, MODE_STANDARD,     34,   0, 34,   0);
                addDefault(set, "reload",  "Reload / use",   "RELOAD",  TYPE_BUTTON, KeyEvent.KEYCODE_R,      BR, 160,  44, 1.00f, 27, true,  false, MODE_STANDARD,     46,   0, 46,   0);
                addDefault(set, "swap",    "Switch weapon",  "SWAP",    TYPE_BUTTON, KeyEvent.KEYCODE_1,    BR, 132, 172, 1.00f, 24, true,  false, MODE_TAP_AND_HOLD, 8,  33, 8,   0);
                addDefault(set, "stick",   "Move stick",     "MOVE",    TYPE_STICK,  0,                       BL, 112, 104, 1.00f, 58, true,  false, MODE_STANDARD,      0,   0,  0,   0);
                addDefault(set, "grenade", "Grenade",        "GRENADE", TYPE_BUTTON, KeyEvent.KEYCODE_G,      BL,  52, 252, 1.00f, 30, true,  false, MODE_STANDARD,     35,   0, 35,   0);
                addDefault(set, "crouch",  "Crouch",         "CROUCH",  TYPE_BUTTON, KeyEvent.KEYCODE_C,      BL, 214,  50, 1.00f, 26, true,  false, MODE_TOGGLE,       31,   0, 31,   0);
                addDefault(set, "gtype",   "Grenade type",   "TYPE",    TYPE_BUTTON, KeyEvent.KEYCODE_X,      BL, 116, 220, 1.00f, 20, true,  false, MODE_STANDARD,     52,   0, 52,   0);
                addDefault(set, "light",   "Flashlight",     "LIGHT",   TYPE_BUTTON, KeyEvent.KEYCODE_Q,      TR,  88,  36, 1.00f, 20, true,  false, MODE_STANDARD,     45,   0, 45,   0);
                addDefault(set, "menu",    "Pause menu",     "MENU",    TYPE_BUTTON, KeyEvent.KEYCODE_ESCAPE, TR,  36,  36, 1.00f, 20, true,  false, MODE_STANDARD,    111,   0, 111,  0);
                addDefault(set, "edit",    "Edit button",    "EDIT",    TYPE_BUTTON, EDIT,                    TL,  30,  36, 1.00f, 20, true,  true,  MODE_STANDARD,     -2,   0, -2,   0);
                addDefault(set, "fire2",   "Second fire",    "FIRE",    TYPE_BUTTON, FIRE,                    BL, 144, 320, 1.00f, 32, false, false, MODE_STANDARD,     FIRE, 0, FIRE, 0);
                addDefault(set, "back",    "Back / scores",  "BACK",    TYPE_BUTTON, KeyEvent.KEYCODE_F1,     TR, 140,  36, 1.00f, 20, true,  false, MODE_STANDARD,    131,   0, 131,  0);
                break;
        }
        set.stick = find(set, "stick");
        set.editButton = find(set, "edit");
    }

    private Control addDefault(ControlSet set, String id, String name, String label, int type, int key,
                               int anchor, float dx, float dy, float scale, float radius, boolean visible, boolean locked,
                               int mode, int pressKey, int longKey, int toggleOn, int toggleOff) {
        Control c = new Control(id, name, label, type, key, anchor, dx, dy, scale, radius, visible, locked, false);
        c.setId = set.id;
        c.behaviorMode = mode;
        c.pressKey = pressKey;
        c.longPressKey = longKey;
        c.toggleOnKey = toggleOn;
        c.toggleOffKey = toggleOff;
        reloadControlIcon(c);
        set.controls.add(c);
        return c;
    }

    private static Control find(ControlSet set, String id) {
        for (Control c : set.controls)
            if (c.id.equals(id))
                return c;
        return null;
    }

    /** makes a set the one in use (without letting go of the old one's
     *  inputs: useSet does) */
    private void pointAt(int index) {
        ControlSet set = sets[index];

        activeSet = index;
        controls = set.controls;
        stick = set.stick;
        editButton = set.editButton;
    }

    public File getExternalFilesDirectory() {
        File dir = getContext().getExternalFilesDir(null);
        if (dir == null) dir = getContext().getFilesDir();
        return dir;
    }

    public List<String> listAvailableCustomIcons() {
        List<String> list = new ArrayList<>();
        File baseDir = getExternalFilesDirectory();
        collectImages(baseDir, list, "");
        File iconsDir = new File(baseDir, "icons");
        if (iconsDir.exists() && iconsDir.isDirectory()) {
            collectImages(iconsDir, list, "icons/");
        }
        Collections.sort(list);
        return list;
    }

    private void collectImages(File dir, List<String> result, String prefix) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (!f.isDirectory()) {
                String n = f.getName().toLowerCase();
                if (n.endsWith(".png") || n.endsWith(".webp") || n.endsWith(".jpg")) {
                    result.add(prefix + f.getName());
                }
            }
        }
    }

    private Drawable loadExternalDrawable(String filename) {
        if (filename == null || filename.trim().isEmpty()) return null;
        File baseDir = getExternalFilesDirectory();
        File target = new File(filename.startsWith("/") ? filename : baseDir.getAbsolutePath() + "/" + filename);
        if (!target.exists()) {
            target = new File(baseDir, "icons/" + filename);
        }
        if (target.exists() && target.isFile()) {
            try {
                Bitmap bmp = BitmapFactory.decodeFile(target.getAbsolutePath());
                if (bmp != null) return new BitmapDrawable(getResources(), bmp);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private Drawable loadExternalOrResourceDrawable(String filename, int resId) {
        Drawable ext = loadExternalDrawable(filename);
        if (ext != null) return ext;
        return loadResourceDrawable(resId);
    }

    private Drawable loadResourceDrawable(int id) {
        if (id == 0) return null;
        try {
            Drawable d = getContext().getDrawable(id);
            return d != null ? d.mutate() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void reloadControlIcon(Control c) {
        if (c.type == TYPE_STICK) {
            stickBase = loadExternalOrResourceDrawable(
                (c.customIconFile != null && !c.customIconFile.isEmpty()) ? c.customIconFile : "stick_base.png",
                R.drawable.tc_stick_base
            );
            knob = loadExternalOrResourceDrawable(
                (c.customKnobFile != null && !c.customKnobFile.isEmpty()) ? c.customKnobFile : "stick_knob.png",
                R.drawable.tc_stick_knob
            );
            return;
        }

        if (c.customIconFile != null && !c.customIconFile.isEmpty()) {
            Drawable d = loadExternalDrawable(c.customIconFile);
            if (d != null) {
                c.icon = d;
                return;
            }
        }

        // files/menu_ok.png (this set's own) before files/ok.png
        Drawable autoExt = loadExternalDrawable(c.setId + "_" + c.id + ".png");
        if (autoExt == null) autoExt = loadExternalDrawable(c.id + ".png");
        if (autoExt != null) {
            c.icon = autoExt;
            return;
        }

        // the app's tc_ic_menu_ok before tc_ic_ok
        String pkg = getContext().getPackageName();
        int resId = getContext().getResources().getIdentifier(ICON_PREFIX + c.setId + "_" + c.id, "drawable", pkg);
        if (resId == 0) resId = getContext().getResources().getIdentifier(ICON_PREFIX + c.id, "drawable", pkg);
        if (resId != 0) {
            c.icon = loadResourceDrawable(resId);
            return;
        }

        c.icon = null;
    }

    public void refreshAllDrawables() {
        for (ControlSet set : sets) {
            for (Control c : set.controls) {
                reloadControlIcon(c);
            }
        }
        invalidate();
    }

    /** a control of the set in use (the one edited, in the editor) */
    public Control byId(String id) {
        for (Control c : controls) {
            if (c.id.equals(id)) return c;
        }
        return null;
    }

    public boolean isIdAvailable(String id) {
        return byId(id) == null;
    }

    public void setEditListener(EditListener listener) { editListener = listener; }
    public boolean isEditMode() { return editMode; }

    public void setEditMode(boolean on) {
        if (on == editMode) return;
        releaseAll();
        editMode = on;
        dragging = null;
        select(null);
        if (on) {
            // the editor opens on the set that is showing
            editSet = activeSet;
        } else {
            save();
        }
        syncSet();
        if (editListener != null) editListener.onEditStateChanged(on);
        invalidate();
    }

    /** the set the editor is on */
    public int editingSet() { return editSet; }

    /** the editor's tabs: another set's controls to edit */
    public void setEditingSet(int set) {
        if (!editMode || set < 0 || set >= SET_COUNT || set == editSet) return;
        save();
        dragging = null;
        editSet = set;
        syncSet();
        select(null);
        if (editListener != null) editListener.onSelectionChanged();
        invalidate();
    }

    public void setHidden(boolean hide) {
        if (hide == hidden) return;
        hidden = hide;
        if (hide) {
            releaseAll();
            setEditMode(false);
        }
        setVisibility(hide ? GONE : VISIBLE);
    }

    public void select(Control c) {
        if (selected == c) return;
        selected = c;
        if (editListener != null) editListener.onSelectionChanged();
        invalidate();
    }

    public Control getSelected() { return selected; }
    public String selectedName() { return selected != null ? selected.name : null; }
    public float selectedScale() { return selected != null ? selected.scale : 1f; }
    public boolean selectedVisible() { return selected != null && selected.visible; }
    public boolean selectedLocked() { return selected == null || selected.locked; }
    public boolean selectedCustom() { return selected != null && selected.custom; }
    public int selectedMode() { return selected != null ? selected.behaviorMode : MODE_STANDARD; }
    public String selectedLabel() { return selected != null ? selected.label : ""; }
    public String selectedIconFile() { return selected != null && selected.customIconFile != null ? selected.customIconFile : ""; }
    public boolean selectedOnlySkippable() { return selected != null && selected.onlyWhenSkippable; }

    public void setSelectedOnlySkippable(boolean on) {
        if (selected == null || selected.locked) return;
        selected.onlyWhenSkippable = on;
        invalidate();
    }

    public void setSelectedScale(float scale) {
        if (selected == null) return;
        selected.scale = Math.max(SIZE_MIN, Math.min(SIZE_MAX, scale));
        layoutControls();
        invalidate();
    }

    public void setSelectedVisible(boolean visible) {
        if (selected == null || selected.locked) return;
        selected.visible = visible;
        invalidate();
    }

    public void setSelectedMode(int mode) {
        if (selected == null) return;
        selected.behaviorMode = mode;
        invalidate();
    }

    public void setSelectedKey(String stateType, int key) {
        if (selected == null || selected.locked) return;
        switch (stateType) {
            case "press": selected.pressKey = key; break;
            case "long": selected.longPressKey = key; break;
            case "toggle_on": selected.toggleOnKey = key; break;
            case "toggle_off": selected.toggleOffKey = key; break;
        }
    }

    public int getSelectedKey(String stateType) {
        if (selected == null) return NONE;
        switch (stateType) {
            case "press": return selected.pressKey;
            case "long": return selected.longPressKey;
            case "toggle_on": return selected.toggleOnKey;
            case "toggle_off": return selected.toggleOffKey;
            default: return NONE;
        }
    }

    public void setSelectedLabel(String label) {
        if (selected == null || selected.locked) return;
        selected.label = label;
        selected.name = label;
        invalidate();
    }

    public void setSelectedIconFile(String file) {
        if (selected == null) return;
        selected.customIconFile = file;
        reloadControlIcon(selected);
        invalidate();
    }

    public void setStickKnobFile(String file) {
        if (selected == null || selected.type != TYPE_STICK) return;
        selected.customKnobFile = file;
        reloadControlIcon(selected);
        invalidate();
    }

    public String getStickKnobFile() {
        return (selected != null && selected.type == TYPE_STICK && selected.customKnobFile != null)
                ? selected.customKnobFile : "";
    }

    public Control addNewButtonWithId(String id, String label) {
        if (id == null || id.trim().isEmpty() || !isIdAvailable(id)) return null;
        Control c = new Control(id, label, label, TYPE_BUTTON, KeyEvent.KEYCODE_BUTTON_A,
                BR, 120, 120, 1.0f, 28, true, false, true);
        c.setId = sets[activeSet].id;
        reloadControlIcon(c);
        controls.add(c);
        layoutControls();
        select(c);
        save();
        invalidate();
        return c;
    }

    public void removeSelectedControl() {
        if (selected == null || selected.locked || "edit".equals(selected.id)) return;
        if (selected == stick) {
            stick = null;
            sets[activeSet].stick = null;
        }
        releaseAll();
        controls.remove(selected);
        select(null);
        save();
        layoutControls();
        invalidate();
    }

    public float opacity() { return opacity; }
    public void setOpacity(float value) {
        opacity = Math.max(OPACITY_MIN, Math.min(OPACITY_MAX, value));
        invalidate();
    }

    public float lookSpeed() { return lookSpeed; }
    public void setLookSpeed(float value) {
        lookSpeed = Math.max(LOOK_MIN, Math.min(LOOK_MAX, value));
    }

    public boolean haptics() { return haptics; }
    public void setHaptics(boolean on) { haptics = on; }

    public boolean areControlsEnabled() { return controlsEnabled; }
    public void setControlsEnabled(boolean enabled) {
        controlsEnabled = enabled;
        if (!enabled) releaseAll();
        save();
        invalidate();
    }

    /** one set's controls back to the defaults */
    public void resetSet(int index) {
        if (index < 0 || index >= SET_COUNT) return;
        releaseAll();
        select(null);
        buildDefaults(sets[index]);
        pointAt(activeSet);
        refreshAllDrawables();
        layoutControls();
        if (editListener != null) editListener.onSelectionChanged();
        save();
        invalidate();
    }

    /** every set's controls and the settings back to the defaults */
    public void resetLayout() {
        releaseAll();
        select(null);
        setupAllDefaults();
        pointAt(activeSet);
        opacity = DEFAULT_OPACITY;
        lookSpeed = 1f;
        haptics = true;
        controlsEnabled = true;
        refreshAllDrawables();
        layoutControls();
        if (editListener != null) editListener.onSelectionChanged();
        save();
        invalidate();
    }

    // ---------- INI Storage with Documentation Header ----------

    private File getConfigFile() {
        return new File(getExternalFilesDirectory(), "android_controls.ini");
    }

    public void save() {
        File file = getConfigFile();
        try (PrintWriter writer = new PrintWriter(new FileWriter(file))) {
            writer.println("# ==========================================================================");
            writer.println("# Halo Touch Controls Configuration");
            writer.println("# ==========================================================================");
            writer.println("# The game tells the app what it is doing, and the app shows the set of");
            writer.println("# controls for it. Each set has its own buttons, layout, keys and icons:");
            writer.println("#");
            writer.println("#   [game]      playing");
            writer.println("#   [menu]      a menu is up (main menu, pause menu, dialogs, keyboard)");
            writer.println("#   [cutscene]  a cutscene is playing");
            writer.println("#   (nothing is shown while a map loads)");
            writer.println("#");
            writer.println("# Configuration Options Reference:");
            writer.println("#");
            writer.println("# [General]");
            writer.println("#   version          : File format version (2: one section per set).");
            writer.println("#   opacity          : Float (0.25 to 1.0). Master transparency of controls.");
            writer.println("#   look             : Float (0.5 to 2.0). Camera look sensitivity multiplier.");
            writer.println("#   haptics          : Boolean (true/false). Vibrates on touch interaction.");
            writer.println("#   controls_enabled : Boolean (true/false). Master toggle for touch overlay.");
            writer.println("#");
            writer.println("# [game] [menu] [cutscene]");
            writer.println("#   custom_ids       : Comma-separated list of the set's custom button IDs.");
            writer.println("#   active_controls  : Comma-separated list of the set's controls.");
            writer.println("#");
            writer.println("# [set.control_id]   (for example [menu.ok])");
            writer.println("#   dx, dy           : Float (dp). Distance offsets relative to anchor corners.");
            writer.println("#   scale            : Float (0.6 to 2.0). Size scaling factor.");
            writer.println("#   visible          : Boolean (true/false). Control visibility.");
            writer.println("#   only_when_skippable : Boolean. Shown only while the cutscene can be skipped.");
            writer.println("#   mode             : Interaction behavior mode:");
            writer.println("#                        0 = MODE_STANDARD (fires press_key on touch down/up)");
            writer.println("#                        1 = MODE_TAP_AND_HOLD (tap triggers press_key, hold triggers long_key)");
            writer.println("#                        2 = MODE_TOGGLE (touch toggles between toggle_on_key and toggle_off_key)");
            writer.println("#   press_key        : Android KeyEvent code (-1 = Fire/Left-Click, -2 = Edit, 0 = None).");
            writer.println("#   long_key         : Key code triggered on hold (mode 1 only).");
            writer.println("#   toggle_on_key    : Key code triggered when toggled active (mode 2 only).");
            writer.println("#   toggle_off_key   : Key code triggered when toggled inactive (mode 2 only).");
            writer.println("#   label            : String text rendered on button plate if no icon is specified.");
            writer.println("#   icon_file        : Custom image filename in files/ or files/icons/ (or stick base).");
            writer.println("#   knob_file        : Custom stick knob image in files/ or files/icons/ ([game.stick] only).");
            writer.println("#");
            writer.println("# Icons: without icon_file, a control looks for files/[set]_[id].png (for");
            writer.println("# example menu_ok.png), then files/[id].png, then the app's drawables");
            writer.println("# tc_ic_[set]_[id] and tc_ic_[id]. Without one it shows its label.");
            writer.println("#");
            writer.println("# Keys sent by the buttons: W A S D move, arrows (DPAD_*) the D-pad, Space/Enter");
            writer.println("# A, F B, E X, Tab Y, Q white, X black, C left stick click, Z right stick");
            writer.println("# click, G left trigger, Esc start, F1 back, -1 right trigger.");
            writer.println("# ==========================================================================\n");

            writer.println("[General]");
            writer.println("version=" + FILE_VERSION);
            writer.println("opacity=" + opacity);
            writer.println("look=" + lookSpeed);
            writer.println("haptics=" + haptics);
            writer.println("controls_enabled=" + controlsEnabled);
            writer.println();

            for (ControlSet set : sets) {
                StringBuilder customIds = new StringBuilder();
                StringBuilder activeIds = new StringBuilder();
                for (Control c : set.controls) {
                    if (c.custom) {
                        if (customIds.length() > 0) customIds.append(",");
                        customIds.append(c.id);
                    }
                    if (activeIds.length() > 0) activeIds.append(",");
                    activeIds.append(c.id);
                }
                writer.println("[" + set.id + "]");
                writer.println("custom_ids=" + customIds);
                writer.println("active_controls=" + activeIds);
                writer.println();

                for (Control c : set.controls) {
                    writer.println("[" + set.id + "." + c.id + "]");
                    writer.println("dx=" + c.dx);
                    writer.println("dy=" + c.dy);
                    writer.println("scale=" + c.scale);
                    writer.println("visible=" + c.visible);
                    writer.println("only_when_skippable=" + c.onlyWhenSkippable);
                    writer.println("mode=" + c.behaviorMode);
                    writer.println("press_key=" + c.pressKey);
                    writer.println("long_key=" + c.longPressKey);
                    writer.println("toggle_on_key=" + c.toggleOnKey);
                    writer.println("toggle_off_key=" + c.toggleOffKey);
                    writer.println("label=" + c.label);
                    writer.println("icon_file=" + (c.customIconFile != null ? c.customIconFile : ""));
                    if (c.type == TYPE_STICK) {
                        writer.println("knob_file=" + (c.customKnobFile != null ? c.customKnobFile : ""));
                    }
                    writer.println();
                }
            }
        } catch (Exception ignored) {}
    }

    private static float parseFloat(String value, float fallback) {
        try {
            return value != null ? Float.parseFloat(value.trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value != null ? Integer.parseInt(value.trim()) : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean parseBoolean(String value, boolean fallback) {
        return value != null ? Boolean.parseBoolean(value.trim()) : fallback;
    }

    private void load() {
        File file = getConfigFile();
        if (!file.exists()) return;

        Map<String, Map<String, String>> ini = new HashMap<>();
        String currentSection = "";

        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1);
                    ini.put(currentSection, new HashMap<>());
                } else if (!currentSection.isEmpty() && line.contains("=")) {
                    int eq = line.indexOf('=');
                    String key = line.substring(0, eq).trim();
                    String val = line.substring(eq + 1).trim();
                    ini.get(currentSection).put(key, val);
                }
            }
        } catch (Exception e) {
            return;
        }

        Map<String, String> gen = ini.get("General");
        int version = 1;

        if (gen != null) {
            version = parseInt(gen.get("version"), 1);
            opacity = Math.max(OPACITY_MIN, Math.min(OPACITY_MAX, parseFloat(gen.get("opacity"), opacity)));
            lookSpeed = Math.max(LOOK_MIN, Math.min(LOOK_MAX, parseFloat(gen.get("look"), lookSpeed)));
            haptics = parseBoolean(gen.get("haptics"), haptics);
            controlsEnabled = parseBoolean(gen.get("controls_enabled"), controlsEnabled);
        }

        if (version < 2) {
            // a file from before the sets: its controls are the game's (the
            // sections are named by id, the lists are in [General])
            loadSet(sets[SET_GAME], ini, "", gen);
        } else {
            for (ControlSet set : sets) {
                loadSet(set, ini, set.id + ".", ini.get(set.id));
            }
        }
    }

    /** one set from the file: prefix names its control sections, lists is
     *  the section with its custom_ids and active_controls */
    private void loadSet(ControlSet set, Map<String, Map<String, String>> ini, String prefix,
                         Map<String, String> lists) {
        Set<String> activeIdsSet = null;

        if (lists != null) {
            String active = lists.get("active_controls");
            if (active != null && !active.isEmpty()) {
                activeIdsSet = new HashSet<>();
                for (String aid : active.split(",")) {
                    if (!aid.trim().isEmpty()) activeIdsSet.add(aid.trim());
                }
                activeIdsSet.add("edit");
            }

            String customIds = lists.get("custom_ids");
            if (customIds != null && !customIds.isEmpty()) {
                for (String cid : customIds.split(",")) {
                    cid = cid.trim();
                    if (cid.matches("^[a-z0-9_]+$") && find(set, cid) == null) {
                        Control c = new Control(cid, "Button", "BTN", TYPE_BUTTON, KeyEvent.KEYCODE_UNKNOWN,
                                BR, 120, 120, 1.0f, 28, true, false, true);
                        c.setId = set.id;
                        set.controls.add(c);
                    }
                }
            }
        }

        if (activeIdsSet != null) {
            List<Control> toRemove = new ArrayList<>();
            for (Control c : set.controls) {
                if (!activeIdsSet.contains(c.id)) {
                    toRemove.add(c);
                }
            }
            set.controls.removeAll(toRemove);
        }
        set.stick = find(set, "stick");
        set.editButton = find(set, "edit");

        for (Control c : set.controls) {
            Map<String, String> sec = ini.get(prefix + c.id);
            if (sec == null) continue;
            c.dx = parseFloat(sec.get("dx"), c.dx);
            c.dy = parseFloat(sec.get("dy"), c.dy);
            c.scale = Math.max(SIZE_MIN, Math.min(SIZE_MAX, parseFloat(sec.get("scale"), c.scale)));
            c.visible = c.locked || parseBoolean(sec.get("visible"), c.visible);
            c.onlyWhenSkippable = parseBoolean(sec.get("only_when_skippable"), c.onlyWhenSkippable);
            c.behaviorMode = parseInt(sec.get("mode"), c.behaviorMode);
            c.pressKey = parseInt(sec.get("press_key"), c.pressKey);
            c.longPressKey = parseInt(sec.get("long_key"), c.longPressKey);
            c.toggleOnKey = parseInt(sec.get("toggle_on_key"), c.toggleOnKey);
            c.toggleOffKey = parseInt(sec.get("toggle_off_key"), c.toggleOffKey);
            if (sec.containsKey("label")) {
                c.label = sec.get("label");
                if (c.custom) c.name = c.label;
            }
            if (sec.containsKey("icon_file")) {
                c.customIconFile = sec.get("icon_file");
            }
            if (c.type == TYPE_STICK && sec.containsKey("knob_file")) {
                c.customKnobFile = sec.get("knob_file");
            }
        }
    }

    // ---------- what the game is doing

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(pollState);
        post(pollState);
    }

    private void applyState(int flags) {
        int next = GameState.contextOf(flags);
        boolean skip = (flags & GameState.SKIPPABLE) != 0;

        if (next == situation && skip == skippable)
            return;
        situation = next;
        skippable = skip;
        // a map loading: let go of everything, there is nothing to touch
        if (next == GameState.NONE)
            releaseAll();
        syncSet();
        invalidate();
    }

    /** the set the game's state calls for */
    private int setForSituation() {
        switch (situation) {
            case GameState.MENUS:    return SET_MENU;
            case GameState.CUTSCENE: return SET_CUTSCENE;
            case GameState.GAMEPLAY: return SET_GAME;
            default:                 return activeSet;   // loading: as it was
        }
    }

    /** puts the right set in use: the editor's, or the game's state's */
    private void syncSet() {
        int want = editMode ? editSet : setForSituation();

        if (want != activeSet)
            useSet(want);
    }

    /** another set in use: the old one's inputs are let go of, the new one
     *  fades in */
    private void useSet(int index) {
        releaseAll();
        pointAt(index);
        uiAlpha = 0f;
        layoutControls();
        invalidate();
    }

    /** a control that shows in play: not hidden, and not a SKIP that has
     *  nothing to skip */
    private boolean shown(Control c) {
        return c.visible && (!c.onlyWhenSkippable || skippable);
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        DisplayCutout cutout = insets.getDisplayCutout();
        padLeft = cutout != null ? cutout.getSafeInsetLeft() : 0;
        padRight = cutout != null ? cutout.getSafeInsetRight() : 0;
        layoutControls();
        invalidate();
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        fit = Math.max(0.7f, Math.min(1f, (h / density) / 360f));
        layoutControls();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (Build.VERSION.SDK_INT >= 29 && changed) {
            gestureExclusion.clear();
            gestureExclusion.add(new Rect(0, 0, r - l, b - t));
            setSystemGestureExclusionRects(gestureExclusion);
        }
    }

    private boolean isLeft(Control c) { return c.anchor == TL || c.anchor == BL; }
    private boolean isTop(Control c) { return c.anchor == TL || c.anchor == TR; }

    private void layoutControls() {
        float unit = density * fit;
        float left = padLeft, right = getWidth() - padRight;
        float height = getHeight();

        for (Control c : controls) {
            c.r = c.radius * c.scale * unit;
            float x = isLeft(c) ? left + c.dx * unit : right - c.dx * unit;
            float y = isTop(c) ? c.dy * unit : height - c.dy * unit;
            c.cx = Math.max(left + c.r, Math.min(right - c.r, x));
            c.cy = Math.max(c.r, Math.min(height - c.r, y));
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (e.getToolType(e.getActionIndex()) == MotionEvent.TOOL_TYPE_MOUSE)
            return false;
        if (editMode)
            return onTouchEdit(e);

        if (!controlsEnabled) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                Control c = hit(e.getX(), e.getY(), false);
                if (c != null && c.pressKey == EDIT) {
                    pointerDown(e.getPointerId(0), e.getX(), e.getY(), e.getEventTime());
                    return true;
                }
            } else if (editHoldStart != 0) {
                if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    pointerUp(e.getPointerId(0));
                }
                return true;
            }
            return false;
        }

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                pointerDown(e.getPointerId(i), e.getX(i), e.getY(i), e.getEventTime());
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++)
                    pointerMove(e.getPointerId(i), e.getX(i), e.getY(i), e.getEventTime());
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP:
                pointerUp(e.getPointerId(e.getActionIndex()));
                break;
            case MotionEvent.ACTION_CANCEL:
                releaseAll();
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void pointerDown(int id, float x, float y, long time) {
        if (id >= MAX_POINTERS) return;
        // a set that has not faded in has nothing to press
        if (!editMode && uiAlpha < 0.5f) return;
        lastX[id] = x;
        lastY[id] = y;
        lastTime[id] = time;

        Control c = hit(x, y, false);
        if (c != null) {
            c.pointer = id;
            c.longPressTriggered = false;
            press(c);
        } else if (x < getWidth() * STICK_SIDE) {
            if (stick != null && shown(stick) && stick.pointer < 0)
                beginStick(id, x, y);
        } else if (lookPointer < 0 && sets[activeSet].look) {
            lookPointer = id;
        }
    }

    private void pointerMove(int id, float x, float y, long time) {
        if (id >= MAX_POINTERS) return;
        if (stick != null && id == stick.pointer) {
            moveStick(x, y);
        } else if (id == lookPointer || aimsWithButton(id)) {
            look(x - lastX[id], y - lastY[id], time - lastTime[id]);
            lastX[id] = x;
            lastY[id] = y;
            lastTime[id] = time;
        }
    }

    private void pointerUp(int id) {
        if (id == lookPointer) lookPointer = -1;
        for (Control c : controls) {
            if (c.pointer == id) {
                c.pointer = -1;
                release(c);
            }
        }
    }

    private boolean aimsWithButton(int id) {
        for (Control c : controls) {
            if (c.pointer == id && (c.pressKey == FIRE || c.longPressKey == FIRE)) return true;
        }
        return false;
    }

    private Control hit(float x, float y, boolean forEditor) {
        float slop = HIT_SLOP_DP * density;
        Control best = null;
        float bestScore = Float.MAX_VALUE;

        for (Control c : controls) {
            if (!forEditor && (c.type == TYPE_STICK || !shown(c) || (c.pointer >= 0 && c.behaviorMode != MODE_TOGGLE)))
                continue;
            float d = (float) Math.hypot(x - c.cx, y - c.cy);
            if (d > c.r + slop) continue;
            float score = d / c.r;
            if (score < bestScore) {
                bestScore = score;
                best = c;
            }
        }
        return best;
    }

    private void tick() {
        if (haptics) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
    }

    private void press(Control c) {
        if (c.pressKey == EDIT) {
            editHoldStart = SystemClock.uptimeMillis();
            postDelayed(openEditor, EDIT_HOLD_MS);
            return;
        }

        if (c.behaviorMode == MODE_TOGGLE) {
            c.toggledState = !c.toggledState;
            if (c.toggledState) {
                sendKeyEvent(c.toggleOffKey, false);
                sendKeyEvent(c.toggleOnKey, true);
            } else {
                sendKeyEvent(c.toggleOnKey, false);
                sendKeyEvent(c.toggleOffKey, true);
            }
            tick();
            return;
        }

        if (c.behaviorMode == MODE_TAP_AND_HOLD) {
            c.longPressTriggered = false;
            c.pendingLongPress = () -> {
                c.longPressTriggered = true;
                sendKeyEvent(c.longPressKey, true);
                tick();
            };
            mainHandler.postDelayed(c.pendingLongPress, LONG_PRESS_TIMEOUT_MS);
            return;
        }

        flushRelease(c);
        c.downAt = SystemClock.uptimeMillis();
        sendKeyEvent(c.pressKey, true);
        tick();
    }

    /** a delayed key-up that has not happened yet, now */
    private void flushRelease(Control c) {
        if (c.pendingRelease != null) {
            mainHandler.removeCallbacks(c.pendingRelease);
            Runnable pending = c.pendingRelease;
            c.pendingRelease = null;
            pending.run();
        }
    }

    private void release(Control c) {
        if (c.type == TYPE_STICK) {
            endStick();
            return;
        }
        if (c.pressKey == EDIT) {
            removeCallbacks(openEditor);
            editHoldStart = 0;
            return;
        }
        if (c.behaviorMode == MODE_TOGGLE) {
            return;
        }

        if (c.behaviorMode == MODE_TAP_AND_HOLD) {
            if (c.pendingLongPress != null) {
                mainHandler.removeCallbacks(c.pendingLongPress);
                c.pendingLongPress = null;
            }
            if (c.longPressTriggered) {
                sendKeyEvent(c.longPressKey, false);
                c.longPressTriggered = false;
            } else {
                sendKeyEvent(c.pressKey, true);
                tick();
                mainHandler.postDelayed(() -> sendKeyEvent(c.pressKey, false), 50);
            }
            return;
        }

        // held at least MIN_HOLD_MS, so that the game sees the tap
        long held = SystemClock.uptimeMillis() - c.downAt;
        final int key = c.pressKey;

        if (held >= MIN_HOLD_MS) {
            sendKeyEvent(key, false);
        } else {
            c.pendingRelease = new Runnable() {
                @Override
                public void run() {
                    c.pendingRelease = null;
                    sendKeyEvent(key, false);
                }
            };
            mainHandler.postDelayed(c.pendingRelease, MIN_HOLD_MS - held);
        }
    }

    private void sendKeyEvent(int key, boolean down) {
        if (key == NONE || key == EDIT) return;

        if (key == FIRE) {
            if (down) {
                SDLActivity.onNativeMouse(MotionEvent.BUTTON_PRIMARY, MotionEvent.ACTION_DOWN, 0f, 0f, true);
            } else {
                for (Control other : controls) {
                    if (other.pressKey == FIRE || other.longPressKey == FIRE || other.toggleOnKey == FIRE) {
                        if (other.pointer >= 0 || (other.behaviorMode == MODE_TOGGLE && other.toggledState))
                            return;
                    }
                }
                SDLActivity.onNativeMouse(0, MotionEvent.ACTION_UP, 0f, 0f, true);
            }
        } else if (key > 0) {
            if (down) SDLActivity.onNativeKeyDown(key);
            else SDLActivity.onNativeKeyUp(key);
        }
    }

    private void look(float dxPx, float dyPx, long dtMs) {
        if (dxPx == 0f && dyPx == 0f) return;
        float dx = dxPx / density, dy = dyPx / density;
        float speed = (float) Math.hypot(dx, dy) / Math.max(1L, dtMs);
        float fast = Math.min(1f, speed / LOOK_FAST_DP_PER_MS);
        float gain = LOOK_BASE * lookSpeed * (1f + LOOK_ACCEL * fast);
        SDLActivity.onNativeMouse(0, MotionEvent.ACTION_MOVE, dx * gain, dy * gain, true);
    }

    private void beginStick(int id, float x, float y) {
        float r = stick.r;
        stick.pointer = id;
        stickX = Math.max(padLeft + r, Math.min(getWidth() - padRight - r, x));
        stickY = Math.max(r, Math.min(getHeight() - r, y));
        knobX = 0f;
        knobY = 0f;
        tick();
        updateStick();
    }

    private void moveStick(float x, float y) {
        float r = stick.r;
        float dx = x - stickX;
        float dy = y - stickY;
        float dist = (float) Math.hypot(dx, dy);

        if (dist > r) {
            float ratio = r / dist;
            knobX = dx * ratio;
            knobY = dy * ratio;
        } else {
            knobX = dx;
            knobY = dy;
        }
        updateStick();
    }

    private void endStick() {
        if (stick != null) stick.pointer = -1;
        knobX = 0f;
        knobY = 0f;
        updateStick();
    }

    private void updateStick() {
        if (stick == null) return;
        float t = stick.r * STICK_DEAD_ZONE;
        float ax = Math.abs(knobX), ay = Math.abs(knobY);
        boolean[] want = stickWant;

        want[0] = -knobY > t && -knobY > ax * STICK_DIAGONAL; // W
        want[1] = -knobX > t && -knobX > ay * STICK_DIAGONAL; // A
        want[2] = knobY > t && knobY > ax * STICK_DIAGONAL;   // S
        want[3] = knobX > t && knobX > ay * STICK_DIAGONAL;   // D
        for (int i = 0; i < 4; i++) {
            if (want[i] != stickHeld[i]) {
                stickHeld[i] = want[i];
                if (want[i]) SDLActivity.onNativeKeyDown(STICK_KEYS[i]);
                else SDLActivity.onNativeKeyUp(STICK_KEYS[i]);
            }
        }
    }

    private void releaseAll() {
        for (Control c : controls) {
            flushRelease(c);
            if (c.pendingLongPress != null) {
                mainHandler.removeCallbacks(c.pendingLongPress);
                c.pendingLongPress = null;
            }
            if (c.pointer >= 0) {
                c.pointer = -1;
                release(c);
            }
            if (c.behaviorMode == MODE_TOGGLE && c.toggledState) {
                c.toggledState = false;
                sendKeyEvent(c.toggleOnKey, false);
                sendKeyEvent(c.toggleOffKey, false);
            }
        }
        lookPointer = -1;
        if (stick != null) endStick();
        invalidate();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        super.onWindowFocusChanged(hasWindowFocus);
        if (!hasWindowFocus) releaseAll();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(pollState);
        releaseAll();
        super.onDetachedFromWindow();
    }

    private boolean onTouchEdit(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX(), y = e.getY();
                dragging = hit(x, y, true);
                if (dragging != null) {
                    grabDx = dragging.cx - x;
                    grabDy = dragging.cy - y;
                }
                select(dragging);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                if (dragging != null)
                    moveControl(dragging, e.getX() + grabDx, e.getY() + grabDy);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging != null) save();
                dragging = null;
                break;
            default:
                break;
        }
        invalidate();
        return true;
    }

    private void moveControl(Control c, float x, float y) {
        float unit = density * fit;
        float left = padLeft, right = getWidth() - padRight;
        float height = getHeight();
        float nx = Math.max(left + c.r, Math.min(right - c.r, x));
        float ny = Math.max(c.r, Math.min(height - c.r, y));

        c.dx = snap((isLeft(c) ? nx - left : right - nx) / unit);
        c.dy = snap((isTop(c) ? ny : height - ny) / unit);
        layoutControls();
    }

    private static float snap(float dp) {
        return Math.round(dp / SNAP_DP) * SNAP_DP;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float step = (lastFrame == 0 ? 16f : Math.min(50f, now - lastFrame)) / PRESS_MS;
        boolean animating = editHoldStart != 0;
        lastFrame = now;

        // a set fades in when it comes up; nothing shows while a map loads
        float fade = (lastFade == 0 ? 16f : Math.min(50f, now - lastFade)) / FADE_MS;
        float uiTarget = (editMode || situation != GameState.NONE) ? 1f : 0f;

        lastFade = now;
        uiAlpha = approach(uiAlpha, uiTarget, fade);
        if (uiAlpha != uiTarget) animating = true;

        if (editMode) {
            canvas.drawColor(SCRIM);
        } else if (!controlsEnabled) {
            if (editButton != null && editButton.visible) {
                drawButton(canvas, editButton);
            }
            if (editHoldStart != 0) drawEditHold(canvas, now);
            if (animating) postInvalidateOnAnimation();
            else lastFrame = lastFade = 0;
            return;
        }

        if (stick != null && (shown(stick) || editMode)) {
            if (drawStick(canvas, step)) animating = true;
        }

        for (Control c : controls) {
            if (c.type == TYPE_STICK || (!shown(c) && !editMode)) continue;
            boolean active = (c.pointer >= 0) || c.toggledState || c.longPressTriggered;
            float target = active ? 1f : 0f;
            c.press = approach(c.press, target, step);
            if (c.press != target) animating = true;
            drawButton(canvas, c);
        }

        if (editMode) drawEditorOutlines(canvas);
        else if (editHoldStart != 0) drawEditHold(canvas, now);

        if (animating) postInvalidateOnAnimation();
        else lastFrame = lastFade = 0;
    }

    private static float approach(float value, float target, float step) {
        if (value < target) return Math.min(target, value + step);
        return Math.max(target, value - step);
    }

    private boolean drawStick(Canvas canvas, float step) {
        boolean held = stick.pointer >= 0;
        float target = held ? 1f : 0f;
        float cx = held ? stickX : stick.cx;
        float cy = held ? stickY : stick.cy;
        float r = stick.r;

        stickGlow = approach(stickGlow, target, step);
        float alpha = (editMode ? (stick.visible ? 0.9f : 0.35f) : opacity * (0.45f + 0.55f * stickGlow)) * uiAlpha;

        if (stickBase != null) {
            stickBase.setBounds((int) (cx - r), (int) (cy - r), (int) (cx + r), (int) (cy + r));
            stickBase.setAlpha((int) (alpha * 255f));
            stickBase.draw(canvas);
        } else {
            drawContainerDisc(canvas, cx, cy, r, alpha, held);
        }

        float kr = r * 0.46f;
        float kx = cx + knobX, ky = cy + knobY;
        if (knob != null) {
            knob.setBounds((int) (kx - kr), (int) (ky - kr), (int) (kx + kr), (int) (ky + kr));
            knob.setAlpha((int) (Math.min(1f, alpha + 0.2f) * 255f));
            knob.draw(canvas);
        }

        // Only draw the "MOVE" label text if no drawables are set for the stick
        if (stickBase == null && knob == null) {
            drawLabel(canvas, stick.label != null ? stick.label : "", cx, cy, kr * 1.4f, alpha, false);
        }

        return stickGlow != target;
    }

    private void drawButton(Canvas canvas, Control c) {
        float scale = 1f - 0.10f * c.press;
        float r = c.r * scale;
        float alpha = (editMode ? (c.visible ? 0.9f : 0.35f)
                                : opacity * (0.8f + 0.2f * c.press)) * uiAlpha;

        if (c.icon != null) {
            c.icon.setBounds((int) (c.cx - r), (int) (c.cy - r), (int) (c.cx + r), (int) (c.cy + r));
            c.icon.setState(c.pointer >= 0 || c.toggledState ? STATE_PRESSED : STATE_IDLE);
            c.icon.setAlpha((int) (alpha * 255f));
            c.icon.draw(canvas);
        } else {
            drawContainerDisc(canvas, c.cx, c.cy, r, alpha, c.toggledState || c.press > 0.5f);
            drawLabel(canvas, c.label, c.cx, c.cy, r, alpha, c.toggledState);
        }
    }

    private void drawContainerDisc(Canvas canvas, float cx, float cy, float r, float alpha, boolean pressed) {
        outlinePaint.setPathEffect(null);
        outlinePaint.setStyle(Paint.Style.FILL);
        int fillColor = pressed ? ((int) (alpha * 0x88) << 24 | (ACCENT & 0x00FFFFFF))
                                : ((int) (alpha * 0x44) << 24 | 0x141E28);
        outlinePaint.setColor(fillColor);
        canvas.drawCircle(cx, cy, r, outlinePaint);

        outlinePaint.setStyle(Paint.Style.STROKE);
        outlinePaint.setStrokeWidth(2f * density);
        int strokeColor = pressed ? ACCENT : ((int) (alpha * 0x99) << 24 | 0xFFFFFF);
        outlinePaint.setColor(strokeColor);
        canvas.drawCircle(cx, cy, r, outlinePaint);
    }

    private void drawLabel(Canvas canvas, String text, float cx, float cy, float r, float alpha, boolean highlighted) {
        float size = r * 0.52f;
        labelPaint.setTextSize(size);
        float width = labelPaint.measureText(text);
        float room = r * 1.7f;
        if (width > room) labelPaint.setTextSize(size * room / width);

        labelPaint.setColor(highlighted ? ACCENT : 0xFFFFFFFF);
        labelPaint.setAlpha((int) (alpha * 255f));
        canvas.drawText(text, cx, cy - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint);
    }

    private void drawEditorOutlines(Canvas canvas) {
        for (Control c : controls) {
            boolean sel = (c == selected);
            outlinePaint.setStyle(Paint.Style.STROKE);
            outlinePaint.setStrokeWidth((sel ? 3f : 1.5f) * density);
            outlinePaint.setColor(sel ? ACCENT : 0x80FFFFFF);
            outlinePaint.setPathEffect(sel ? null : dashes);
            canvas.drawCircle(c.cx, c.cy, c.r + 4f * density, outlinePaint);
        }
        outlinePaint.setPathEffect(null);
    }

    private void drawEditHold(Canvas canvas, long now) {
        if (editButton == null) return;
        float progress = Math.min(1f, (now - editHoldStart) / (float) EDIT_HOLD_MS);
        float r = editButton.r + 4f * density;
        oval.set(editButton.cx - r, editButton.cy - r, editButton.cx + r, editButton.cy + r);
        canvas.drawArc(oval, -90f, 360f * progress, false, holdPaint);
    }
}
