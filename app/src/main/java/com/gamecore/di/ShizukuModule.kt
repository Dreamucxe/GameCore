package com.gamecore.di

import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShizukuShell
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the elevated shell.
 *
 * Note what is *not* bound here: nothing above `core` can obtain an [ElevatedShell]
 * from the graph, because no class outside `core.shizuku`, `core.system` and
 * `domain.optimization` declares one as a dependency — and the architecture's rule
 * that the UI never executes shell commands is checked by that fact rather than by
 * review. A ViewModel that wanted to run a command would have to add a constructor
 * parameter for a type it has no legitimate reason to name, which is exactly the
 * kind of change a diff makes obvious.
 *
 * [ShizukuShell] is bound as the interface *and* remains injectable as itself,
 * because the Shizuku screen needs the connection-state flow and the permission
 * request, which are not part of the shell abstraction.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class ShizukuModule {

    @Binds
    abstract fun elevatedShell(impl: ShizukuShell): ElevatedShell
}
