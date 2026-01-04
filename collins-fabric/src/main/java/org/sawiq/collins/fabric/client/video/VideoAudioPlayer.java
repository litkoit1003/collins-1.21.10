package org.sawiq.collins.fabric.client.video;

import javax.sound.sampled.*;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

public final class VideoAudioPlayer implements AutoCloseable {

    private final int sampleRate;
    private final int channels;
    private final SourceDataLine line;

    private volatile float gain = 1.0f;

    public VideoAudioPlayer(int sampleRate, int channels) throws LineUnavailableException {
        this.sampleRate = sampleRate;
        this.channels = channels;

        AudioFormat fmt = new AudioFormat(sampleRate, 16, channels, true, false); // PCM 16-bit LE
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);

        this.line = (SourceDataLine) AudioSystem.getLine(info);
        this.line.open(fmt);
        this.line.start();
    }

    public void setGain(float gain) {
        this.gain = Math.max(0f, gain);
    }

    public void shutdownNow() {
        try { line.stop(); } catch (Exception ignored) {}
        try { line.flush(); } catch (Exception ignored) {}
        try { line.close(); } catch (Exception ignored) {}
    }

    public double timeSeconds() {
        return line.getMicrosecondPosition() / 1_000_000.0;
    }

    public void writeSamples(Buffer[] samples, int channelsWanted) {
        if (samples == null || samples.length == 0) return;

        // чаще всего JavaCV даёт ShortBuffer
        if (samples[0] instanceof ShortBuffer) {
            byte[] pcm = toPcm16le(samples, channelsWanted);
            if (pcm != null && pcm.length > 0) {
                line.write(pcm, 0, pcm.length);
            }
        }
    }

    private byte[] toPcm16le(Buffer[] samples, int channelsWanted) {
        float g = this.gain;

        if (channelsWanted <= 1) {
            ShortBuffer sb = ((ShortBuffer) samples[0]).duplicate();
            ByteBuffer bb = ByteBuffer.allocate(sb.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);

            while (sb.hasRemaining()) {
                short s = sb.get();
                bb.putShort(scaleClamp(s, g));
            }
            return bb.array();
        }

        // stereo: либо planar (L,R), либо interleaved
        if (samples.length >= 2 && samples[0] instanceof ShortBuffer && samples[1] instanceof ShortBuffer) {
            ShortBuffer l = ((ShortBuffer) samples[0]).duplicate();
            ShortBuffer r = ((ShortBuffer) samples[1]).duplicate();

            int n = Math.min(l.remaining(), r.remaining());
            ByteBuffer bb = ByteBuffer.allocate(n * 2 * 2).order(ByteOrder.LITTLE_ENDIAN);

            for (int i = 0; i < n; i++) {
                bb.putShort(scaleClamp(l.get(), g));
                bb.putShort(scaleClamp(r.get(), g));
            }
            return bb.array();
        }

        // interleaved
        ShortBuffer sb = ((ShortBuffer) samples[0]).duplicate();
        ByteBuffer bb = ByteBuffer.allocate(sb.remaining() * 2).order(ByteOrder.LITTLE_ENDIAN);

        while (sb.hasRemaining()) {
            bb.putShort(scaleClamp(sb.get(), g));
        }
        return bb.array();
    }

    private static short scaleClamp(short s, float g) {
        if (g == 1.0f) return s;

        int v = Math.round(s * g);
        if (v > Short.MAX_VALUE) v = Short.MAX_VALUE;
        if (v < Short.MIN_VALUE) v = Short.MIN_VALUE;
        return (short) v;
    }

    @Override
    public void close() {
        shutdownNow();
    }
}
