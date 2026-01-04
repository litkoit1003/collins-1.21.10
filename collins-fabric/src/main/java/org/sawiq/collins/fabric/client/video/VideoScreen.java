package org.sawiq.collins.fabric.client.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.system.MemoryUtil;
import org.sawiq.collins.fabric.client.state.ScreenState;
import org.sawiq.collins.fabric.mixin.NativeImageAccessor;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class VideoScreen implements VideoPlayer.FrameSink {

    private ScreenState state;

    private Identifier texId;
    private NativeImageBackedTexture texture;

    private VideoPlayer player;

    private int texW, texH;

    private boolean started = false;
    private String startedUrl = "";
    private float lastGain = -1f;

    // ===== Очередь от декодера -> в tick (только последнее значение) =====
    private record InitReq(int videoW, int videoH, int targetW, int targetH) {}
    private record FrameReq(int[] abgr, int w, int h) {} // ВАЖНО: ожидаем ABGR (см. VideoPlayer)

    private final AtomicReference<InitReq> pendingInit = new AtomicReference<>(null);
    private final AtomicReference<FrameReq> pendingFrame = new AtomicReference<>(null);
    private final AtomicBoolean pendingStop = new AtomicBoolean(false);
    // ====================================================================

    // Ограничиваем upload, чтобы не лагать на больших текстурах
    private long lastUploadNs = 0;
    private static final long MIN_UPLOAD_INTERVAL_NS =
            1_000_000_000L / Math.max(1, VideoConfig.TARGET_FPS);

    public VideoScreen(ScreenState state) {
        this.state = state;
    }

    public ScreenState state() { return state; }

    public void updateState(ScreenState newState) { this.state = newState; }

    public boolean hasTexture() { return texture != null && texId != null; }

    public Identifier textureId() { return texId; }

    public void tickPlayback(Vec3d playerPos, int radiusBlocks, float globalVolume, long serverNowMs) {
        // 1) применяем всё, что пришло из декодера (ТОЛЬКО тут)
        applyPendingStop();
        applyPendingInit();
        uploadPendingFrameFast();

        // 2) управление воспроизведением
        if (state.url() == null || state.url().isEmpty() || !state.playing()) {
            stop();
            return;
        }

        long posMs = currentVideoPosMs(serverNowMs);
        float gain = Math.max(0f, globalVolume) * Math.max(0f, state.volume());

        if (player == null) player = new VideoPlayer(this);

        if (!started || !startedUrl.equals(state.url())) {
            started = true;
            startedUrl = state.url();
            lastGain = gain;
            player.start(state.url(), state.blocksW(), state.blocksH(), state.loop(), posMs, gain);
            return;
        }

        if (Math.abs(gain - lastGain) > 0.001f) {
            lastGain = gain;
            player.setGain(gain);
        }
    }

    private void applyPendingStop() {
        if (!pendingStop.getAndSet(false)) return;

        started = false;
        startedUrl = "";
        lastGain = -1f;

        pendingFrame.set(null);
    }

    private void applyPendingInit() {
        InitReq req = pendingInit.getAndSet(null);
        if (req == null) return;

        this.texW = req.targetW();
        this.texH = req.targetH();

        if (texId == null) {
            texId = Identifier.of("collins", "screen/" + state.name().toLowerCase());
        }

        if (texture != null) {
            texture.close();
            texture = null;
        }

        texture = new NativeImageBackedTexture("collins:" + texId, texW, texH, true);
        MinecraftClient.getInstance().getTextureManager().registerTexture(texId, texture);

        // Быстро заливаем цветом (без двойных циклов)
        NativeImage img = texture.getImage();
        if (img != null) {
            img.fillRect(0, 0, texW, texH, 0xFFFF00FF);
        }

        texture.upload();

        pendingFrame.set(null);
        lastUploadNs = 0;

        System.out.println("[Collins] initVideo " + texW + "x" + texH + " texId=" + texId);
    }

    /**
     * Быстрая заливка: копируем весь кадр одним IntBuffer.put() в pointer NativeImage
     * и делаем один upload (вместо миллионов setColorArgb). [page:1]
     */
    private void uploadPendingFrameFast() {
        if (texture == null) return;

        long now = System.nanoTime();
        if (now - lastUploadNs < MIN_UPLOAD_INTERVAL_NS) return;

        FrameReq fr = pendingFrame.getAndSet(null);
        if (fr == null) return;

        if (fr.w() != texW || fr.h() != texH) return;

        NativeImage img = texture.getImage();
        if (img == null) return;

        int[] abgr = fr.abgr();
        int pixels = texW * texH;

        long ptr = ((NativeImageAccessor) (Object) img).collins$getPointer();
        IntBuffer dst = MemoryUtil.memIntBuffer(ptr, pixels);
        dst.put(abgr, 0, pixels);

        texture.upload();
        lastUploadNs = now;
    }

    private long currentVideoPosMs(long serverNowMs) {
        long base = Math.max(0L, state.basePosMs());
        if (serverNowMs <= 0 || state.startEpochMs() <= 0) return base;
        return base + Math.max(0L, serverNowMs - state.startEpochMs());
    }

    public void stop() {
        if (player != null) player.stop();

        started = false;
        startedUrl = "";
        lastGain = -1f;

        pendingFrame.set(null);
    }

    // ===== FrameSink: эти методы могут вызываться ИЗ ДЕКОДЕР-ПОТОКА =====

    @Override
    public void initVideo(int videoW, int videoH, int targetW, int targetH) {
        pendingInit.set(new InitReq(videoW, videoH, targetW, targetH));
    }

    @Override
    public void onFrame(int[] abgr, int w, int h) {
        if (abgr == null) return;
        pendingFrame.set(new FrameReq(abgr, w, h));
    }

    @Override
    public void onStop() {
        pendingStop.set(true);
    }

    public int texW() { return texW; }
    public int texH() { return texH; }
}
