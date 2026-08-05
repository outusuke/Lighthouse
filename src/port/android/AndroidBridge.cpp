/**
 * JNI surface for the Android app.
 *
 * Three unrelated things live here because they are the only places the Kotlin
 * side reaches into native code:
 *
 *  - the on-screen controller, fed into SDL as a virtual joystick,
 *  - whether the engine's menu is up, which decides when the overlay stands
 *    down and the Mods button appears, and
 *  - game asset generation, which runs Torch against the player's ROM.
 *
 * The asset generation entry point is deliberately independent of libultraship:
 * it is called from the launcher activity, before SDL exists, so it cannot use
 * Ship::Context to discover paths. The launcher passes them in explicitly.
 */
#ifdef __ANDROID__

#include <jni.h>
#include <android/log.h>

#include <SDL2/SDL.h>
#include <SDL2/SDL_gamecontroller.h>

#include <atomic>
#include <exception>
#include <filesystem>
#include <string>

#include "port/Extractor/GameExtractor.h"
#include "ship/Context.h"
#include "ship/window/Window.h"
#include "ship/window/gui/Gui.h"

namespace {

constexpr const char* kLogTag = "Lighthouse";

std::string ToStdString(JNIEnv* env, jstring value) {
    if (value == nullptr) {
        return {};
    }
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars != nullptr ? chars : "";
    if (chars != nullptr) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

// The on-screen controller is exposed to the game as an ordinary SDL game
// controller so it flows through libultraship's normal input mapping, which
// means it can be remapped in-game like any physical pad.
SDL_Joystick* gVirtualJoystick = nullptr;
int gVirtualJoystickId = -1;

} // namespace

extern "C" {

JNIEXPORT void JNICALL Java_com_lighthouse_android_MainActivity_attachController(JNIEnv*, jobject) {
    if (gVirtualJoystick != nullptr) {
        return;
    }

    gVirtualJoystickId = SDL_JoystickAttachVirtual(SDL_JOYSTICK_TYPE_GAMECONTROLLER, 6, 18, 0);
    if (gVirtualJoystickId == -1) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Could not attach the on-screen controller: %s",
                            SDL_GetError());
        return;
    }

    gVirtualJoystick = SDL_JoystickOpen(gVirtualJoystickId);
    if (gVirtualJoystick == nullptr) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Could not open the on-screen controller: %s", SDL_GetError());
        SDL_JoystickDetachVirtual(gVirtualJoystickId);
        gVirtualJoystickId = -1;
    }
}

JNIEXPORT void JNICALL Java_com_lighthouse_android_MainActivity_detachController(JNIEnv*, jobject) {
    if (gVirtualJoystick == nullptr) {
        return;
    }

    SDL_JoystickClose(gVirtualJoystick);
    SDL_JoystickDetachVirtual(gVirtualJoystickId);
    gVirtualJoystick = nullptr;
    gVirtualJoystickId = -1;
}

JNIEXPORT void JNICALL Java_com_lighthouse_android_MainActivity_setButton(JNIEnv*, jobject, jint button,
                                                                         jboolean value) {
    if (gVirtualJoystick == nullptr) {
        return;
    }

    // Negative ids address an axis instead, so Z and the shoulder triggers can
    // be driven by a plain on/off button in the overlay.
    if (button < 0) {
        SDL_JoystickSetVirtualAxis(gVirtualJoystick, -button, value ? SDL_MAX_SINT16 : -SDL_MAX_SINT16);
    } else {
        SDL_JoystickSetVirtualButton(gVirtualJoystick, button, value);
    }
}

JNIEXPORT void JNICALL Java_com_lighthouse_android_MainActivity_setAxis(JNIEnv*, jobject, jint axis, jshort value) {
    if (gVirtualJoystick == nullptr) {
        return;
    }

    SDL_JoystickSetVirtualAxis(gVirtualJoystick, axis, value);
}

