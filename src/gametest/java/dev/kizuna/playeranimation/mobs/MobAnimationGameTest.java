package dev.kizuna.playeranimation.mobs;

import com.mojang.brigadier.suggestion.Suggestion;
import dev.kizuna.playeranimation.server.ServerPlayerAnimations;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class MobAnimationGameTest implements FabricGameTest {
    private static final int ELAPSED_TICKS = 4;

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void vindicatorActionKeepsEquipmentAndOriginalStart(GameTestHelper context) {
        // 正式同步入口使用卫道士、真实装备和历史起点，停止后不再补发旧功法。
        var entity = context.spawnWithNoFreeWill(EntityType.VINDICATOR, new BlockPos(0, 1, 0));
        entity.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        entity.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        entity.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.DIAMOND_CHESTPLATE));
        long start = Math.max(0, context.getLevel().getGameTime() - ELAPSED_TICKS);
        String requestId = MobAnimationServer.playFrom(entity, "attack_main", start);
        var request = MobAnimationServer.snapshot(entity).get(0);
        context.assertTrue(request.getRequestId().equals(requestId), "同步请求身份丢失");
        context.assertTrue(request.getPlay().getStartTick() == start, "历史起点被重置");
        context.assertTrue(entity.getMainHandItem().is(Items.IRON_AXE), "动画替换了主手装备");
        context.assertTrue(entity.getOffhandItem().is(Items.SHIELD), "动画替换了副手装备");
        MobAnimationServer.stop(entity, "attack_main");
        context.assertTrue(MobAnimationServer.snapshot(entity).isEmpty(), "停止后仍有同步请求");
        context.succeed();
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void merchantInteractionStartsAndStopsPaAtBoundary(GameTestHelper context) {
        // 客户关系由服务端权威持有，旁观客户端通过显式播放与停止看到交易动作。
        var merchant = context.spawnWithNoFreeWill(EntityType.VILLAGER, new BlockPos(0, 1, 0));
        var customer = context.makeMockServerPlayerInLevel();
        try {
            merchant.setTradingPlayer(customer);
            var request = MobAnimationServer.snapshot(merchant).get(0).getPlay();
            context.assertTrue(request.getAnimationId().equals("use_item") && request.getLoop(), "交易未播放循环交互");
            merchant.setTradingPlayer(null);
            context.assertTrue(MobAnimationServer.snapshot(merchant).isEmpty(), "交易结束后未恢复自动层");
            context.succeed();
        } finally {
            context.getLevel().getServer().getPlayerList().remove(customer);
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void playTrackingQueueAndUnloadCleanup(GameTestHelper context) throws ReflectiveOperationException {
        // 通过正式服务端入口登记，再触发 Fabric 追踪与实体卸载生命周期。
        var entity = context.spawnWithNoFreeWill(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        var viewer = context.makeMockServerPlayerInLevel();
        var server = context.getLevel().getServer();
        MobAnimationSyncState state = (MobAnimationSyncState) field("STATE").get(null);
        Set<?> pending = (Set<?>) field("PENDING").get(null);
        try {
            String id = MobAnimationServer.play(entity, "dodge_forward");
            var snapshot = state.snapshot(entity.getUUID());
            context.assertTrue(snapshot.size() == 1, "播放必须登记活动请求");
            context.assertTrue(snapshot.get(0).getRequestId().equals(id), "追踪补发必须保留请求 ID");
            context.assertTrue(snapshot.get(0).getPlay().getEntityId() == entity.getId(), "必须使用实际运行时 ID");
            context.assertTrue(snapshot.get(0).getPlay().hasStartTick(), "补发必须携带原起点");
            EntityTrackingEvents.STOP_TRACKING.invoker().onStopTracking(entity, viewer);
            int before = pending.size();
            EntityTrackingEvents.START_TRACKING.invoker().onStartTracking(entity, viewer);
            context.assertTrue(pending.size() == before + 1, "开始追踪必须排入补发队列");
            EntityTrackingEvents.STOP_TRACKING.invoker().onStopTracking(entity, viewer);
            context.assertTrue(pending.size() == before, "结束追踪必须取消补发");
            EntityTrackingEvents.START_TRACKING.invoker().onStartTracking(entity, viewer);
            entity.discard();
            context.runAtTickTime(1, () -> {
                context.assertTrue(state.snapshot(entity.getUUID()).isEmpty(), "卸载必须清理活动请求");
                context.assertTrue(pending.size() <= before, "卸载必须清理追踪补发");
                context.succeed();
            });
        } finally {
            server.getPlayerList().remove(viewer);
        }
    }

    @GameTest(template = FabricGameTest.EMPTY_STRUCTURE)
    public void playAnimationArgumentCompletesFromPaIndex(GameTestHelper context) {
        // 补全不再写死几项，PA 内置索引里的每个 id 都要列出，含原先写死的四项。
        List<String> ids = ServerPlayerAnimations.animationIds();
        context.assertTrue(ids.containsAll(List.of("walk", "run", "dodge_forward", "meditate", "attack_main")),
                "PA 内置索引应包含原先写死的四项与原版动作，实际 " + ids);
        var server = context.getLevel().getServer();
        var dispatcher = server.getCommands().getDispatcher();
        var parsed = dispatcher.parse("kizuna_anim_mob play @s ", server.createCommandSourceStack());
        List<String> suggested = dispatcher.getCompletionSuggestions(parsed).join().getList().stream()
                .map(Suggestion::getText)
                .toList();
        context.assertTrue(suggested.size() == ids.size() && Set.copyOf(suggested).equals(Set.copyOf(ids)),
                "动画参数补全应与 PA 内置索引一致，实际 " + suggested);
        context.succeed();
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = MobAnimationServer.class.getDeclaredField(name);
        field.setAccessible(true /* flag */);
        return field;
    }
}
