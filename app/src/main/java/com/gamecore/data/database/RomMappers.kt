package com.gamecore.data.database

import com.gamecore.core.emulator.EmulatorSystem
import com.gamecore.core.emulator.RomEntry
import com.gamecore.core.emulator.RomFile

/**
 * Room rows ↔ ROM-library models.
 *
 * An unrecognised system name does not drop the row. Unlike a controller profile whose curve this build
 * cannot interpret, a ROM is the user's file and remains useful even when its family is unknown; it is
 * shown as "Unknown system" and can be reassigned. Dropping it would hide a file the user deliberately
 * added.
 */
object RomMappers {

    fun toModel(entity: RomEntity): RomEntry? {
        val uri = entity.uri.trim()
        val name = entity.displayName.trim()
        if (uri.isEmpty() || name.isEmpty()) return null
        val extension = entity.extension
            .trim()
            .lowercase()
            .ifEmpty { RomFile.extensionOf(name).orEmpty() }
        return RomEntry(
            id = entity.id,
            uri = uri,
            displayName = name,
            sizeBytes = entity.sizeBytes?.takeIf { it >= 0L },
            extension = extension,
            system = EmulatorSystem.fromStoredName(entity.system),
            emulatorPackage = entity.emulatorPackage?.trim()?.takeIf { it.isNotEmpty() },
            addedAtMillis = entity.addedAtMillis,
            lastLaunchedAtMillis = entity.lastLaunchedAtMillis,
            launchCount = entity.launchCount.coerceAtLeast(0),
        )
    }

    fun toEntity(model: RomEntry): RomEntity = RomEntity(
        id = model.id,
        uri = model.uri,
        displayName = model.displayName,
        sizeBytes = model.sizeBytes,
        extension = model.extension,
        system = model.system.name,
        emulatorPackage = model.emulatorPackage,
        addedAtMillis = model.addedAtMillis,
        lastLaunchedAtMillis = model.lastLaunchedAtMillis,
        launchCount = model.launchCount.coerceAtLeast(0),
    )
}
