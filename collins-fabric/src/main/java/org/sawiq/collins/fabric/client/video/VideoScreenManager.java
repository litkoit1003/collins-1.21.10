package org.sawiq.collins.fabric.client.video;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;
import org.sawiq.collins.fabric.client.net.CollinsNet;
import org.sawiq.collins.fabric.client.state.ScreenState;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class VideoScreenManager {

    private VideoScreenManager() {}

    private static final Map<String, VideoScreen> SCREENS = new ConcurrentHashMap<>();

    public static Collection<VideoScreen> all() {
        return SCREENS.values();
    }

    public static void applySync(Map<String, ScreenState> incoming) {
        Set<String> keep = new HashSet<>(incoming.keySet());

        // 1) удалённые экраны
        for (String key : new ArrayList<>(SCREENS.keySet())) {
            if (!keep.contains(key)) {
                VideoScreen vs = SCREENS.remove(key);
                if (vs != null) {
                    System.out.println("[Collins] STOP by remove: key=" + key);
                    vs.stop();
                }
            }
        }

        // 2) обновляем существующие/создаём новые
        for (var e : incoming.entrySet()) {
            String key = e.getKey();
            ScreenState st = e.getValue();

            VideoScreen vs = SCREENS.get(key);
            if (vs == null) {
                vs = new VideoScreen(st);
                SCREENS.put(key, vs);
                System.out.println("[Collins] screen created: key=" + key + " name=" + st.name());
            } else {
                vs.updateState(st);
            }

            // 3) если сервер сказал остановить — останавливаем
            if (!st.playing() || st.url() == null || st.url().isEmpty()) {
                System.out.println("[Collins] STOP by sync: name=" + st.name()
                        + " playing=" + st.playing()
                        + " url=" + st.url());
                vs.stop();
            }
        }
    }

    public static void tick(MinecraftClient client) {
        PlayerEntity p = client.player;
        if (p == null) return;

        Vec3d pos = p.getPos();

        // ВАЖНО: используем server-sent настройки (а не тестовые константы)
        int radius = CollinsNet.HEAR_RADIUS;
        float globalVolume = CollinsNet.GLOBAL_VOLUME;

        long serverNowMs = estimateServerNowMs();

        for (VideoScreen s : SCREENS.values()) {
            s.tickPlayback(pos, radius, globalVolume, serverNowMs);
        }
    }

    private static long estimateServerNowMs() {
        long sn = CollinsNet.SERVER_NOW_MS;
        long cr = CollinsNet.CLIENT_RECV_MS;
        if (sn <= 0 || cr <= 0) return 0;
        return sn + (System.currentTimeMillis() - cr);
    }

    public static void stopAll() {
        System.out.println("[Collins] stopAll()");
        for (VideoScreen s : SCREENS.values()) s.stop();
        SCREENS.clear();
    }
}
