package com.arena3.gl;

import android.opengl.GLES30;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/** Compiled shader pair with cached uniform locations. */
public final class ShaderProgram {

    private static final String TAG = "Arena3";

    public final int handle;
    private final Map<String, Integer> uniforms = new HashMap<>();

    public ShaderProgram(String vertexSource, String fragmentSource, String name) {
        int vs = compile(GLES30.GL_VERTEX_SHADER, vertexSource, name + ".vs");
        int fs = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource, name + ".fs");
        handle = GLES30.glCreateProgram();
        GLES30.glAttachShader(handle, vs);
        GLES30.glAttachShader(handle, fs);
        GLES30.glLinkProgram(handle);

        int[] status = new int[1];
        GLES30.glGetProgramiv(handle, GLES30.GL_LINK_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "link failed for " + name + ": " + GLES30.glGetProgramInfoLog(handle));
        }
        // The program keeps its own copy once linked.
        GLES30.glDeleteShader(vs);
        GLES30.glDeleteShader(fs);
    }

    private static int compile(int type, String source, String name) {
        int shader = GLES30.glCreateShader(type);
        GLES30.glShaderSource(shader, source);
        GLES30.glCompileShader(shader);
        int[] status = new int[1];
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            Log.e(TAG, "compile failed for " + name + ": " + GLES30.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    public void use() {
        GLES30.glUseProgram(handle);
    }

    public int uniform(String name) {
        Integer cached = uniforms.get(name);
        if (cached != null) return cached;
        int location = GLES30.glGetUniformLocation(handle, name);
        uniforms.put(name, location);
        return location;
    }

    public void set(String name, float v) {
        GLES30.glUniform1f(uniform(name), v);
    }

    public void set(String name, int v) {
        GLES30.glUniform1i(uniform(name), v);
    }

    public void set(String name, float x, float y) {
        GLES30.glUniform2f(uniform(name), x, y);
    }

    public void set(String name, float x, float y, float z) {
        GLES30.glUniform3f(uniform(name), x, y, z);
    }

    public void setMatrix(String name, float[] m) {
        GLES30.glUniformMatrix4fv(uniform(name), 1, false, m, 0);
    }

    public void setVec4Array(String name, float[] data, int count) {
        GLES30.glUniform4fv(uniform(name), count, data, 0);
    }

    public void setVec3Array(String name, float[] data, int count) {
        GLES30.glUniform3fv(uniform(name), count, data, 0);
    }

    public void dispose() {
        GLES30.glDeleteProgram(handle);
    }
}
