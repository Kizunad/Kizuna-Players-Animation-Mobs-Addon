package dev.kizuna.playeranimation.mobs;

import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.illager.Vindicator;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** 完整装配下观察真正的类人 AI、功法表现和 PA 网络播放。 */
final class MobTechniqueClientScene {
    private static final int TIMEOUT_TICKS = 240;
    private static final double TARGET_DISTANCE = 2;
    private static final int CAPTURE_DELAY_TICKS = 6;
    private static final Set<String> DOOR_CLIPS = Set.of(
            "door_breaker_knock", "door_breaker_cleave", "door_breaker_ram");
    private final CompletableFuture<Integer> spawned = new CompletableFuture<>();
    private volatile long startedAt = -1;
    private Vindicator actor;
    private int ticks;
    private volatile int targetId;
    private volatile String animation;
    private volatile String observedClips = "[]";

    MobTechniqueClientScene(Minecraft client, Vec3 position) {
        // 通过公开管理命令出生，不绕过档位初始化、学卷或原版 AI 决策。
        MinecraftServer server = client.getSingleplayerServer();
        ServerTickEvents.END_SERVER_TICK.register(this::observe);
        server.execute(() -> spawn(server, position));
    }

    static void cleanWorld(MinecraftServer server) {
        // 独立测试世界可能保留上次客户端保存的实体；清理夹具，避免旧卫道士遮挡或被误选。
        var world = server.overworld();
        world.getGameRules().set(GameRules.SPAWN_MOBS, false /* value */, server);
        var unwanted = new ArrayList<Entity>();
        for (var entity : world.getAllEntities()) {
            if (entity instanceof ServerPlayer player) {
                player.setGameMode(GameType.SPECTATOR);
            } else {
                unwanted.add(entity);
            }
        }
        unwanted.forEach(Entity::discard);
    }

    private void spawn(MinecraftServer server, Vec3 position) {
        // 异常交回客户端测试线程，使缺接线与出生失败成为明确失败。
        try {
            server.setDifficulty(Difficulty.NORMAL, true /* forceUpdate */);
            var world = server.overworld();
            var previous = world.getEntities(EntityTypes.VINDICATOR, entity -> true).stream()
                    .map(Entity::getUUID)
                    .collect(Collectors.toSet());
            var source = server.createCommandSourceStack().withLevel(world).withPosition(position);
            server.getCommands().performPrefixedCommand(source, "meridian humanoid spawn vindicator true_qi ~ ~ ~");
            // 26.2 的命令执行不再返回结果，改以命令点附近是否多出新卫道士判断出生成功。
            var spawnedActors = world.getEntities(EntityTypes.VINDICATOR,
                    AABB.ofSize(position, 1, TARGET_DISTANCE, 1), entity -> !previous.contains(entity.getUUID()));
            if (spawnedActors.isEmpty()) {
                throw new IllegalStateException("真炁卫道士出生命令失败");
            }
            actor = spawnedActors.get(0);
            actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            actor.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            actor.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
            // 铁傀儡属于卫道士的原版敌对目标，且有足够生命等待完整起手。
            var opponent = EntityTypes.IRON_GOLEM.create(world, EntitySpawnReason.COMMAND);
            opponent.snapTo(position.x, position.y, position.z + TARGET_DISTANCE, 0, 0);
            opponent.setNoAi(true /* aiDisabled */);
            world.addFreshEntity(opponent);
            targetId = opponent.getId();
            actor.setTarget(opponent);
            spawned.complete(actor.getId());
        } catch (RuntimeException failure) {
            spawned.completeExceptionally(failure);
        }
    }

    private void observe(MinecraftServer server) {
        // 只接受正式功法宿主写入的同步请求，测试本身不调用 play 或伪造施放。
        if (actor == null || startedAt >= 0) {
            return;
        }
        var requests = MobAnimationServer.snapshot(actor);
        observedClips = requests.stream().map(request -> request.getPlay().getAnimationId()).toList()
                + "; health=" + actor.getHealth() + "; position=" + actor.position()
                + "; target=" + actor.getTarget() + "; noAi=" + actor.isNoAi();
        for (var request : requests) {
            var play = request.getPlay();
            if (DOOR_CLIPS.contains(play.getAnimationId())) {
                animation = play.getAnimationId();
                startedAt = play.getStartTick();
                actor.setNoAi(true /* aiDisabled */);
            }
        }
    }

    boolean ready(Minecraft client, MobRecordingHost recording) {
        // 留出少量淡入时间，同时要求客户端实际加载了服务端出生的实体。
        if (++ticks > TIMEOUT_TICKS) {
            throw new IllegalStateException("未观察到真炁卫道士的 PA 功法播放：" + observedClips);
        }
        if (!spawned.isDone()) {
            return false;
        }
        int id = spawned.join();
        var target = client.level.getEntity(targetId);
        if (target != null) {
            target.setInvisible(true /* invisible */);
        }
        var play = recording.explicit(id);
        return startedAt >= 0 && client.level.getEntity(id) != null
                && client.level.getGameTime() >= startedAt + CAPTURE_DELAY_TICKS
                && play != null && play.getAnimationId().equals(animation)
                && play.getStartTick() == startedAt;
    }

    boolean observed() {
        // 完成记录区分整合服施放与前面的客户端字段夹具。
        return startedAt >= 0;
    }
}
