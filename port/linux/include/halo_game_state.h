/*
HALO_GAME_STATE.H

What the game is doing, for the Android app's touch controls. The widget
update (source/interface/ui_widget.c) works the state out every frame and
passes it on when it changes; the guest runtime hands it to the host
(port/android/guest/runtime/guest_misc.c, port/android/host/host_state.c),
and the app reads it (GameState.java) to show the controls that fit: the
buttons for playing, the menu's few, or the one that skips a cutscene.
Nothing happens on the desktop builds.
*/

#ifndef HALO_GAME_STATE_H
#define HALO_GAME_STATE_H

/* a menu is up for the first player: the pause menu, the main menu, a
dialog; the app's GameState.java has the same values */
#define HALO_GAME_STATE_MENU 0x01u
/* the on-screen keyboard is up */
#define HALO_GAME_STATE_KEYBOARD 0x02u
/* a cutscene is playing (a scripted cinematic) */
#define HALO_GAME_STATE_CINEMATIC 0x04u
/* ... and A skips it */
#define HALO_GAME_STATE_SKIPPABLE 0x08u
/* the game's time is stopped */
#define HALO_GAME_STATE_PAUSED 0x10u
/* a map is loading, or the menus are still starting */
#define HALO_GAME_STATE_LOADING 0x20u
/* a game is running, and it is not the main menu's backdrop */
#define HALO_GAME_STATE_IN_GAME 0x40u

#ifdef HALO_ANDROID
void halo_game_state_update(unsigned int flags);
#else
#define halo_game_state_update(flags) ((void)(flags))
#endif

#endif
