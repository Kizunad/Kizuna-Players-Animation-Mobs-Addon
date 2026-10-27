package dev.kizuna.playeranimation.mobs;

import java.lang.reflect.Field;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.fabricmc.fabric.api.networking.v1.EntityTrackingEvents;
import net.minecraft.entity.EntityType;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;

public final class MobAnimationGameTest implements FabricGameTest {
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void playTrackingQueueAndUnloadCleanup(TestContext context) throws ReflectiveOperationException {
        // 通过正式服务端入口登记，再触发 Fabric 追踪与实体卸载生命周期。
        var entity = context.spawnMob(EntityType.ZOMBIE, new BlockPos(0, 1, 0));
        var viewer = context.createMockCreativeServerPlayerInWorld();
        var server = context.getWorld().getServer();
        MobAnimationSyncState state = (MobAnimationSyncState) field("STATE").get(null);
        Set<?> pending = (Set<?>) field("PENDING").get(null);
        try {
            String id = MobAnimationServer.play(entity, "dodge_forward");
            var snapshot = state.snapshot(entity.getUuid());
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
            context.runAtTick(1, () -> {
                context.assertTrue(state.snapshot(entity.getUuid()).isEmpty(), "卸载必须清理活动请求");
                context.assertTrue(pending.size() <= before, "卸载必须清理追踪补发");
                context.complete();
            });
        } finally {
            server.getPlayerManager().remove(viewer);
        }
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = MobAnimationServer.class.getDeclaredField(name);
        field.setAccessible(true /* flag */);
        return field;
    }
}
