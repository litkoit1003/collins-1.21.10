package org.sawiq.collins.fabric.client.video;

public final class VideoConfig {

    private VideoConfig() {}

    // Было 96. Это можно поднять, но без фанатизма.
    public static final int PX_PER_BLOCK = 128;

    public static final int MIN_W = 256;
    public static final int MIN_H = 144;

    // Оставляем 720p как потолок для CPU-аплоада через setColorArgb.
    public static final int MAX_W = 1980;
    public static final int MAX_H = 1080;

    // Целевой FPS для аплоада (мы ограничим аплоад кадра в VideoScreen)
    public static final int TARGET_FPS = 30;

    public static final double PLANE_EPS = 0.012;
}
