package com.gamecore.core.overlay

import android.content.Context
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * The owners a Compose hierarchy needs when there is no Activity to provide them.
 *
 * `ComposeView` will not compose without a `LifecycleOwner`, and it will not *recompose* without one
 * that reaches `RESUMED` — a view stuck at `CREATED` renders its first frame and then never updates,
 * which for a performance pill means a stat panel frozen on the numbers from the moment it appeared.
 * `SavedStateRegistryOwner` is needed because `rememberSaveable` looks for one and throws if it is
 * missing, and `ViewModelStoreOwner` because `hiltViewModel()` does.
 *
 * One host per service, not per window: the lifecycle being modelled is the service's, and giving each
 * window its own would mean four registries to drive in step. [attach] wires a new view into this one.
 *
 * The ordering in [start] is not cosmetic. `performRestore` must happen before the registry leaves
 * `INITIALIZED`, or `SavedStateRegistryController` throws; and a view added to the `WindowManager`
 * before the host is resumed composes once against a lifecycle that is not ready and silently never
 * recomposes, which is the frozen-pill bug above.
 */
class OverlayViewHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry

    override val viewModelStore: ViewModelStore = ViewModelStore()

    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    /** True between [start] and [stop]; windows may only be added while it is true. */
    var isRunning: Boolean = false
        private set

    /**
     * Brings the host to `RESUMED`.
     *
     * Called from the service's `onCreate`, before any window is added. Idempotent, because a service
     * that is started twice — which happens whenever the system redelivers a start intent — must not
     * restore the saved-state registry a second time.
     */
    fun start() {
        if (isRunning) return
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        isRunning = true
    }

    /**
     * Tears the host down.
     *
     * `DESTROYED` first so every composition inside every attached window disposes and its effects are
     * cancelled, and only then the ViewModel store — clearing it while a composition still held a
     * ViewModel would leave that composition talking to a cleared object for the length of one frame.
     */
    fun stop() {
        if (!isRunning) return
        isRunning = false
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        viewModelStore.clear()
    }

    /**
     * Builds a `ComposeView` owned by this host, ready to hand to the `WindowManager`.
     *
     * No inset padding is applied to overlay content anywhere, and that is a rule rather than an
     * omission: an overlay window is positioned in raw screen coordinates by [OverlayFrame], so a
     * `windowInsetsPadding` inside it would shift the content within its own window and put a pill the
     * user placed at the top edge visibly below where they put it. The window, not the content, is what
     * gets moved out of the status bar's way.
     *
     * The default `ViewCompositionStrategy` is the right one here — it disposes the composition when the
     * view is detached, which is exactly what `WindowManager.removeView` does — so it is left alone.
     */
    fun attach(context: Context, content: @Composable () -> Unit): View =
        ComposeView(context).apply {
            setViewTreeLifecycleOwner(this@OverlayViewHost)
            setViewTreeViewModelStoreOwner(this@OverlayViewHost)
            setViewTreeSavedStateRegistryOwner(this@OverlayViewHost)
            setContent(content)
        }
}
