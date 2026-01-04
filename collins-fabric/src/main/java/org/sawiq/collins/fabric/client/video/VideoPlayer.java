package org.sawiq.collins.fabric.client.video;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;

import javax.sound.sampled.LineUnavailableException;
import java.awt.image.BufferedImage;
import java.util.concurrent.locks.LockSupport;

public final class VideoPlayer {

    public interface FrameSink {
        void initVideo(int videoW, int videoH, int targetW, int targetH);
        void onFrame(int[] argb, int w, int h);
        void onStop();
    }

    private final FrameSink sink;

    private volatile boolean running;
    private Thread thread;

    private volatile long startPosMs = 0;
    private volatile float gain = 1.0f;
    private volatile VideoAudioPlayer currentAudio;

    public VideoPlayer(FrameSink sink) {
        this.sink = sink;
    }

    public void start(String url, int blocksW, int blocksH, boolean loop) {
        start(url, blocksW, blocksH, loop, 0L, 1.0f);
    }

    public void start(String url, int blocksW, int blocksH, boolean loop, long startPosMs, float gain) {
        stop();
        this.startPosMs = Math.max(0L, startPosMs);
        this.gain = Math.max(0f, gain);

        running = true;
        thread = new Thread(() -> runLoop(url, blocksW, blocksH, loop), "Collins-VideoPlayer");
        thread.setDaemon(true);
        thread.start();
    }

    public void setGain(float gain) {
        float g = Math.max(0f, gain);
        this.gain = g;

        VideoAudioPlayer a = currentAudio;
        if (a != null) a.setGain(g);
    }

    public void stop() {
        running = false;

        Thread t = thread;
        if (t != null) t.interrupt();
        thread = null;

        VideoAudioPlayer a = currentAudio;
        if (a != null) a.shutdownNow();
    }

    private void runLoop(String url, int blocksW, int blocksH, boolean loop) {
        try {
            boolean first = true;
            while (running) {
                long seekMs = first ? startPosMs : 0L;
                first = false;

                playOnce(url, blocksW, blocksH, seekMs);

                if (!loop) break;
            }
        } finally {
            currentAudio = null;
            sink.onStop();
        }
    }

    private void playOnce(String url, int blocksW, int blocksH, long seekMs) {
        int videoW, videoH;

        // 1) мета (ширина/высота)
        try (FFmpegFrameGrabber meta = new FFmpegFrameGrabber(url)) {
            meta.start();
            videoW = meta.getImageWidth();
            videoH = meta.getImageHeight();
            meta.stop();
        } catch (Exception e) {
            System.out.println("[Collins] FFmpeg meta failed: " + e.getMessage());
            return;
        }

        if (videoW <= 0 || videoH <= 0) {
            System.out.println("[Collins] Video has invalid size: " + videoW + "x" + videoH);
            return;
        }

        // 2) target размер
        VideoSizeUtil.Size target = VideoSizeUtil.pick(blocksW, blocksH, videoW, videoH);
        sink.initVideo(videoW, videoH, target.w(), target.h());

        // 3) декод
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(url)) {
            grabber.setImageWidth(target.w());
            grabber.setImageHeight(target.h());
            grabber.start();

            if (seekMs > 0) {
                try { grabber.setTimestamp(seekMs * 1000L); }
                catch (Exception e) { System.out.println("[Collins] seek failed: " + e.getMessage()); }
            }

            int sampleRate = grabber.getSampleRate() > 0 ? grabber.getSampleRate() : 48000;
            int channels = grabber.getAudioChannels() > 0 ? grabber.getAudioChannels() : 2;
            channels = Math.min(2, channels);

            double fps = grabber.getVideoFrameRate();
            if (fps <= 0) fps = 30.0;

            Java2DFrameConverter converter = new Java2DFrameConverter();

            // пул буферов вместо clone()
            final int bufferCount = 4;
            final int pixels = target.w() * target.h();
            final int[][] buffers = new int[bufferCount][pixels];
            int bufIndex = 0;

            boolean hasAnyAudio = false;
            long wallStartNs = 0;
            double firstVideoSec = 0.0;
            boolean wallStarted = false;

            try (VideoAudioPlayer audio = new VideoAudioPlayer(sampleRate, channels)) {
                currentAudio = audio;
                audio.setGain(gain);

                long baseVideoTsUs = Long.MIN_VALUE;
                long videoFrameIndex = 0;

                while (running) {
                    Frame frame = grabber.grab();
                    if (frame == null) break;

                    if (frame.samples != null) {
                        hasAnyAudio = true;
                        audio.writeSamples(frame.samples, channels);
                        continue;
                    }

                    if (frame.image == null) continue;

                    videoFrameIndex++;

                    long tsUs = frame.timestamp;
                    double videoSec;
                    if (tsUs > 0) {
                        if (baseVideoTsUs == Long.MIN_VALUE) baseVideoTsUs = tsUs;
                        videoSec = (tsUs - baseVideoTsUs) / 1_000_000.0;
                    } else {
                        videoSec = (videoFrameIndex - 1) / fps;
                    }

                    // если нет аудио — синхра по wall-clock
                    if (!hasAnyAudio) {
                        if (!wallStarted) {
                            wallStarted = true;
                            wallStartNs = System.nanoTime();
                            firstVideoSec = videoSec;
                        }

                        double targetSec = videoSec - firstVideoSec;
                        double wallSec = (System.nanoTime() - wallStartNs) / 1_000_000_000.0;
                        double ahead = targetSec - wallSec;

                        while (running && ahead > 0.002) {
                            long ns = (long) ((ahead - 0.002) * 1_000_000_000.0);
                            if (ns > 5_000_000L) ns = 5_000_000L;
                            if (ns > 0) LockSupport.parkNanos(ns);
                            if (Thread.interrupted()) return;

                            wallSec = (System.nanoTime() - wallStartNs) / 1_000_000_000.0;
                            ahead = targetSec - wallSec;
                        }
                    }

                    BufferedImage img = converter.getBufferedImage(frame);
                    if (img == null) continue;

                    int[] out = buffers[bufIndex];
                    bufIndex++;
                    if (bufIndex >= bufferCount) bufIndex = 0;

                    // ВАЖНО: всегда читаем ровно target.w/target.h
                    int w = target.w();
                    int h = target.h();
                    img.getRGB(0, 0, w, h, out, 0, target.w());

                    // ARGB -> ABGR (swap R/B) для быстрой заливки NativeImage через память
                    for (int i = 0; i < pixels; i++) {
                        int c = out[i];
                        out[i] = (c & 0xFF00FF00) | ((c & 0x00FF0000) >>> 16) | ((c & 0x000000FF) << 16);
                    }

                    sink.onFrame(out, target.w(), target.h());
                }

            } catch (LineUnavailableException e) {
                System.out.println("[Collins] Audio init failed: " + e.getMessage());
            } finally {
                currentAudio = null;
                try { grabber.stop(); } catch (Exception ignored) {}
                System.out.println("[Collins] Decoder ended (running=" + running + ") url=" + url);
            }

        } catch (Exception e) {
            System.out.println("[Collins] FFmpeg decode failed: " + e.getMessage());
        }
    }
}
