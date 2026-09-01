package com.gamecore.core.shizuku

import android.content.Context
import android.content.pm.ProviderInfo
import android.os.Bundle
import rikka.shizuku.ShizukuProvider

/**
 * Shizuku's binder handshake, wrapped so that it cannot stop GameCore from starting.
 *
 * Shizuku delivers its binder by calling into a content provider the app declares,
 * so the provider has to exist for Shizuku support to work at all. The manifest
 * points here rather than at [ShizukuProvider] directly because of what Android does
 * with a provider that throws: content providers are constructed while the process
 * starts, before `Application.onCreate`, and an exception from one of them is fatal.
 * The app dies on launch, before a line of GameCore has run, with no screen and no
 * way to recover.
 *
 * `ShizukuProvider.onCreate` can throw. It asks Sui whether it is present, and that
 * path ends at `android.os.ServiceManager.getService` reached by reflection; when
 * hidden-API enforcement denies that member the reflected `Method` is left null and
 * the resulting `NullPointerException` is not caught on the way back out. Whether it
 * is denied depends on the Android version, the vendor and the target SDK — exactly
 * the kind of thing that cannot be assumed from a build-time constant.
 *
 * So the whole of it is guarded. Shizuku is one access level among several in this
 * app and never a requirement: if this initialisation fails, GameCore starts
 * normally and reports Shizuku as unavailable through the same state machine that
 * handles a device where it was never installed. An optional elevation path is not
 * permitted to be a single point of failure.
 *
 * Nothing is logged. A failure here is expected on a large fraction of devices, and
 * the security requirements forbid writing Shizuku-related detail to logcat in a
 * release build; the state is observable through [ShizukuShell.state], which is
 * where a user looking for an explanation is actually sent.
 *
 * Shizuku itself is unaffected by the subclassing: its server finds this provider by
 * authority — `${applicationId}.shizuku`, declared in the manifest — not by class
 * name, and the inherited `call` implementation still performs the exchange.
 */
class ShizukuStartupProvider : ShizukuProvider() {

    override fun attachInfo(context: Context, info: ProviderInfo) {
        // ShizukuProvider attaches the context first and only then validates the
        // manifest entry, so by the time it can object the provider is already
        // usable — swallowing the objection loses nothing but the crash. GameCore's
        // entry declares multiprocess="false" and exported="true", which is what it
        // asks for, so this should not trigger.
        try {
            super.attachInfo(context, info)
        } catch (error: Throwable) {
        }
    }

    override fun onCreate(): Boolean {
        try {
            super.onCreate()
        } catch (error: Throwable) {
            // Most likely the Sui probe reaching a denied hidden API. Shizuku ends up
            // reported as unavailable, which is a state the app already handles fully.
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? = try {
        super.call(method, arg, extras)
    } catch (error: Throwable) {
        // A throw here happens inside a binder call from the Shizuku server rather
        // than at startup, but it would still surface as a crash. Failing the
        // handshake quietly leaves Shizuku unavailable and the app intact.
        null
    }
}
