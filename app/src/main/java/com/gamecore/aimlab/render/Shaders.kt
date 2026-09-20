package com.gamecore.aimlab.render

/**
 * Every shader the training view uses, as constant GLES 2.0 source strings (§2, §9.item-shaders).
 *
 * No shader files ship in the APK; these strings are the whole of it. All are `precision mediump float`
 * so they run on the low-end GPUs the spec targets (PowerVR on a Dimensity 7020), and none use a feature
 * beyond GLES 2.0 — no derivatives extension, no arrays of samplers, nothing that needs a runtime probe.
 * The checker and the fog are computed in the fragment shader from interpolated coordinates, so the room
 * needs no textures at all.
 */
object Shaders {

    /**
     * Lit shader for spheres and the weapon viewmodel: Blinn-Phong diffuse + specular with a rim term
     * (§1: glossy spheres with diffuse+specular+rim). One directional light in view space. The base
     * colour is a uniform so one program draws every target kind and every weapon by colour alone.
     */
    const val LIT_VERTEX = """
        uniform mat4 uMvp;
        uniform mat4 uModel;
        uniform mat4 uNormal;
        attribute vec3 aPos;
        attribute vec3 aNormal;
        varying vec3 vWorldPos;
        varying vec3 vNormal;
        void main() {
            vec4 world = uModel * vec4(aPos, 1.0);
            vWorldPos = world.xyz;
            vNormal = normalize((uNormal * vec4(aNormal, 0.0)).xyz);
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """

    const val LIT_FRAGMENT = """
        precision mediump float;
        uniform vec3 uBaseColor;
        uniform vec3 uLightDir;   // direction TO the light, world space, normalized
        uniform vec3 uCameraPos;  // world space
        varying vec3 vWorldPos;
        varying vec3 vNormal;
        void main() {
            vec3 n = normalize(vNormal);
            vec3 l = normalize(uLightDir);
            vec3 v = normalize(uCameraPos - vWorldPos);
            vec3 h = normalize(l + v);
            float diff = max(dot(n, l), 0.0);
            float spec = pow(max(dot(n, h), 0.0), 48.0);
            // Rim: brighter where the surface faces away from the eye, for the glossy edge highlight.
            float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0);
            vec3 ambient = uBaseColor * 0.25;
            vec3 color = ambient
                + uBaseColor * diff * 0.75
                + vec3(1.0) * spec * 0.6
                + uBaseColor * rim * 0.5;
            gl_FragColor = vec4(color, 1.0);
        }
    """

    /**
     * Room shader: a procedural checker with distance fog (§1). The checker cell comes from the
     * interpolated UV (floored sum parity), tinted by a per-surface base colour so floor/walls/ceiling
     * read differently. Fog mixes toward [uFogColor] by an exponential of view-space depth, giving the
     * "light fog/depth shading" the spec asks for without a second pass.
     */
    const val ROOM_VERTEX = """
        uniform mat4 uMvp;
        uniform mat4 uModel;
        attribute vec3 aPos;
        attribute vec2 aUv;
        varying vec2 vUv;
        varying float vViewDepth;
        void main() {
            vec4 world = uModel * vec4(aPos, 1.0);
            vec4 clip = uMvp * vec4(aPos, 1.0);
            vViewDepth = clip.w;   // perspective w ≈ view-space distance, good enough for fog
            vUv = aUv;
            gl_Position = clip;
        }
    """

    const val ROOM_FRAGMENT = """
        precision mediump float;
        uniform vec3 uColorA;
        uniform vec3 uColorB;
        uniform vec3 uFogColor;
        uniform float uFogDensity;
        varying vec2 vUv;
        varying float vViewDepth;
        void main() {
            float cx = floor(vUv.x);
            float cy = floor(vUv.y);
            float checker = mod(cx + cy, 2.0);
            vec3 base = mix(uColorA, uColorB, checker);
            float fog = 1.0 - exp(-uFogDensity * vViewDepth);
            fog = clamp(fog, 0.0, 1.0);
            gl_FragColor = vec4(mix(base, uFogColor, fog), 1.0);
        }
    """

    /**
     * Flat/unlit shader for particles, the muzzle-flash quad, bullet-hole decals and the crosshair hit
     * marker (§4). MVP + a uniform colour with alpha, so a cheap additive or blended sprite needs no
     * lighting and no texture.
     */
    const val FLAT_VERTEX = """
        uniform mat4 uMvp;
        attribute vec3 aPos;
        void main() {
            gl_Position = uMvp * vec4(aPos, 1.0);
        }
    """

    const val FLAT_FRAGMENT = """
        precision mediump float;
        uniform vec4 uColor;
        void main() {
            gl_FragColor = uColor;
        }
    """
}
