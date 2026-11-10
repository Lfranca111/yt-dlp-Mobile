package com.linfranca.ytdlpmobile;

/** A vertical drag owns scrolling until the next explicit touch/key/accessibility action. */
final class ScrollIntent {
    private final int slop;
    private float startX, startY;
    private boolean manual;
    ScrollIntent(int slop) { this.slop = slop; }
    void begin(float x, float y) { startX = x; startY = y; manual = false; }
    void move(float x, float y) {
        float dx = Math.abs(x - startX), dy = Math.abs(y - startY);
        if (dy > slop && dy > dx) manual = true;
    }
    void explicitAction() { manual = false; }
    boolean manualScroll() { return manual; }
}
