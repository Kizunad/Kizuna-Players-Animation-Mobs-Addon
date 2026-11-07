package dev.kizuna.playeranimation.mobs;

import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.level.GameType;

/** 从真实使用请求记录收纳袋倒出物品时的第一人称持物。 */
final class BundleClientScene {
    private static final int TIMEOUT_TICKS = 100;
    private static final int CAPTURE_INTERVAL = 3;
    private static final int FRAME_COUNT = 4;
    private static final float LOOK_DOWN_DEGREES = 25;
    private final CompletableFuture<Void> prepared;
    private int ticks;
    private int usingTicks;
    private int frames;
    private boolean using;

    BundleClientScene(Minecraft client) {
        // 服务端持物通过正常同步抵达客户端，再发送原版使用请求。
        var playerId = client.player.getUUID();
        prepared = client.getSingleplayerServer().submit(() -> {
            var player = client.getSingleplayerServer().getPlayerList().getPlayer(playerId);
            player.setGameMode(GameType.CREATIVE);
            var bundle = Items.BUNDLE.getDefaultInstance();
            var contents = List.of(new ItemStackTemplate(Items.STONE), new ItemStackTemplate(Items.DIRT),
                    new ItemStackTemplate(Items.GRANITE), new ItemStackTemplate(Items.DIORITE),
                    new ItemStackTemplate(Items.ANDESITE), new ItemStackTemplate(Items.COBBLESTONE));
            bundle.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(contents));
            player.setItemSlot(EquipmentSlot.MAINHAND, bundle);
            player.containerMenu.broadcastChanges();
        });
    }

    boolean ready(Minecraft client) {
        if (++ticks > TIMEOUT_TICKS) {
            throw new IllegalStateException("收纳袋使用或截图超时");
        }
        if (!prepared.isDone()) {
            return false;
        }
        prepared.join();
        if (!client.player.getMainHandItem().is(Items.BUNDLE) || client.player.isSpectator()) {
            return false;
        }
        client.setCameraEntity(client.player);
        if (client.gui.hud.isHidden()) {
            client.gui.hud.toggle();
        }
        client.player.setXRot(LOOK_DOWN_DEGREES);
        client.options.keyUse.setDown(true /* pressed */);
        if (!using) {
            client.gameMode.useItem(client.player, InteractionHand.MAIN_HAND);
            using = true;
        }
        if (!client.player.isUsingItem()) {
            throw new IllegalStateException("收纳袋未进入原版使用状态");
        }
        return ++usingTicks % CAPTURE_INTERVAL == 0;
    }

    void capture(Minecraft client, NativeImage image, Path directory) throws IOException {
        // 连续时间点保存各自真实渲染帧，不通过 scrub 伪造第一人称挥动。
        image.writeToFile(directory.resolve("bundle-first-person-" + frames + ".png"));
        frames++;
        if (complete()) {
            client.gui.hud.toggle();
            client.options.keyUse.setDown(false /* pressed */);
            client.gameMode.releaseUsingItem(client.player);
            var playerId = client.player.getUUID();
            client.getSingleplayerServer().execute(() ->
                    client.getSingleplayerServer().getPlayerList().getPlayer(playerId)
                            .setGameMode(GameType.SPECTATOR));
        }
    }

    boolean complete() {
        return frames == FRAME_COUNT;
    }
}
