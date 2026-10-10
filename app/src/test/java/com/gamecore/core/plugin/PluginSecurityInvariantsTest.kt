package com.gamecore.core.plugin

import com.gamecore.core.common.AccessLevel
import com.gamecore.core.plugin.content.AimLabContentValidator
import com.gamecore.core.shizuku.ElevatedShell
import com.gamecore.core.shizuku.ShellCommand
import com.gamecore.core.shizuku.ShellResult
import com.gamecore.core.shizuku.WritableSetting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the properties the whole plugin platform rests on, so that widening the surface fails a
 * test rather than passing review.
 *
 * Every other test file in this package checks one component's behaviour. This one checks the
 * claims the threat model makes about the platform as a whole — the claims that are true today
 * because of how several files happen to line up, and that a reasonable-looking refactor could
 * quietly break without breaking anything else:
 *
 *  - **Reserved names stay refused.** `network`, `internet`, `external_app`, `launch_app` and
 *    `intent` are the capabilities a plugin may never have. The refusal is defence in depth —
 *    there is no enum case behind them either — but it is the only thing that tells a developer
 *    *why*, and a reserved name that silently became "unknown" would be a worse message.
 *  - **No value a plugin writes can become a command.** The injection invariant: for every
 *    action and every hostile string, either the form check refuses it, or it travels as exactly
 *    one element of a fixed five-element argv that no shell ever parses. This is the single
 *    property that makes the elevated shell safe to expose at all.
 *  - **The risk tiers mean what the UI says they mean.**
 *  - **Aim Lab content stays primitive.** A product-and-safety line, enforced for every term in
 *    both screened vocabularies rather than the two or three a spot check would reach.
 *  - **Namespaces cannot be confused by a prefix.** `plugin.a.b.` and `plugin.a.bc.` are distinct
 *    because of one trailing dot; drop it and one plugin reads another's stored values.
 *
 * Where a bound lives in a `private` member, this file mirrors it and then asserts the public
 * predicate agrees with the mirror — a mirrored list that drifts is caught by the predicate, and
 * the alternative (reflecting over the compiler's field layout) is more brittle than the thing it
 * would be testing.
 */
class PluginSecurityInvariantsTest {

    /** [ShizukuActionBroker.plan] is pure, so the shell exists only to satisfy the constructor. */
    private class UnusedShell : ElevatedShell {
        override val accessLevel = AccessLevel.SHIZUKU
        override suspend fun isAvailable() = false
        override suspend fun execute(command: ShellCommand, timeoutMillis: Long): ShellResult =
            throw AssertionError("plan() must never reach the shell")
    }

    private fun broker() = ShizukuActionBroker(
        shell = UnusedShell(),
        gate = PluginPermissionGate(),
        rateLimiter = PluginRateLimiter(now = { 0L }),
        auditLog = PluginAuditLog(),
        now = { 0L },
    )

    private fun ready(actionId: String, value: String): ShizukuActionBroker.Plan.Ready {
        val plan = broker().plan(ActionRequest(actionId, value))
        assertTrue("expected a usable plan, got $plan", plan is ShizukuActionBroker.Plan.Ready)
        return plan as ShizukuActionBroker.Plan.Ready
    }

    private fun manifestRequesting(permissionId: String): String =
        """{"format":"gamecore.plugin","id":"com.d.x","name":"X","version":1,"api":1,""" +
            """"minHostVersion":1,"permissions":["$permissionId"]}"""

    private fun reasonFor(permissionId: String): PluginManifestCodec.Reason? =
        (PluginManifestCodec.decode(manifestRequesting(permissionId)) as? PluginManifestCodec.Result.Rejected)
            ?.reason

    /**
     * Mirrors `PluginPermission.RESERVED`, which is private. The test below asserts `isReserved`
     * agrees with every entry here, so a name *removed* from the real set fails this file; a name
     * *added* to it is not covered, which is recorded here rather than worked around — reflecting
     * over the compiler's field layout would be more brittle than the thing it was testing.
     */
    private val reservedMirror = listOf("network", "internet", "external_app", "launch_app", "intent")

    /**
     * Values with a shell metacharacter in the middle, where no numeric grammar can mistake it for
     * padding. These are the injection attempts proper.
     */
    private val injectionAttempts = listOf(
        "60; rm -rf /",
        "\$(id)",
        "1 || true",
        "1 && reboot",
        "60`id`",
        "1 | sh",
        "\${IFS}1",
        "1\u0000; rm -rf /",
        "0\n1",
        "60\nreboot",
    )

    /**
     * The same attempts plus two values padded with characters in U+0000..U+0020, which the
     * float-backed [com.gamecore.core.shizuku.ValueForm]s do admit — see the normalisation test
     * below. The broker trims before it validates, so these are the cases that actually exercise
     * the `Ready` branch.
     */
    private val brokerAttempts = injectionAttempts + listOf("\n60", "60\u0000")

    /** Anything that could turn one argv element into two commands if argv were ever a string. */
    private val shellMetacharacters = listOf(";", "|", "&", "`", "\$(", "\n", "\r")

    // Mirrors AimLabContentValidator's private screening vocabularies, for the same reason as above:
    // the loops below assert every single term is refused, which a spot check cannot.
    private val humanoidTerms = listOf(
        "human", "humanoid", "person", "people", "body", "torso", "head", "silhouette",
        "figure", "soldier", "enemy", "player", "zombie", "character", "man", "woman",
    )
    private val weaponTerms = listOf(
        "gun", "rifle", "pistol", "weapon", "firearm", "ak47", "ak-47", "m4", "knife",
        "sniper", "shotgun", "grenade", "bullet", "ammo", "scope", "barrel",
    )

    private fun packWith(name: String, assetRef: String? = null) = AimLabContentValidator.PackSpec(
        packName = "drills",
        targets = listOf(AimLabContentValidator.TargetSpec(name = name, shapeId = "sphere", assetRef = assetRef)),
    )

    private fun reasonsFor(pack: AimLabContentValidator.PackSpec): List<String> {
        val result = AimLabContentValidator().validate(pack)
        assertTrue("expected a rejection, got $result", result is AimLabContentValidator.Result.Invalid)
        return (result as AimLabContentValidator.Result.Invalid).reasons
    }

    // ------------------------------------------------------------------ reserved capabilities

    @Test
    fun `every reserved permission name is refused with the reserved reason`() {
        reservedMirror.forEach { id ->
            assertTrue("\"$id\" must be reserved", PluginPermission.isReserved(id))
            assertEquals(
                "\"$id\" must be refused as reserved, not as unknown",
                PluginManifestCodec.Reason.RESERVED_PERMISSION,
                reasonFor(id),
            )
        }
        // The check normalises before it compares, so padding and case are not a way around it.
        assertEquals(PluginManifestCodec.Reason.RESERVED_PERMISSION, reasonFor(" NETWORK "))
        assertEquals(PluginManifestCodec.Reason.RESERVED_PERMISSION, reasonFor("Launch_App"))
    }

    @Test
    fun `no reserved name is also a real permission, and none of them resolves`() {
        PluginPermission.entries.forEach { permission ->
            // A permission that was also reserved would be unrequestable: the manifest codec
            // consults isReserved first, so the grant flow could never reach it.
            assertFalse("${permission.id} is both offered and reserved", PluginPermission.isReserved(permission.id))
            assertEquals(permission, PluginPermission.fromId(permission.id))
        }
        reservedMirror.forEach { id ->
            // The reserved check is defence in depth: even without it there is nothing to resolve to.
            assertNull("\"$id\" must not resolve to a permission", PluginPermission.fromId(id))
        }
    }

    @Test
    fun `isReserved and the manifest codec never disagree about a name`() {
        val probes = reservedMirror +
            PluginPermission.entries.map { it.id } +
            listOf("do_anything", "networking", "net", "intents", "external", "launch", "shell", "")
        probes.forEach { id ->
            val reason = reasonFor(id)
            if (PluginPermission.isReserved(id)) {
                assertEquals("\"$id\" is reserved", PluginManifestCodec.Reason.RESERVED_PERMISSION, reason)
            } else {
                // Not reserved means it must fail (or pass) on its own merits — never with the
                // reserved message, which would tell a developer something untrue about their id.
                assertFalse(
                    "\"$id\" is not reserved but was refused as reserved",
                    reason == PluginManifestCodec.Reason.RESERVED_PERMISSION,
                )
            }
        }
    }

    // ------------------------------------------------------------------ the injection invariant

    @Test
    fun `no hostile value survives the broker for any action`() {
        var ready = 0
        BrokerAction.entries.forEach { action ->
            brokerAttempts.forEach { hostile ->
                when (val plan = broker().plan(ActionRequest(action.id, hostile))) {
                    // Refused outright: no command was built, so there is nothing to contain.
                    is ShizukuActionBroker.Plan.Reject -> Unit
                    is ShizukuActionBroker.Plan.Ready -> {
                        ready++
                        val argv = plan.command.argv
                        val where = "${action.id} <- ${hostile.replace("\n", "\\n")}"
                        // The template is fixed, so "exactly one argv element" is precisely
                        // "five elements, and the value is the fifth".
                        assertEquals("$where: argv must stay the fixed template", 5, argv.size)
                        assertEquals("$where: the value must be the last element", plan.appliedValue, argv[4])
                        shellMetacharacters.forEach { meta ->
                            assertFalse(
                                "$where: argv must not carry ${meta.replace("\n", "\\n")}",
                                argv.any { it.contains(meta) },
                            )
                        }
                    }
                }
            }
        }
        // If every attempt were refused the assertions above would be vacuous. Two of the padded
        // values are valid rates once trimmed, so the Ready branch is genuinely exercised.
        assertTrue("the Ready branch must be reached, or this test proves nothing", ready > 0)
    }

    @Test
    fun `the settings command template is fixed and the value is its last element`() {
        val plan = ready(BrokerAction.SET_PEAK_REFRESH_RATE.id, "120")
        // A plugin names neither the verb, the namespace, nor the key: all three come from the
        // registry's own WritableSetting. Only the fifth element is plugin-influenced at all.
        assertEquals(
            listOf("settings", "put", "system", "peak_refresh_rate", "120"),
            plan.command.argv,
        )
        assertFalse(plan.wasClamped)
    }

    @Test
    fun `no hostile value can be built into a command for any writable setting`() {
        // Wider than the broker: every WritableSetting, not only the seven a BrokerAction reaches.
        // If a future action points at one of the others, this invariant already covers it.
        WritableSetting.entries.forEach { setting ->
            injectionAttempts.forEach { hostile ->
                val command = ShellCommand.putSetting(setting, hostile)
                val where = "${setting.key} <- ${hostile.replace("\n", "\\n")}"
                if (command != null) {
                    assertEquals("$where: argv must stay the fixed template", 5, command.argv.size)
                    assertEquals("$where: the value must be the last element", hostile, command.argv[4])
                    shellMetacharacters.forEach { meta ->
                        assertFalse("$where: argv must not carry it", command.argv.any { it.contains(meta) })
                    }
                } else {
                    assertFalse("$where: a refused value must also fail its form", setting.accepts(hostile))
                }
            }

            // The positive half of the same invariant: a setting's own restore default must build,
            // or GameCore could not undo its own write, and it lands behind the same fixed template.
            val restore = ShellCommand.putSetting(setting, setting.restoreDefault)
            assertNotNull("${setting.key}: its own restore default must be a valid value", restore)
            assertEquals(
                listOf("settings", "put", setting.namespace.token, setting.key, setting.restoreDefault),
                restore!!.argv,
            )
        }
    }

    @Test
    fun `a padded value is normalised before it reaches argv`() {
        // kotlin.text.toFloatOrNull admits padding in U+0000..U+0020 at either end, so the float
        // forms accept "\n60" on their own. That is a stdlib detail; what must hold is that the
        // broker trims before it validates, so nothing downstream ever sees the padding.
        val plan = ready(BrokerAction.SET_PEAK_REFRESH_RATE.id, "\n60")
        assertEquals("60", plan.appliedValue)
        assertFalse(plan.command.argv.any { it.contains("\n") })

        if (WritableSetting.PEAK_REFRESH_RATE.accepts("\n60")) {
            // Reached directly — bypassing the broker's trim — the padding is still only ever one
            // argv element. argv is a String[] that no shell parses, so a newline inside an element
            // is a strange value, not a second command. Tightening the form later is fine; losing
            // the containment is not, which is what this branch pins.
            val raw = ShellCommand.putSetting(WritableSetting.PEAK_REFRESH_RATE, "\n60")
            assertNotNull("a form that accepts a value must be able to build its command", raw)
            assertEquals(5, raw!!.argv.size)
            assertEquals("\n60", raw.argv[4])
        }

        // A NUL is the one padding character trim() does not remove, because
        // Character.isWhitespace('\u0000') is false. Whichever way that is settled, the value stays
        // contained in a single element.
        when (val nul = broker().plan(ActionRequest(BrokerAction.SET_PEAK_REFRESH_RATE.id, "60\u0000"))) {
            is ShizukuActionBroker.Plan.Reject -> Unit
            is ShizukuActionBroker.Plan.Ready -> {
                assertEquals(5, nul.command.argv.size)
                assertEquals(nul.appliedValue, nul.command.argv[4])
            }
        }
    }

    // ------------------------------------------------------------------ risk tiers

    @Test
    fun `no broker action is high risk, and high risk still means something`() {
        // Every action a plugin can name is LOW or MEDIUM today, which is why the per-use HIGH
        // confirmation in ShizukuActionBroker.run is unreachable by data in this release. It is
        // wired and fails closed anyway; the day a HIGH action is added, this assertion is the one
        // that forces someone to look at the confirmation path before shipping it.
        BrokerAction.entries.forEach { action ->
            assertTrue(
                "${action.id} is ${action.tier} — a HIGH action needs the confirm path reviewed",
                action.tier == RiskTier.LOW || action.tier == RiskTier.MEDIUM,
            )
        }

        // HIGH is a live concept one level up: the permission that leaves GameCore at all is HIGH,
        // and it is the only one, which is what makes the separate advanced opt-in meaningful.
        assertEquals(RiskTier.HIGH, PluginPermission.SYSTEM_ACTIONS.tier)
        assertEquals(1, PluginPermission.entries.count { it.tier == RiskTier.HIGH })
    }

    // ------------------------------------------------------------------ aim lab content

    @Test
    fun `every screened humanoid term is refused as a humanoid target`() {
        humanoidTerms.forEach { term ->
            val reasons = reasonsFor(packWith(name = term))
            assertTrue(
                "\"$term\" must be refused: $reasons",
                reasons.any { it.contains("humanoid target") },
            )
        }
        // The screen is a substring match, so a term inside a longer name is caught too.
        assertTrue(reasonsFor(packWith(name = "practice silhouette v2")).any { it.contains("humanoid") })
    }

    @Test
    fun `every screened weapon term is refused as a weapon model`() {
        weaponTerms.forEach { term ->
            val reasons = reasonsFor(packWith(name = term))
            assertTrue(
                "\"$term\" must be refused: $reasons",
                reasons.any { it.contains("weapon model") },
            )
        }
        assertTrue(reasonsFor(packWith(name = "flick_rifle_drill")).any { it.contains("weapon") })
    }

    @Test
    fun `only primitive textures are permitted as assets`() {
        // A mesh is the thing this rule exists to keep out: a plugin shipping .glb could ship any
        // shape at all, and the primitive allowlist would be decoration.
        listOf("dot.jpg", "dot.svg", "dot.glb", "dot.obj", "dot.fbx", "dot.gif", "dot", "dot.png.exe")
            .forEach { ref ->
                assertTrue(
                    "\"$ref\" must not be a permitted asset",
                    reasonsFor(packWith(name = "dot", assetRef = ref))
                        .any { it.contains("not a permitted primitive texture") },
                )
            }
        listOf("dot.png", "dot.webp", "DOT.PNG").forEach { ref ->
            assertEquals(
                "\"$ref\" is a texture and must be accepted",
                AimLabContentValidator.Result.Valid,
                AimLabContentValidator().validate(packWith(name = "dot", assetRef = ref)),
            )
        }
    }

    // ------------------------------------------------------------------ sections and namespaces

    @Test
    fun `no section entry prefix is a prefix of another section's`() {
        // sectionOf matches a package entry against these prefixes. If one were a prefix of
        // another, an entry would belong to two sections and which one won would depend on
        // iteration order — a plugin could ship content under a section it never declared.
        PluginSection.entries.forEach { a ->
            PluginSection.entries.filter { it != a }.forEach { b ->
                assertFalse(
                    "${a.name}'s prefix \"${a.entryPrefix}\" starts with ${b.name}'s \"${b.entryPrefix}\"",
                    a.entryPrefix.startsWith(b.entryPrefix),
                )
            }
            assertTrue("${a.name} has a blank id", a.id.isNotBlank())
            assertEquals(a, PluginSection.fromId(a.id))
        }
        assertEquals(PluginSection.entries.size, PluginSection.entries.map { it.id }.distinct().size)
    }

    @Test
    fun `a qualified key never belongs to a plugin whose id merely resembles its owner`() {
        val key = PluginNamespace.qualify("com.dev.perf", "preset")
        assertNotNull(key)
        assertTrue(PluginNamespace.belongsTo("com.dev.perf", key!!))

        // The near-miss the trailing dot in PluginNamespace.of exists for. Drop it and
        // "com.dev.perfpro" reads every key "com.dev.perf" ever wrote.
        assertFalse(PluginNamespace.belongsTo("com.dev.perfpro", key))
        assertFalse(PluginNamespace.belongsTo("com.dev.per", key))

        // The same thing at minimum length, in both directions.
        val shortKey = PluginNamespace.qualify("a.b", "k")
        val longKey = PluginNamespace.qualify("a.bc", "k")
        assertNotNull(shortKey)
        assertNotNull(longKey)
        assertFalse(PluginNamespace.belongsTo("a.b", longKey!!))
        assertFalse(PluginNamespace.belongsTo("a.bc", shortKey!!))
        assertTrue(PluginNamespace.belongsTo("a.b", shortKey))
        assertTrue(PluginNamespace.belongsTo("a.bc", longKey))

        // An unqualified key belongs to nobody: belongsTo is a guard, not a default-allow.
        assertFalse(PluginNamespace.belongsTo("com.dev.perf", "preset"))
        assertFalse(PluginNamespace.belongsTo("com.dev.perf", "plugin.preset"))
    }
}
