package com.gamecore.core.plugin.content

/**
 * Validates an Aim Lab content pack shipped by a plugin.
 *
 * GameCore's own aim trainer has humanoid targets and the models that go with them. A
 * *plugin's* aim content deliberately may not: the plugin platform ships primitive practice
 * shapes — spheres, discs, boxes, the geometric targets of a flick or tracking drill — and
 * nothing that is a person-shaped target or a weapon model. This is a product-and-safety line,
 * not a technical one, and it is enforced by allow-listing the primitive shape set and
 * rejecting any target whose shape, name, or asset reference reaches for a humanoid or a
 * weapon rather than by trying to blocklist every synonym.
 *
 * The rejection is specific so a developer sees why: a pack naming a "silhouette" target or
 * referencing a "rifle.glb" asset is told which rule it broke, not merely "invalid".
 */
class AimLabContentValidator {

    /** The only target shapes a plugin pack may use. Anything else is rejected. */
    enum class PrimitiveShape(val id: String) {
        SPHERE("sphere"),
        DISC("disc"),
        BOX("box"),
        CAPSULE_PILL("pill"),   // a rounded pill shape — a primitive, not a figure
        DIAMOND("diamond"),
        RING("ring");

        companion object {
            private val byId = entries.associateBy { it.id }
            fun fromId(id: String): PrimitiveShape? = byId[id.trim().lowercase()]
        }
    }

    data class TargetSpec(
        val name: String,
        val shapeId: String,
        val assetRef: String? = null,
    )

    data class PackSpec(
        val packName: String,
        val targets: List<TargetSpec>,
    )

    sealed interface Result {
        data object Valid : Result
        data class Invalid(val reasons: List<String>) : Result
    }

    fun validate(pack: PackSpec): Result {
        val reasons = mutableListOf<String>()
        if (pack.targets.isEmpty()) reasons += "pack has no targets"
        if (pack.targets.size > MAX_TARGETS) reasons += "pack exceeds $MAX_TARGETS targets"

        pack.targets.forEach { t ->
            if (PrimitiveShape.fromId(t.shapeId) == null) {
                reasons += "target '${t.name}': shape '${t.shapeId}' is not a permitted primitive"
            }
            forbiddenWord(t.name)?.let { reasons += "target name '${t.name}' suggests a $it, which plugin packs may not add" }
            t.assetRef?.let { ref ->
                forbiddenWord(ref)?.let { reasons += "asset '$ref' suggests a $it, which plugin packs may not add" }
                if (!ALLOWED_ASSET_EXT.any { ref.lowercase().endsWith(it) }) {
                    reasons += "asset '$ref' is not a permitted primitive texture"
                }
            }
        }
        return if (reasons.isEmpty()) Result.Valid else Result.Invalid(reasons.distinct())
    }

    /** Returns a short category word if [text] reaches for a humanoid or a weapon, else null. */
    private fun forbiddenWord(text: String): String? {
        val t = text.lowercase()
        if (HUMANOID_TERMS.any { t.contains(it) }) return "humanoid target"
        if (WEAPON_TERMS.any { t.contains(it) }) return "weapon model"
        return null
    }

    private companion object {
        const val MAX_TARGETS = 64

        /** Primitive packs are textured, not modelled: an image, not a mesh. No .glb/.obj/.fbx. */
        val ALLOWED_ASSET_EXT = listOf(".png", ".webp")

        val HUMANOID_TERMS = listOf(
            "human", "humanoid", "person", "people", "body", "torso", "head", "silhouette",
            "figure", "soldier", "enemy", "player", "zombie", "character", "man", "woman",
        )
        val WEAPON_TERMS = listOf(
            "gun", "rifle", "pistol", "weapon", "firearm", "ak47", "ak-47", "m4", "knife",
            "sniper", "shotgun", "grenade", "bullet", "ammo", "scope", "barrel",
        )
    }
}
