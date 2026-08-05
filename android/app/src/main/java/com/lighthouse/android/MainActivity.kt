package com.lighthouse.android

import android.annotation.SuppressLint
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.libsdl.app.SDLActivity
import kotlin.math.sqrt

/**
 * The game itself, plus the on-screen N64 pad drawn over the SDL surface.
 *
 * The overlay drives a virtual SDL joystick (see src/port/android/AndroidBridge.cpp)
 * rather than synthesising input events, so it goes through libultraship's normal
 * controller mapping and can be remapped in-game like any other pad.
 *
 * [LauncherActivity] guarantees bk.o2r exists before this activity starts.
 */
class MainActivity : SDLActivity() {

    private lateinit var preferences: SharedPreferences
    private lateinit var modsButton: Button

    private var controlsEnabled = true
    private var menuOpen = false
    private var controlsHidden = false

    private val handler = Handler(Looper.getMainLooper())
    private val menuWatcher = object : Runnable {
        override fun run() {
            syncMenuState()
            handler.postDelayed(this, MENU_POLL_MS)
        }
    }

    // org/libsdl/app is kept byte-identical to the SDL release the CMake pin
    // fetches, so the game library is named here rather than patched in there.
    // SDLActivity refuses to start if the Java glue and libSDL2.so disagree on
    // their version, so both have to move together.
    override fun getLibraries(): Array<String> = arrayOf("SDL2", "main")

    private external fun attachController()
    private external fun detachController()
    private external fun setButton(button: Int, value: Boolean)
    private external fun setAxis(axis: Int, value: Short)
    private external fun isMenuOpen(): Boolean

    override fun onCreate(savedInstanceState: Bundle?) {
        preferences = getSharedPreferences(PREFS, MODE_PRIVATE)
        super.onCreate(savedInstanceState)
        goFullscreen()
        setupControllerOverlay()
        attachController()
    }

    /**
     * Edge to edge, with the status and navigation bars hidden.
     *
     * From API 35 the system no longer honours the old fullscreen window flags,
     * so the bars have to be dismissed through the insets controller instead —
     * without this the navigation bar sits on top of the game. Transient-by-swipe
     * means a stray swipe shows them briefly rather than resizing the window and
     * forcing the engine to rebuild its framebuffers.
     */
    private fun goFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Render into the camera cutout as well, so the game gets the whole
        // panel instead of being letterboxed beside it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The bars come back whenever focus is lost — to the mods manager, a
        // notification shade pull, anything. Put them away again on the way in.
        if (hasFocus) {
            goFullscreen()
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-sync immediately as well as on the timer, so coming back from the
        // mods manager never leaves the overlay showing a stale menu state.
        syncMenuState()
        handler.removeCallbacks(menuWatcher)
        handler.postDelayed(menuWatcher, MENU_POLL_MS)
    }

    override fun onPause() {
        handler.removeCallbacks(menuWatcher)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(menuWatcher)
        detachController()
        super.onDestroy()
    }

    /**
     * Mirrors the engine's menu state onto the overlay.
     *
     * The menu is not only driven by the on-screen Menu button: a physical
     * keyboard, a gamepad, or the menu dismissing itself all change it without
     * the overlay hearing about it. Tracking it with a local flag drifts out of
     * sync and leaves the controls dead with the Mods button stuck on screen,
     * so the engine is asked instead.
     */
    private fun syncMenuState() {
        val open = runCatching { isMenuOpen() }.getOrDefault(false)
        if (open == menuOpen) return

        menuOpen = open
        controlsEnabled = !open
        // Managing mods is a settings action, so it stays out of reach until
        // the menu is up.
        modsButton.visibility = if (open) View.VISIBLE else View.GONE

        if (open) {
            releaseAllInputs()
        }
    }

    /**
     * Clears anything the overlay was holding down. Without this, opening the
     * menu mid-input leaves that button or stick stuck on in the virtual pad,
     * because the touch listeners stop reporting once controls are disabled.
     */
    private fun releaseAllInputs() {
        for (button in intArrayOf(
            ControllerButtons.BUTTON_A, ControllerButtons.BUTTON_B,
            ControllerButtons.BUTTON_L, ControllerButtons.BUTTON_START,
            ControllerButtons.BUTTON_DPAD_UP, ControllerButtons.BUTTON_DPAD_DOWN,
            ControllerButtons.BUTTON_DPAD_LEFT, ControllerButtons.BUTTON_DPAD_RIGHT,
            ControllerButtons.BUTTON_Z, ControllerButtons.BUTTON_R
        )) {
            setButton(button, false)
        }
        for (axis in intArrayOf(
            ControllerButtons.AXIS_STICK_X, ControllerButtons.AXIS_STICK_Y,
            ControllerButtons.AXIS_C_X, ControllerButtons.AXIS_C_Y
        )) {
            setAxis(axis, 0)
        }
    }

