package com.gamecore.di

import com.gamecore.core.plugin.PluginGrantStore
import com.gamecore.core.plugin.PluginRegistry
import com.gamecore.data.plugin.PluginRegistryImpl
import com.gamecore.data.plugin.SecurePluginGrantStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Binds the plugin registry and the plugin grant store.
 *
 * Note what is *not* bound here, because the omissions are the design. There is no
 * `ElevatedShell` in a plugin's reach: nothing in `core.plugin` or `data.plugin`
 * declares one, so a plugin cannot be handed a shell by the graph any more than it
 * can ask for one at runtime. There is no broker binding either — the object that
 * answers a plugin's calls is constructed by the host that owns the data it would
 * expose, with the permission gate between them, rather than being available to
 * anything that can name a type. And the raw bytes of a package appear in no binding
 * at all: [PluginRegistryImpl] is the only class that reads them and it exposes
 * parsed, validated, sanitised values, so a ViewModel that wanted a package would
 * have to be given one by hand.
 *
 * Both implementations are bound as their interfaces *and* remain injectable as
 * themselves, which is deliberate and narrow. [PluginRegistryImpl] takes the concrete
 * [SecurePluginGrantStore] because it needs the two members the interface does not
 * carry — the selected theme id and the suspension detail a row shows instead of
 * looking dead — and the registry is the one class with a reason to know both.
 * Everything in the presentation layer takes the interfaces, where the only
 * operations that exist are the ones a reviewer has to check.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PluginModule {

    @Binds
    abstract fun pluginRegistry(impl: PluginRegistryImpl): PluginRegistry

    @Binds
    abstract fun pluginGrantStore(impl: SecurePluginGrantStore): PluginGrantStore
}
