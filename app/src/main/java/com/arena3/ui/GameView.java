package com.arena3.ui;

import android.content.Context;
import android.view.MotionEvent;

import android.opengl.GLSurfaceView;

/** GL surface that forwards touches to the on-screen controls. */
public final class GameView extends GLSurfaceView {

    private TouchControls controls;

    public GameView(Context context) {
        super(context);
        setEGLContextClientVersion(3);
        setEGLConfigChooser(8, 8, 8, 0, 24, 0);
        setPreserveEGLContextOnPause(true);
    }

    public void setControls(TouchControls controls) {
        this.controls = controls;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (controls == null) return false;
        return controls.onTouch(event);
    }
}
