package org.sawiq.collins.fabric.client.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import org.sawiq.collins.fabric.client.util.TimeFormatUtil;
import org.sawiq.collins.fabric.client.video.VideoScreen;
import org.sawiq.collins.fabric.client.video.VideoScreenManager;

public final class CollinsClientCommands {

    private static final int GREEN = 0x00FF00;
    private static final Text PREFIX = Text.literal("[Collins-Fabric] ").setStyle(Style.EMPTY.withColor(GREEN));

    private CollinsClientCommands() {
    }

    public static void init() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("collinsc")
                    .then(ClientCommandManager.literal("time")
                            .executes(ctx -> showTimeline(null))
                            .then(ClientCommandManager.argument("screen", StringArgumentType.word())
                                    .executes(ctx -> showTimeline(StringArgumentType.getString(ctx, "screen"))))));
        });
    }

    private static int showTimeline(String screenName) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return 0;

        VideoScreen screen = null;
        if (screenName != null && !screenName.isBlank()) {
            screen = VideoScreenManager.getByName(screenName);
        } else {
            screen = VideoScreenManager.findNearestPlaying(client.player.getEntityPos());
        }

        if (screen == null) {
            client.player.sendMessage(PREFIX.copy().append(Text.literal("No active screen").formatted(Formatting.RED)), false);
            return 0;
        }

        long serverNowMs = VideoScreenManager.estimateServerNowMs();
        long posMs = screen.currentPosMsForDisplay(serverNowMs);
        long durMs = screen.durationMs();

        String pos = TimeFormatUtil.formatMs(posMs);
        String msg;
        if (durMs > 0) {
            msg = screen.state().name() + ": " + pos + " / " + TimeFormatUtil.formatMs(durMs);
        } else {
            msg = screen.state().name() + ": " + pos;
        }

        client.player.sendMessage(PREFIX.copy().append(Text.literal(msg).setStyle(Style.EMPTY.withColor(GREEN))), false);
        return Command.SINGLE_SUCCESS;
    }
}
