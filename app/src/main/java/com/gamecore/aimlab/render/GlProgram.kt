package com.gamecore.aimlab.render

import android.opengl.GLES20

/** Thrown when a shader will not compile or a program will not link; carries the GL info log. */
class GlException(message: String) : RuntimeException(message)

/**
 * A compiled+linked GLES 2.0 program with cached uniform/attribute locations.
 *
 * Compilation and linking are checked (§2): a failed compile or link throws [GlException] with the info
 * log, which the view boundary catches to show the "3D view unavailable" screen rather than letting the
 * GL thread die on an un-recoverable state. Locations are looked up once at construction and cached, so
 * the draw loop never calls `glGetUniformLocation` per frame.
 *
 * Built inside `onSurfaceCreated`, so a context loss that discards the program simply builds a fresh one.
 */
class GlProgram(vertexSrc: String, fragmentSrc: String) {

    val id: Int
    private val uniforms = HashMap<String, Int>()
    private val attribs = HashMap<String, Int>()

    init {
        val vs = compile(GLES20.GL_VERTEX_SHADER, vertexSrc)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSrc)
        val program = GLES20.glCreateProgram()
        if (program == 0) throw GlException("glCreateProgram returned 0")
        GLES20.glAttachShader(program, vs)
        GLES20.glAttachShader(program, fs)
        GLES20.glLinkProgram(program)
        val status = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw GlException("Program link failed: $log")
        }
        // Shaders can be deleted once linked; the program keeps its own copy.
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        id = program
    }

    fun use() = GLES20.glUseProgram(id)

    /** Cached uniform location; -1 (GL's "not found") is cached too so a typo is not re-queried per frame. */
    fun uniform(name: String): Int = uniforms.getOrPut(name) { GLES20.glGetUniformLocation(id, name) }

    /** Cached attribute location. */
    fun attribute(name: String): Int = attribs.getOrPut(name) { GLES20.glGetAttribLocation(id, name) }

    fun release() {
        if (id != 0) GLES20.glDeleteProgram(id)
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        if (shader == 0) throw GlException("glCreateShader returned 0")
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            val kind = if (type == GLES20.GL_VERTEX_SHADER) "vertex" else "fragment"
            throw GlException("$kind shader compile failed: $log")
        }
        return shader
    }
}
