package dev.kizuna.playeranimation.mobs;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.villager.Villager;

public final class MobIdlePosesGameTest {
    private static final BlockPos SPOT = new BlockPos(1, 1, 1);
    // 剧本演员到场 20 tick 后摆，站姿进入段 10 tick、坐下 24 tick；留几 tick 余量再检查。
    private static final int STANDING_HELD = 40;
    private static final int SEATED_WARMING = 60;
    private static final int SLEEVES_SETTLED = 20;
    private static final int GENERIC_TIMEOUT = 300;
    private static final int SCRIPTED_TIMEOUT = 120;
    private static final float HIT = 1;

    @GameTest(maxTicks = GENERIC_TIMEOUT)
    public void stillVillagerTakesARandomPose(GameTestHelper context) {
        // 有 AI 的村民站定一阵就从通用池里挑一种摆；腿速清零让它待在原地。
        var villager = context.spawn(EntityTypes.VILLAGER, SPOT);
        villager.getAttribute(Attributes.MOVEMENT_SPEED).setBaseValue(0);
        context.succeedWhen(() -> context.assertTrue(playing(villager).stream().anyMatch(id ->
                        id.startsWith("idle_") && id.endsWith("_enter_robed")),
                "站定的村民没有进入日常姿态，实际 " + playing(villager)));
    }

    @GameTest(maxTicks = SCRIPTED_TIMEOUT)
    public void hitStopsStandingPoseInTheSameTick(GameTestHelper context) {
        var villager = scripted(context, "akimbo");
        context.runAtTickTime(STANDING_HELD, () -> {
            context.assertTrue(playing(villager).contains("idle_akimbo_robed"),
                    "叉腰没有进入循环，实际 " + playing(villager) + state(villager));
            villager.hurtServer(context.getLevel(), context.getLevel().damageSources().generic(), HIT);
            context.assertTrue(playing(villager).stream().noneMatch(id -> id.startsWith("idle_")),
                    "挨打的同一 tick 没有收手，实际 " + playing(villager) + state(villager));
            context.succeed();
        });
    }

    @GameTest(maxTicks = SCRIPTED_TIMEOUT)
    public void hitWhileSeatedStandsUpQuickly(GameTestHelper context) {
        var villager = scripted(context, "campfire");
        context.runAtTickTime(SEATED_WARMING, () -> {
            context.assertTrue(playing(villager).contains("campfire_warm_loop_robed"),
                    "没有坐下烤火，实际 " + playing(villager) + state(villager));
            villager.hurtServer(context.getLevel(), context.getLevel().damageSources().generic(), HIT);
            context.assertTrue(playing(villager).equals(List.of("campfire_stand_up_quick_robed")),
                    "坐着挨打的同一 tick 没有快版起身，实际 " + playing(villager) + state(villager));
            context.succeed();
        });
    }

    @GameTest(maxTicks = SCRIPTED_TIMEOUT)
    public void tradingWhileSeatedTucksHandsIntoSleeves(GameTestHelper context) {
        // 站着的交易时播递东西；坐在火边的收手入袖，抱着袖接着坐。
        var villager = scripted(context, "campfire");
        var customer = context.makeMockServerPlayerInLevel();
        context.runAtTickTime(SEATED_WARMING, () -> {
            villager.setTradingPlayer(customer);
            context.assertTrue(playing(villager).equals(List.of("campfire_hands_in_sleeves_robed")),
                    "坐着被交易打断没有收手入袖，实际 " + playing(villager) + state(villager));
        });
        context.runAtTickTime(SEATED_WARMING + SLEEVES_SETTLED, () -> {
            context.assertTrue(playing(villager).equals(List.of("campfire_sleeves_loop_robed")),
                    "收手入袖后没有抱袖坐，实际 " + playing(villager) + state(villager));
            villager.setTradingPlayer(null);
            context.getLevel().getServer().getPlayerList().remove(customer);
            context.succeed();
        });
    }

    @GameTest(maxTicks = SCRIPTED_TIMEOUT)
    public void talkTagWhileSeatedTucksHandsIntoSleeves(GameTestHelper context) {
        // 剧情对话用一次性标签打断：读到就移除，坐着的收手入袖。
        var villager = scripted(context, "campfire");
        context.runAtTickTime(SEATED_WARMING, () -> villager.addTag(MobIdlePoses.TALK_TAG));
        context.runAtTickTime(SEATED_WARMING + 1, () -> {
            context.assertTrue(playing(villager).equals(List.of("campfire_hands_in_sleeves_robed")),
                    "交谈标签没有让坐着的收手入袖，实际 " + playing(villager) + state(villager));
            context.assertTrue(!villager.entityTags().contains(MobIdlePoses.TALK_TAG), "交谈标签没有被读走");
            context.succeed();
        });
    }

    @GameTest(maxTicks = SCRIPTED_TIMEOUT)
    public void specifiedPoseIsTheOnlyPoseUsed(GameTestHelper context) {
        // 剧情指定抱臂的演员只摆抱臂，而且一直摆着，不换别的。
        var villager = scripted(context, "arms_crossed");
        context.runAtTickTime(STANDING_HELD, () -> {
            context.assertTrue(playing(villager).equals(List.of("idle_arms_crossed_robed")),
                    "指定抱臂的演员摆了别的，实际 " + playing(villager) + state(villager));
            context.assertTrue(MobIdlePoses.stay(villager).until == Long.MAX_VALUE, "只指定一种姿态时不该到点换");
            context.succeed();
        });
    }

    @GameTest(maxTicks = SCRIPTED_TIMEOUT)
    public void taggedPlayerModelActorUsesPlayerClips(GameTestHelper context) {
        // 不是村民、流浪商人的（例如玩家模型的角色）用玩家版；卫道士白天不着火，不会自己挨烧打断。
        var actor = context.spawnWithNoFreeWill(EntityTypes.VINDICATOR, SPOT);
        actor.setNoAi(true /* noAi */);
        actor.addTag(MobIdlePoses.TAG_PREFIX + "hands_behind");
        context.runAtTickTime(STANDING_HELD, () -> {
            context.assertTrue(playing(actor).equals(List.of("idle_hands_behind")),
                    "实际 " + playing(actor) + state(actor));
            context.succeed();
        });
    }

    private static Villager scripted(GameTestHelper context, String pose) {
        // 剧本请来的演员没有 AI（和剧情仓请人的做法一样），靠标签指定姿态。
        var villager = context.spawnWithNoFreeWill(EntityTypes.VILLAGER, SPOT);
        villager.setNoAi(true /* noAi */);
        villager.addTag(MobIdlePoses.TAG_PREFIX + pose);
        return villager;
    }

    private static List<String> playing(LivingEntity entity) {
        return MobAnimationServer.snapshot(entity).stream()
                .filter(request -> request.hasPlay())
                .map(request -> request.getPlay().getAnimationId())
                .toList();
    }

    private static String state(LivingEntity entity) {
        // 断言失败时带上控制器的阶段，便于看停在哪一段。
        var stay = MobIdlePoses.stay(entity);
        if (stay == null) {
            return " 控制器没有登记，标签 " + entity.entityTags();
        }
        return " 阶段 " + stay.phase + "，到 " + stay.until + "，现在 " + entity.level().getGameTime();
    }
}