/**
 * Whether libultraship's menu is currently up.
 *
 * The overlay needs this because the menu is not only opened by its own Menu
 * button — a keyboard, a gamepad, or the menu closing itself all change it
 * behind the overlay's back. Reading the engine's state instead of mirroring it
 * in Kotlin is what keeps the two from drifting apart.
 *
 * Called from the UI thread while the game thread renders. It reads a bool the
 * game thread may be writing, which is worth accepting here: the value only
 * drives which overlay buttons are shown, and a torn read self-corrects on the
 * next poll.
 */
JNIEXPORT jboolean JNICALL Java_com_lighthouse_android_MainActivity_isMenuOpen(JNIEnv*, jobject) {
    // Returns null rather than creating anything when the engine has not
    // started yet, which is exactly what the first few polls hit.
    auto* context = Ship::Context::GetRawInstance();
    if (context == nullptr) {
        return JNI_FALSE;
    }

    auto window = context->GetWindow();
    if (window == nullptr) {
        return JNI_FALSE;
    }

    auto gui = window->GetGui();
    if (gui == nullptr) {
        return JNI_FALSE;
    }

    return gui->GetMenuOrMenubarVisible() ? JNI_TRUE : JNI_FALSE;
}

/**
 * Runs Torch over the ROM at romPath and writes bk.o2r into destDir.
 *
 * sourceDir is the directory holding config.yml and assets/ (unpacked from the
 * APK by the launcher). Returns null on success, or a message describing what
 * went wrong.
 */
JNIEXPORT jstring JNICALL Java_com_lighthouse_android_GameAssets_nativeGenerateGameArchive(JNIEnv* env, jobject,
                                                                                          jstring jRomPath,
                                                                                          jstring jSourceDir,
                                                                                          jstring jDestDir) {
    const std::string romPath = ToStdString(env, jRomPath);
    const std::string sourceDir = ToStdString(env, jSourceDir);
    const std::string destDir = ToStdString(env, jDestDir);

    const auto fail = [env](const std::string& message) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Asset generation failed: %s", message.c_str());
        return env->NewStringUTF(message.c_str());
    };

    GameExtractor extractor;
    // Nothing here runs the ImGui loop that answers a custom-code prompt, so a
    // romhack that raised one would hang the launcher forever.
    extractor.SetInteractive(false);
    if (!extractor.RunStandalone(romPath)) {
        return fail("Could not read a supported Banjo-Kazooie ROM at " + romPath + ".");
    }

    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Extracting %s into %s", romPath.c_str(), destDir.c_str());

    std::string extractError;
    std::atomic<size_t> assetCount{ 0 };
    std::atomic<size_t> totalAssets{ 0 };
    try {
        if (!extractor.GenerateOTRTo(assetCount, totalAssets, sourceDir, destDir)) {
            extractError = GameExtractor::sLastError.empty() ? "Torch could not extract the ROM."
                                                             : "Torch could not extract the ROM: " +
                                                                   GameExtractor::sLastError;
        }
    } catch (const std::exception& error) {
        extractError = std::string("Torch could not extract the ROM: ") + error.what();
    } catch (...) {
        extractError = "Torch could not extract the ROM.";
    }

    // GenerateOTRTo tears Companion down itself on both the success and the
    // failure path, so there is nothing to free here. What that does not undo is
    // the peak: extraction holds the ROM and every decoded asset at once, and
    // the allocator keeps that arena. The launcher runs in its own process
    // (android:process=":launcher") and ends it once the archive is written, so
    // the OS hands all of it back before the game starts.

    if (!extractError.empty()) {
        return fail(extractError);
    }

    // Init() logs and returns rather than throwing for a few failure modes
    // (missing config, unrecognised ROM), so confirm the archive really landed.
    std::error_code error;
    const std::filesystem::path archive = std::filesystem::path(destDir) / "bk.o2r";
    if (!std::filesystem::exists(archive, error) || std::filesystem::file_size(archive, error) == 0) {
        return fail("Torch finished without producing bk.o2r. Check that the ROM is a supported "
                    "Banjo-Kazooie release.");
    }

    __android_log_print(ANDROID_LOG_INFO, kLogTag, "Wrote %s", archive.c_str());
    return nullptr;
}

} // extern "C"

#endif // __ANDROID__
