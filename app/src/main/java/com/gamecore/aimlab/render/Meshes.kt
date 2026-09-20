package com.gamecore.aimlab.render

import com.gamecore.aimlab.engine.WeaponCategory
import com.gamecore.aimlab.engine3d.Room
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A ready-to-draw mesh: an interleaved vertex buffer and an index buffer, plus its counts.
 *
 * [stride] is in floats per vertex. For lit meshes the layout is position(3)+normal(3); for the room it
 * is position(3)+uv(2). The buffers are direct NIO buffers so they can be handed straight to
 * `glVertexAttribPointer`. Meshes are built once in `onSurfaceCreated` and reused every frame — there is
 * no per-frame geometry generation.
 */
class Mesh(
    val vertices: FloatBuffer,
    val indices: ShortBuffer,
    val indexCount: Int,
    val stride: Int,
)

/**
 * Procedural mesh construction — no model files anywhere (§2, §9). A UV sphere for targets, six quads for
 * the room, and a distinct low-poly silhouette per weapon category assembled from boxes. All geometry is
 * original; nothing replicates a real product or carries a logo (§1).
 */
object Meshes {

    private fun floatBuffer(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(data); position(0)
        }

    private fun shortBuffer(data: ShortArray): ShortBuffer =
        ByteBuffer.allocateDirect(data.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply {
            put(data); position(0)
        }

    /**
     * A unit-radius UV sphere, position+normal interleaved (§2: ~16×10). For a unit sphere the normal at a
     * vertex is its position, so the two are identical — the renderer scales it to each target's radius
     * with the model matrix.
     */
    fun buildUvSphere(stacks: Int = 10, slices: Int = 16): Mesh {
        val verts = ArrayList<Float>((stacks + 1) * (slices + 1) * 6)
        for (i in 0..stacks) {
            val v = i.toFloat() / stacks
            val phi = v * PI.toFloat()        // 0..π
            val y = cos(phi)
            val r = sin(phi)
            for (j in 0..slices) {
                val u = j.toFloat() / slices
                val theta = u * 2f * PI.toFloat()
                val x = r * cos(theta)
                val z = r * sin(theta)
                // position == normal for a unit sphere
                verts.add(x); verts.add(y); verts.add(z)
                verts.add(x); verts.add(y); verts.add(z)
            }
        }
        val idx = ArrayList<Short>(stacks * slices * 6)
        val cols = slices + 1
        for (i in 0 until stacks) {
            for (j in 0 until slices) {
                val a = (i * cols + j).toShort()
                val b = ((i + 1) * cols + j).toShort()
                val c = ((i + 1) * cols + j + 1).toShort()
                val d = (i * cols + j + 1).toShort()
                idx.add(a); idx.add(b); idx.add(d)
                idx.add(b); idx.add(c); idx.add(d)
            }
        }
        return Mesh(floatBuffer(verts.toFloatArray()), shortBuffer(idx.toShortArray()), idx.size, stride = 6)
    }

    /**
     * The room as six inward-facing quads (floor, ceiling, four walls), position+UV interleaved. UVs are
     * scaled to roughly one checker cell per world unit so the pattern reads at a sensible size in the
     * room shader. Winding faces inward because the camera is inside the box.
     */
    fun buildRoom(room: Room): Mesh {
        val hw = room.halfWidth
        val hd = room.halfDepth
        val h = room.height
        val verts = ArrayList<Float>()
        val idx = ArrayList<Short>()

        // Adds one quad from four corners (CCW as seen from inside) with UVs derived from the two spans.
        fun quad(
            ax: Float, ay: Float, az: Float, au: Float, av: Float,
            bx: Float, by: Float, bz: Float, bu: Float, bv: Float,
            cx: Float, cy: Float, cz: Float, cu: Float, cv: Float,
            dx: Float, dy: Float, dz: Float, du: Float, dv: Float,
        ) {
            val base = (verts.size / 5).toShort()
            verts.addAll(listOf(ax, ay, az, au, av))
            verts.addAll(listOf(bx, by, bz, bu, bv))
            verts.addAll(listOf(cx, cy, cz, cu, cv))
            verts.addAll(listOf(dx, dy, dz, du, dv))
            idx.add(base); idx.add((base + 1).toShort()); idx.add((base + 2).toShort())
            idx.add(base); idx.add((base + 2).toShort()); idx.add((base + 3).toShort())
        }

        // Floor (y=0), UV from x/z.
        quad(
            -hw, 0f, -hd, 0f, 0f,
            -hw, 0f, hd, 0f, 2f * hd,
            hw, 0f, hd, 2f * hw, 2f * hd,
            hw, 0f, -hd, 2f * hw, 0f,
        )
        // Ceiling (y=h).
        quad(
            -hw, h, -hd, 0f, 0f,
            hw, h, -hd, 2f * hw, 0f,
            hw, h, hd, 2f * hw, 2f * hd,
            -hw, h, hd, 0f, 2f * hd,
        )
        // Far wall (z=-hd), UV from x/y.
        quad(
            -hw, 0f, -hd, 0f, 0f,
            hw, 0f, -hd, 2f * hw, 0f,
            hw, h, -hd, 2f * hw, h,
            -hw, h, -hd, 0f, h,
        )
        // Near wall (z=hd).
        quad(
            -hw, 0f, hd, 0f, 0f,
            -hw, h, hd, 0f, h,
            hw, h, hd, 2f * hw, h,
            hw, 0f, hd, 2f * hw, 0f,
        )
        // Left wall (x=-hw), UV from z/y.
        quad(
            -hw, 0f, -hd, 0f, 0f,
            -hw, h, -hd, 0f, h,
            -hw, h, hd, 2f * hd, h,
            -hw, 0f, hd, 2f * hd, 0f,
        )
        // Right wall (x=hw).
        quad(
            hw, 0f, -hd, 0f, 0f,
            hw, 0f, hd, 2f * hd, 0f,
            hw, h, hd, 2f * hd, h,
            hw, h, -hd, 0f, h,
        )
        return Mesh(floatBuffer(verts.toFloatArray()), shortBuffer(idx.toShortArray()), idx.size, stride = 5)
    }

    /**
     * A distinct low-poly weapon silhouette per category, assembled from axis-aligned boxes in the
     * viewmodel's local space (§1). Each is original geometry — a bundle of prisms, not a scan of a real
     * gun — and differs enough in proportion that a glance tells a pistol from a sniper: pistols are short
     * with a tall grip, SMGs stubby, ARs medium with a longer barrel, LMGs bulky with a box magazine,
     * shotguns thick and short-barrelled, snipers long and thin with a scope block on top.
     *
     * Position+normal interleaved for the lit shader. Local origin is at the grip; the renderer places it
     * bottom-right and pushes it toward centre for ADS.
     */
    fun buildWeaponViewmodel(category: WeaponCategory): Mesh {
        val boxes = weaponBoxes(category)
        val verts = ArrayList<Float>(boxes.size * 6 * 6 * 6)
        val idx = ArrayList<Short>(boxes.size * 36)
        for (b in boxes) appendBox(verts, idx, b)
        return Mesh(floatBuffer(verts.toFloatArray()), shortBuffer(idx.toShortArray()), idx.size, stride = 6)
    }

    /** One box: centre (cx,cy,cz) and half-extents (hx,hy,hz). */
    private data class Box(
        val cx: Float, val cy: Float, val cz: Float,
        val hx: Float, val hy: Float, val hz: Float,
    )

    private fun weaponBoxes(category: WeaponCategory): List<Box> = when (category) {
        WeaponCategory.PISTOL -> listOf(
            Box(0f, 0.06f, -0.10f, 0.03f, 0.03f, 0.12f),   // short slide
            Box(0f, -0.06f, 0.0f, 0.03f, 0.08f, 0.03f),    // tall grip
        )
        WeaponCategory.SMG -> listOf(
            Box(0f, 0.05f, -0.12f, 0.035f, 0.035f, 0.16f), // stubby body
            Box(0f, -0.05f, 0.02f, 0.03f, 0.07f, 0.03f),   // grip
            Box(0f, -0.02f, 0.10f, 0.025f, 0.05f, 0.03f),  // short mag
        )
        WeaponCategory.ASSAULT_RIFLE -> listOf(
            Box(0f, 0.05f, -0.20f, 0.03f, 0.03f, 0.26f),   // longer barrel/body
            Box(0f, -0.05f, 0.04f, 0.03f, 0.07f, 0.03f),   // grip
            Box(0f, -0.03f, 0.12f, 0.025f, 0.06f, 0.03f),  // mag
            Box(0f, 0.10f, -0.02f, 0.02f, 0.02f, 0.06f),   // low sight rail
        )
        WeaponCategory.LMG -> listOf(
            Box(0f, 0.05f, -0.24f, 0.045f, 0.045f, 0.30f), // bulky body/barrel
            Box(0f, -0.06f, 0.06f, 0.035f, 0.08f, 0.035f), // grip
            Box(0.05f, -0.04f, 0.10f, 0.06f, 0.07f, 0.05f),// box magazine
        )
        WeaponCategory.SHOTGUN -> listOf(
            Box(0f, 0.05f, -0.16f, 0.045f, 0.045f, 0.20f), // thick short barrel
            Box(0f, 0.0f, 0.02f, 0.045f, 0.03f, 0.10f),    // pump under barrel
            Box(0f, -0.06f, 0.10f, 0.03f, 0.07f, 0.03f),   // grip/stock
        )
        WeaponCategory.SNIPER -> listOf(
            Box(0f, 0.05f, -0.34f, 0.025f, 0.025f, 0.40f), // long thin barrel
            Box(0f, -0.05f, 0.08f, 0.03f, 0.07f, 0.03f),   // grip
            Box(0f, 0.13f, -0.06f, 0.03f, 0.035f, 0.12f),  // scope block on top
        )
    }

    /** Appends the 24 vertices (position+normal per face) and 36 indices of one axis-aligned box. */
    private fun appendBox(verts: ArrayList<Float>, idx: ArrayList<Short>, b: Box) {
        val x0 = b.cx - b.hx; val x1 = b.cx + b.hx
        val y0 = b.cy - b.hy; val y1 = b.cy + b.hy
        val z0 = b.cz - b.hz; val z1 = b.cz + b.hz
        // Six faces, each four verts sharing a normal.
        val faces = arrayOf(
            // +X
            floatArrayOf(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1) to floatArrayOf(1f, 0f, 0f),
            // -X
            floatArrayOf(x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0) to floatArrayOf(-1f, 0f, 0f),
            // +Y
            floatArrayOf(x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0) to floatArrayOf(0f, 1f, 0f),
            // -Y
            floatArrayOf(x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1) to floatArrayOf(0f, -1f, 0f),
            // +Z
            floatArrayOf(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1) to floatArrayOf(0f, 0f, 1f),
            // -Z
            floatArrayOf(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0) to floatArrayOf(0f, 0f, -1f),
        )
        for ((corners, normal) in faces) {
            val base = (verts.size / 6).toShort()
            var p = 0
            repeat(4) {
                verts.add(corners[p]); verts.add(corners[p + 1]); verts.add(corners[p + 2])
                verts.add(normal[0]); verts.add(normal[1]); verts.add(normal[2])
                p += 3
            }
            idx.add(base); idx.add((base + 1).toShort()); idx.add((base + 2).toShort())
            idx.add(base); idx.add((base + 2).toShort()); idx.add((base + 3).toShort())
        }
    }

    /** A single quad in the XY plane centred at origin, side 1, position-only (stride 3) for flat sprites. */
    fun buildUnitQuad(): Mesh {
        val verts = floatArrayOf(
            -0.5f, -0.5f, 0f,
            0.5f, -0.5f, 0f,
            0.5f, 0.5f, 0f,
            -0.5f, 0.5f, 0f,
        )
        val idx = shortArrayOf(0, 1, 2, 0, 2, 3)
        return Mesh(floatBuffer(verts), shortBuffer(idx), idx.size, stride = 3)
    }
}
