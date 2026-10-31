package dev.kizuna.playeranimation.mobs;

import com.google.protobuf.MessageLite;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationRequest;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Play;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Stop;
import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/** 服务端按追踪关系同步任意 LivingEntity 的 PA 动画。 */
public final class MobAnimationServer implements ModInitializer {
    private static final int COMMAND_PERMISSION_LEVEL = 2;
    private static final MobAnimationSyncState STATE = new MobAnimationSyncState();
    private static final Set<TrackingPair> PENDING = new HashSet<>();
    private record TrackingPair(UUID target, UUID viewer) { }
    private enum LoopMode { RESOURCE, ONCE, REPEAT }

    @Override
    public void onInitialize() {
        EntityTrackingEvents.START_TRACKING.register((entity, viewer) -> {
            if (entity instanceof LivingEntity living) {
                PENDING.add(new TrackingPair(living.getUUID(), viewer.getUUID()));
            }
        });
        EntityTrackingEvents.STOP_TRACKING.register((entity, viewer) ->
                PENDING.remove(new TrackingPair(entity.getUUID(), viewer.getUUID())));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof LivingEntity living) {
                clearEntity(living.getUUID());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> {
            UUID viewer = handler.player.getUUID();
            PENDING.removeIf(pair -> pair.viewer().equals(viewer));
        }));
        ServerTickEvents.END_SERVER_TICK.register(MobAnimationServer::tick);
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            STATE.clear();
            PENDING.clear();
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> registerCommands(dispatcher));
    }

    public static String play(LivingEntity entity, String animation) {
        // 默认动作按世界当前 tick 开始。
        return playFrom(entity, animation, entity.level().getGameTime());
    }

    public static String playFrom(LivingEntity entity, String animation, long startTick) {
        // 功法重试及迟到效果保留原始起点，不把已过去的动作重新播一遍。
        return play(entity, animation, LoopMode.RESOURCE, startTick);
    }

    public static String repeat(LivingEntity entity, String animation) {
        // 持续交互按资源时长循环，由交互结束显式停止。
        return play(entity, animation, LoopMode.REPEAT, entity.level().getGameTime());
    }

    public static List<EntityAnimationRequest> snapshot(LivingEntity entity) {
        // 返回不可变的原始同步请求，诊断与宿主验证不会修改活动播放。
        requireServerThread(entity);
        return STATE.snapshot(entity.getUUID());
    }

    private static String play(LivingEntity entity, String animation, LoopMode loopMode) {
        // 命令路径以当前世界时间起播。
        return play(entity, animation, loopMode, entity.level().getGameTime());
    }

    private static String play(LivingEntity entity, String animation, LoopMode loopMode, long startTick) {
        // 先验证再登记，非法起点不能改变追踪补发状态。
        requireServerThread(entity);
        validateAnimation(animation);
        if (startTick < 0 || startTick > entity.level().getGameTime()) {
            throw new IllegalArgumentException("起播 tick 必须位于当前世界时间以内");
        }
        String requestId = UUID.randomUUID().toString();
        Play.Builder play = Play.newBuilder()
                .setEntityId(entity.getId())
                .setAnimationId(animation)
                .setStartTick(startTick);
        if (loopMode == LoopMode.REPEAT) {
            play.setLoop(true /* loop */);
        } else if (loopMode == LoopMode.ONCE) {
            play.setLoop(false /* loop */);
        }
        Play operation = play.build();
        EntityAnimationRequest request = EntityAnimationRequest.newBuilder()
                .setRequestId(requestId)
                .setPlay(operation)
                .build();
        checkSize(request);
        STATE.play(entity.getUUID(), request);
        broadcast(entity, MobAnimationPackets.PLAY, request);
        return requestId;
    }

    public static String stopAll(LivingEntity entity) {
        return stop(entity, null);
    }

    public static String stop(LivingEntity entity, String animation) {
        requireServerThread(entity);
        if (animation != null) {
            validateAnimation(animation);
        }
        String requestId = UUID.randomUUID().toString();
        Stop.Builder stop = Stop.newBuilder().setEntityId(entity.getId());
        if (animation == null) {
            stop.setAll(true /* all */);
        } else {
            stop.setAnimationId(animation);
        }
        Stop operation = stop.build();
        EntityAnimationRequest request = EntityAnimationRequest.newBuilder()
                .setRequestId(requestId)
                .setStop(operation)
                .build();
        checkSize(request);
        STATE.stop(entity.getUUID(), animation);
        broadcast(entity, MobAnimationPackets.STOP, request);
        return requestId;
    }

    private static void tick(MinecraftServer server) {
        for (TrackingPair pair : Set.copyOf(PENDING)) {
            LivingEntity target = findEntity(server, pair.target());
            ServerPlayer viewer = server.getPlayerList().getPlayer(pair.viewer());
            if (target == null || viewer == null || !viewer.connection.isAcceptingMessages()
                    || (target != viewer && !PlayerLookup.tracking(target).contains(viewer))) {
                PENDING.remove(pair);
                continue;
            }
            if (!ready(viewer)) {
                continue;
            }
            for (EntityAnimationRequest request : STATE.snapshot(pair.target())) {
                send(viewer, MobAnimationPackets.PLAY, request);
            }
            PENDING.remove(pair);
        }
    }

    private static void broadcast(LivingEntity target, ResourceLocation channel, MessageLite message) {
        Set<ServerPlayer> viewers = new HashSet<>(PlayerLookup.tracking(target));
        if (target instanceof ServerPlayer player) {
            viewers.add(player);
        }
        for (ServerPlayer viewer : viewers) {
            if (!viewer.connection.isAcceptingMessages() || !ready(viewer)) {
                PENDING.add(new TrackingPair(target.getUUID(), viewer.getUUID()));
                continue;
            }
            send(viewer, channel, message);
        }
    }

    private static void send(ServerPlayer viewer, ResourceLocation channel, MessageLite message) {
        ServerPlayNetworking.send(viewer, channel, MobAnimationPackets.write(message));
    }

    private static boolean ready(ServerPlayer viewer) {
        return ServerPlayNetworking.canSend(viewer, MobAnimationPackets.PLAY)
                && ServerPlayNetworking.canSend(viewer, MobAnimationPackets.STOP);
    }

    private static LivingEntity findEntity(MinecraftServer server, UUID id) {
        for (ServerLevel world : server.getAllLevels()) {
            Entity entity = world.getEntity(id);
            if (entity instanceof LivingEntity living) {
                return living;
            }
        }
        return null;
    }

    private static void clearEntity(UUID id) {
        STATE.stop(id, null);
        PENDING.removeIf(pair -> pair.target().equals(id));
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 生物与玩家播放同一套 PA 动画，补全直接取 PA 内置索引，不另维护列表。
        List<String> animations = ServerPlayerAnimations.animationIds();
        dispatcher.register(Commands.literal("kizuna_anim_mob")
                .requires(source -> source.hasPermission(COMMAND_PERMISSION_LEVEL))
                .then(Commands.literal("play")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .then(Commands.argument("animation", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(animations, builder))
                                        .executes(context -> playTargets(context, LoopMode.RESOURCE))
                                        .then(Commands.argument("loop", BoolArgumentType.bool())
                                                .executes(context -> {
                                                    boolean loop = BoolArgumentType.getBool(context, "loop");
                                                    LoopMode mode = loop ? LoopMode.REPEAT : LoopMode.ONCE;
                                                    return playTargets(context, mode);
                                                })))))
                .then(Commands.literal("stop")
                        .then(Commands.argument("targets", EntityArgument.entities())
                                .executes(MobAnimationServer::stopTargets))));
    }

    private static int playTargets(CommandContext<CommandSourceStack> context, LoopMode loopMode)
            throws CommandSyntaxException {
        String animation = StringArgumentType.getString(context, "animation");
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        int count = 0;
        for (Entity target : targets) {
            if (!(target instanceof LivingEntity living)) {
                continue;
            }
            play(living, animation, loopMode);
            count++;
        }
        if (count == 0) {
            context.getSource().sendFailure(Component.literal("目标中没有 LivingEntity"));
            return 0;
        }
        int targetCount = count;
        context.getSource().sendSuccess(() -> Component.literal("已播放动画 " + animation + "，目标 " + targetCount + " 个"),
                false /* broadcastToOps */);
        return count;
    }

    private static int stopTargets(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgument.getEntities(context, "targets");
        int count = 0;
        for (Entity target : targets) {
            if (target instanceof LivingEntity living) {
                stopAll(living);
                count++;
            }
        }
        int targetCount = count;
        context.getSource().sendSuccess(() -> Component.literal("已停止动画，目标 " + targetCount + " 个"),
                false /* broadcastToOps */);
        return count;
    }

    private static void validateAnimation(String animation) {
        if (animation == null || !animation.matches("[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("无效动画 id");
        }
    }

    private static void checkSize(MessageLite message) {
        if (message.getSerializedSize() > MobAnimationPackets.MAX_BYTES) {
            throw new IllegalArgumentException("生物动画包超过 32 KiB");
        }
    }

    private static void requireServerThread(LivingEntity entity) {
        MinecraftServer server = entity.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException("生物动画同步必须在服务端主线程调用");
        }
    }
}
