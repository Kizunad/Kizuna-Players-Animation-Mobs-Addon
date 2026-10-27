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
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

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
                PENDING.add(new TrackingPair(living.getUuid(), viewer.getUuid()));
            }
        });
        EntityTrackingEvents.STOP_TRACKING.register((entity, viewer) ->
                PENDING.remove(new TrackingPair(entity.getUuid(), viewer.getUuid())));
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, world) -> {
            if (entity instanceof LivingEntity living) {
                clearEntity(living.getUuid());
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> server.execute(() -> {
            UUID viewer = handler.player.getUuid();
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
        return play(entity, animation, LoopMode.RESOURCE);
    }

    private static String play(LivingEntity entity, String animation, LoopMode loopMode) {
        requireServerThread(entity);
        validateAnimation(animation);
        String requestId = UUID.randomUUID().toString();
        Play.Builder play = Play.newBuilder()
                .setEntityId(entity.getId())
                .setAnimationId(animation)
                .setStartTick(entity.getWorld().getTime());
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
        STATE.play(entity.getUuid(), request);
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
        STATE.stop(entity.getUuid(), animation);
        broadcast(entity, MobAnimationPackets.STOP, request);
        return requestId;
    }

    private static void tick(MinecraftServer server) {
        for (TrackingPair pair : Set.copyOf(PENDING)) {
            LivingEntity target = findEntity(server, pair.target());
            ServerPlayerEntity viewer = server.getPlayerManager().getPlayer(pair.viewer());
            if (target == null || viewer == null || !viewer.networkHandler.isConnectionOpen()
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

    private static void broadcast(LivingEntity target, Identifier channel, MessageLite message) {
        Set<ServerPlayerEntity> viewers = new HashSet<>(PlayerLookup.tracking(target));
        if (target instanceof ServerPlayerEntity player) {
            viewers.add(player);
        }
        for (ServerPlayerEntity viewer : viewers) {
            if (!viewer.networkHandler.isConnectionOpen() || !ready(viewer)) {
                PENDING.add(new TrackingPair(target.getUuid(), viewer.getUuid()));
                continue;
            }
            send(viewer, channel, message);
        }
    }

    private static void send(ServerPlayerEntity viewer, Identifier channel, MessageLite message) {
        ServerPlayNetworking.send(viewer, channel, MobAnimationPackets.write(message));
    }

    private static boolean ready(ServerPlayerEntity viewer) {
        return ServerPlayNetworking.canSend(viewer, MobAnimationPackets.PLAY)
                && ServerPlayNetworking.canSend(viewer, MobAnimationPackets.STOP);
    }

    private static LivingEntity findEntity(MinecraftServer server, UUID id) {
        for (ServerWorld world : server.getWorlds()) {
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

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("kizuna_anim_mob")
                .requires(source -> source.hasPermissionLevel(COMMAND_PERMISSION_LEVEL))
                .then(CommandManager.literal("play")
                        .then(CommandManager.argument("targets", EntityArgumentType.entities())
                                .then(CommandManager.argument("animation", StringArgumentType.word())
                                        .suggests((context, builder) -> CommandSource.suggestMatching(
                                                List.of("walk", "run", "dodge_forward", "meditate"), builder))
                                        .executes(context -> playTargets(context, LoopMode.RESOURCE))
                                        .then(CommandManager.argument("loop", BoolArgumentType.bool())
                                                .executes(context -> {
                                                    boolean loop = BoolArgumentType.getBool(context, "loop");
                                                    LoopMode mode = loop ? LoopMode.REPEAT : LoopMode.ONCE;
                                                    return playTargets(context, mode);
                                                })))))
                .then(CommandManager.literal("stop")
                        .then(CommandManager.argument("targets", EntityArgumentType.entities())
                                .executes(MobAnimationServer::stopTargets))));
    }

    private static int playTargets(CommandContext<ServerCommandSource> context, LoopMode loopMode)
            throws CommandSyntaxException {
        String animation = StringArgumentType.getString(context, "animation");
        Collection<? extends Entity> targets = EntityArgumentType.getEntities(context, "targets");
        int count = 0;
        for (Entity target : targets) {
            if (!(target instanceof LivingEntity living)) {
                continue;
            }
            play(living, animation, loopMode);
            count++;
        }
        if (count == 0) {
            context.getSource().sendError(Text.literal("目标中没有 LivingEntity"));
            return 0;
        }
        int targetCount = count;
        context.getSource().sendFeedback(() -> Text.literal("已播放动画 " + animation + "，目标 " + targetCount + " 个"),
                false /* broadcastToOps */);
        return count;
    }

    private static int stopTargets(CommandContext<ServerCommandSource> context) throws CommandSyntaxException {
        Collection<? extends Entity> targets = EntityArgumentType.getEntities(context, "targets");
        int count = 0;
        for (Entity target : targets) {
            if (target instanceof LivingEntity living) {
                stopAll(living);
                count++;
            }
        }
        int targetCount = count;
        context.getSource().sendFeedback(() -> Text.literal("已停止动画，目标 " + targetCount + " 个"),
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
        if (server == null || !server.isOnThread()) {
            throw new IllegalStateException("生物动画同步必须在服务端主线程调用");
        }
    }
}
