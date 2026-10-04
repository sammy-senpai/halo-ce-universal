/*
HOST_STATE.C

What the game is doing (port/linux/include/halo_game_state.h): the guest
reports it each time it changes, and the app's touch controls read it
(GameState.java asks for it about twenty times a second).
*/

#include "host.h"

#include <jni.h>
#include <stdatomic.h>

static _Atomic uint32_t game_state;

/* from the guest's game thread */
void host_set_game_state(uint32_t flags)
{
	atomic_store_explicit(&game_state, flags, memory_order_relaxed);
}

/* from the app's UI thread */
JNIEXPORT jint JNICALL Java_com_halo_decomp_GameState_nativeFlags(JNIEnv *environment, jclass type)
{
	(void)environment;
	(void)type;
	return (jint)atomic_load_explicit(&game_state, memory_order_relaxed);
}
