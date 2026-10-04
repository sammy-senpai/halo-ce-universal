package com.halo.decomp;

/**
 * What the game is doing, as the touch controls need it.
 *
 * The game works it out every frame (source/interface/ui_widget.c), the guest
 * hands it to the host when it changes, and host_state.c answers nativeFlags.
 * The flag values are those of port/linux/include/halo_game_state.h.
 *
 * contextOf turns the flags into the one thing the controls are for just now:
 * playing, a menu (the main menu, the pause menu, a dialog, the keyboard),
 * a cutscene, or nothing (a map is loading).
 */
public final class GameState {
    public static final int MENU = 0x01;
    public static final int KEYBOARD = 0x02;
    public static final int CINEMATIC = 0x04;
    public static final int SKIPPABLE = 0x08;
    public static final int PAUSED = 0x10;
    public static final int LOADING = 0x20;
    public static final int IN_GAME = 0x40;

    /** the contexts, as bits: a control lists those it shows in */
    public static final int NONE = 0;
    public static final int GAMEPLAY = 1;
    public static final int MENUS = 2;
    public static final int CUTSCENE = 4;

    private static boolean available = true;

    private GameState() {
    }

    private static native int nativeFlags();

    /**
     * The game's flags. Without the native side (an old libmain.so) the
     * game is taken to be in play, so that the controls all show, as they
     * did before the game told them anything.
     */
    public static int flags() {
        if (!available)
            return IN_GAME;
        try {
            return nativeFlags();
        } catch (UnsatisfiedLinkError e) {
            available = false;
            return IN_GAME;
        }
    }

    public static int contextOf(int flags) {
        if ((flags & LOADING) != 0)
            return NONE;
        // a menu over a cutscene (a dialog) takes the touches
        if ((flags & (MENU | KEYBOARD)) != 0)
            return MENUS;
        if ((flags & CINEMATIC) != 0)
            return CUTSCENE;
        // paused with no menu yet, or no game at all (the title)
        if ((flags & PAUSED) != 0 || (flags & IN_GAME) == 0)
            return MENUS;
        return GAMEPLAY;
    }
}