    private fun setupControllerOverlay() {
        val inflater = getSystemService(LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val overlay = inflater.inflate(R.layout.touchcontrol_overlay, null).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        findViewById<ViewGroup>(android.R.id.content).addView(overlay)

        val buttonGroup = overlay.findViewById<ViewGroup>(R.id.button_group)

        overlay.bindButton(R.id.buttonA, ControllerButtons.BUTTON_A)
        overlay.bindButton(R.id.buttonB, ControllerButtons.BUTTON_B)
        overlay.bindButton(R.id.buttonL, ControllerButtons.BUTTON_L)
        overlay.bindButton(R.id.buttonStart, ControllerButtons.BUTTON_START)
        overlay.bindButton(R.id.buttonDpadUp, ControllerButtons.BUTTON_DPAD_UP)
        overlay.bindButton(R.id.buttonDpadDown, ControllerButtons.BUTTON_DPAD_DOWN)
        overlay.bindButton(R.id.buttonDpadLeft, ControllerButtons.BUTTON_DPAD_LEFT)
        overlay.bindButton(R.id.buttonDpadRight, ControllerButtons.BUTTON_DPAD_RIGHT)

        // Z and R are trigger axes on a real pad, so they are pushed to full
        // deflection rather than set as buttons.
        overlay.bindButton(R.id.buttonZ, ControllerButtons.BUTTON_Z)
        overlay.bindButton(R.id.buttonR, ControllerButtons.BUTTON_R)

        // The C buttons sit on the right stick, which is how libultraship maps
        // the N64 pad by default, so the cluster drives that axis.
        overlay.bindAxisButton(R.id.buttonCUp, ControllerButtons.AXIS_C_Y, positive = false)
        overlay.bindAxisButton(R.id.buttonCDown, ControllerButtons.AXIS_C_Y, positive = true)
        overlay.bindAxisButton(R.id.buttonCLeft, ControllerButtons.AXIS_C_X, positive = false)
        overlay.bindAxisButton(R.id.buttonCRight, ControllerButtons.AXIS_C_X, positive = true)

        modsButton = overlay.findViewById(R.id.buttonMods)
        modsButton.setOnClickListener { startActivity(Intent(this, ModsActivity::class.java)) }

        setupMenuButton(overlay.findViewById(R.id.buttonMenu))
        setupJoystick(overlay.findViewById(R.id.left_joystick), overlay.findViewById(R.id.left_joystick_knob))
        setupToggleButton(overlay.findViewById(R.id.buttonToggle), buttonGroup)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.bindButton(id: Int, button: Int) {
        val view = findViewById<Button>(id)
        view.setOnTouchListener { _, event ->
            if (!controlsEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    setButton(button, true)
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setButton(button, false)
                    view.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun View.bindAxisButton(id: Int, axis: Int, positive: Boolean) {
        val view = findViewById<Button>(id)
        val pressed = if (positive) Short.MAX_VALUE else Short.MIN_VALUE
        view.setOnTouchListener { _, event ->
            if (!controlsEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    setAxis(axis, pressed)
                    view.isPressed = true
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    setAxis(axis, 0)
                    view.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Escape opens libultraship's menu. While it is up the overlay stops feeding
     * the virtual pad so taps land on the menu instead of the game.
     *
     * Not F1. libultraship drives two separate things: Escape toggles a "Menu"
     * and F1 toggles a "MenuBar" (see Gui::DrawMenu). Lighthouse installs a menu
     * — LighthouseGui.cpp calls SetMenu() and never SetMenuBar() — so GetMenuBar()
     * is null and F1 would do nothing at all.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupMenuButton(button: Button) {
        button.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    onNativeKeyDown(KeyEvent.KEYCODE_ESCAPE)
                    button.isPressed = true
                    // Don't assume this opened or closed the menu — syncMenuState
                    // picks up whatever the engine actually did.
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    onNativeKeyUp(KeyEvent.KEYCODE_ESCAPE)
                    button.isPressed = false
                    true
                }
                else -> false
            }
        }
    }

    private fun setupToggleButton(button: Button, group: ViewGroup) {
        controlsHidden = preferences.getBoolean(KEY_CONTROLS_HIDDEN, false)
        group.visibility = if (controlsHidden) View.INVISIBLE else View.VISIBLE

        button.setOnClickListener {
            controlsHidden = !controlsHidden
            group.visibility = if (controlsHidden) View.INVISIBLE else View.VISIBLE
            preferences.edit().putBoolean(KEY_CONTROLS_HIDDEN, controlsHidden).apply()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupJoystick(joystick: FrameLayout, knob: ImageView) {
        joystick.post {
            val centerX = joystick.width / 2f
            val centerY = joystick.height / 2f
            val maxRadius = joystick.width / 2f - knob.width / 2f

            fun recenter() {
                knob.x = centerX - knob.width / 2f
                knob.y = centerY - knob.height / 2f
            }

            joystick.setOnTouchListener { _, event ->
                if (!controlsEnabled) return@setOnTouchListener false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                        var deltaX = event.x - centerX
                        var deltaY = event.y - centerY
                        val distance = sqrt(deltaX * deltaX + deltaY * deltaY)
                        if (distance > maxRadius) {
                            val scale = maxRadius / distance
                            deltaX *= scale
                            deltaY *= scale
                        }

                        knob.x = centerX + deltaX - knob.width / 2f
                        knob.y = centerY + deltaY - knob.height / 2f

                        setAxis(
                            ControllerButtons.AXIS_STICK_X,
                            (deltaX / maxRadius * Short.MAX_VALUE).toInt().toShort()
                        )
                        setAxis(
                            ControllerButtons.AXIS_STICK_Y,
                            (deltaY / maxRadius * Short.MAX_VALUE).toInt().toShort()
                        )
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        recenter()
                        setAxis(ControllerButtons.AXIS_STICK_X, 0)
                        setAxis(ControllerButtons.AXIS_STICK_Y, 0)
                        true
                    }
                    else -> false
                }
            }
        }
    }

    private companion object {
        const val PREFS = "com.lighthouse.android.prefs"
        const val KEY_CONTROLS_HIDDEN = "controlsHidden"

        /** Fast enough to feel immediate on a menu toggle, cheap enough to ignore. */
        const val MENU_POLL_MS = 150L
    }
}
