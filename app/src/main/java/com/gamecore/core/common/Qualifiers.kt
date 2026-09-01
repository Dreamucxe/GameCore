package com.gamecore.core.common

import javax.inject.Qualifier

/**
 * Hilt qualifiers for the coroutine dispatchers.
 *
 * Injecting dispatchers rather than reaching for `Dispatchers.IO` in place is what
 * makes the samplers, repositories and controllers testable against `runTest`'s
 * scheduler, and it keeps every `/proc` read, shell invocation and database write
 * provably off the main thread — which matters more here than in most apps,
 * because a frame dropped by GameCore is a frame dropped in the game underneath
 * its overlay.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * Application-scoped supervisor scope, for work that must outlive whatever started
 * it: finishing a session write after the tracking service is told to stop,
 * restoring a device setting after the game that changed it has exited.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
