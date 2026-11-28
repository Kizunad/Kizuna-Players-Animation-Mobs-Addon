package dev.kizuna.playeranimation.mobs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.npc.villager.AbstractVillager;

/**
 * 日常姿态：站着不动、没在打斗或交谈的村民和流浪商人随机摆一种姿态，摆一阵退出再换；宿主用实体标签指定只摆哪几种，
 * 或叫它坐到火边烤火。进出都播进入段和退出段；挨打的当 tick 收手（坐着的播快版起身），进战斗、被交谈打断时退出，
 * 坐着被交谈打断则把手收进袖里。没有 AI 的生物由剧本控制，只在有标签时摆。
 */
public final class MobIdlePoses {
    /** 实体标签前缀，后接姿态名（akimbo、arms_crossed、squat、hands_behind、lean_counter、campfire）：只摆这几种。 */
    public static final String TAG_PREFIX = "kizuna_players_animation_mobs.idle.";
    /** 坐在火边时把手收进袖里，之后一直抱着袖。 */
    public static final String SLEEVES_TAG = TAG_PREFIX + "sleeves";
    /** 一次性的交谈打断：控制器读到就移除，站着的退出，坐着的收手入袖。 */
    public static final String TALK_TAG = TAG_PREFIX + "talk";
    static final int STAND_ENTER_TICKS = 10;
    static final int LOW_ENTER_TICKS = 12;
    static final int STAND_EXIT_TICKS = 9;
    static final int SQUAT_EXIT_TICKS = 11;
    static final int SIT_DOWN_TICKS = 24;
    static final int TUCK_TICKS = 16;
    static final int STAND_UP_TICKS = 20;
    static final int QUICK_STAND_UP_TICKS = 15;
    private static final String ROBED_SUFFIX = "_robed";
    private static final List<Pose> GENERIC_POOL =
            List.of(Pose.AKIMBO, Pose.ARMS_CROSSED, Pose.SQUAT, Pose.HANDS_BEHIND);
    // 站定多久开始摆：路过的村民停一下不必摆；剧本请来的演员到场就摆。
    private static final int GENERIC_REST_MIN = 60;
    private static final int GENERIC_REST_MAX = 160;
    private static final int SCRIPTED_REST = 20;
    // 一种姿态摆 20–60 s 再换；挨打、被打断后至少歇这么久才重新摆。
    private static final int HOLD_MIN = 400;
    private static final int HOLD_MAX = 1200;
    private static final int REST_AFTER_INTERRUPT = 200;
    // 水平速度超过每 tick 这么多（格）算在走。
    private static final double MOVING_STEP = 0.02;
    private static final Set<LivingEntity> CANDIDATES = new HashSet<>();
    private static final Map<UUID, Stay> STAYS = new HashMap<>();

    enum Pose {
        AKIMBO("akimbo", STAND_ENTER_TICKS, STAND_EXIT_TICKS),
        ARMS_CROSSED("arms_crossed", STAND_ENTER_TICKS, STAND_EXIT_TICKS),
        SQUAT("squat", LOW_ENTER_TICKS, SQUAT_EXIT_TICKS),
        HANDS_BEHIND("hands_behind", STAND_ENTER_TICKS, STAND_EXIT_TICKS),
        LEAN_COUNTER("lean_counter", LOW_ENTER_TICKS, STAND_EXIT_TICKS),
        CAMPFIRE("campfire", SIT_DOWN_TICKS, STAND_UP_TICKS);

        final String tag;
        final int enterTicks;
        final int exitTicks;

        Pose(String tag, int enterTicks, int exitTicks) {
            this.tag = tag;
            this.enterTicks = enterTicks;
            this.exitTicks = exitTicks;
        }

        String enter() {
            return "idle_" + tag + "_enter";
        }

        String loop() {
            return "idle_" + tag;
        }

        String exit() {
            return "idle_" + tag + "_exit";
        }
    }

    enum Phase { RESTING, ENTERING, HOLDING, LEAVING, SITTING_DOWN, WARMING, TUCKING, SLEEVED, RISING }

    private enum Busy { NONE, MOVING, FIGHTING, TRADING }

    /** 一个生物当前的姿态：phase 到 until 那 tick 换下一段；clip 是本控制器正在播的片段（没有则为 null）。 */
    static final class Stay {
        Pose pose;
        Pose last;
        Phase phase = Phase.RESTING;
        long until;
        String clip;

        Stay(long restUntil) {
            until = restUntil;
        }
    }

    private MobIdlePoses() {
        // 只有静态入口。
    }

    static void register() {
        ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
            if (entity instanceof Mob mob) {
                CANDIDATES.add(mob);
            }
        });
        ServerEntityEvents.ENTITY_UNLOAD.register((entity, level) -> {
            CANDIDATES.remove(entity);
            STAYS.remove(entity.getUUID());
        });
        ServerTickEvents.END_LEVEL_TICK.register(MobIdlePoses::tick);
        // 受击在扣血的同一 tick 收手：站着的停下姿态，坐着的播快版起身。
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> hurt(entity));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            CANDIDATES.clear();
            STAYS.clear();
        });
    }

    /** 坐在火边（坐下、烤火、收手、抱袖）时为真：交易不再播站着递东西的动作，改成收手入袖。 */
    public static boolean seated(LivingEntity entity) {
        Stay stay = STAYS.get(entity.getUUID());
        return stay != null && switch (stay.phase) {
            case SITTING_DOWN, WARMING, TUCKING, SLEEVED -> true;
            default -> false;
        };
    }

    /** 被交谈打断：站着的退出姿态，坐着的把手收进袖里。 */
    public static void talk(LivingEntity entity) {
        Stay stay = STAYS.get(entity.getUUID());
        if (stay != null) {
            interrupt(entity, stay, Busy.TRADING, entity.level().getGameTime());
        }
    }

    static Stay stay(LivingEntity entity) {
        return STAYS.get(entity.getUUID());
    }

    static List<Pose> pool(LivingEntity entity) {
        // 标签指定的优先；火边一坐就只坐着。没有标签时只有有 AI 的成年村民、流浪商人用通用池。
        List<Pose> tagged = new ArrayList<>();
        for (Pose pose : Pose.values()) {
            if (entity.entityTags().contains(TAG_PREFIX + pose.tag)) {
                tagged.add(pose);
            }
        }
        if (tagged.contains(Pose.CAMPFIRE)) {
            return List.of(Pose.CAMPFIRE);
        }
        if (!tagged.isEmpty()) {
            return List.copyOf(tagged);
        }
        if (entity instanceof AbstractVillager villager && !villager.isNoAi() && !villager.isBaby()) {
            return GENERIC_POOL;
        }
        return List.of();
    }

    static Pose choose(List<Pose> pool, Pose last, int draw) {
        // 池里不止一种时不连着摆同一种。
        List<Pose> options = new ArrayList<>(pool);
        if (options.size() > 1) {
            options.remove(last);
        }
        return options.get(Math.floorMod(draw, options.size()));
    }

    private static void tick(ServerLevel level) {
        long now = level.getGameTime();
        for (LivingEntity entity : List.copyOf(CANDIDATES)) {
            if (entity.level() == level && entity.isAlive()) {
                update(entity, now);
            }
        }
    }

    private static void update(LivingEntity entity, long now) {
        List<Pose> pool = pool(entity);
        Stay stay = STAYS.get(entity.getUUID());
        if (pool.isEmpty()) {
            if (stay != null) {
                release(entity, stay);
            }
            return;
        }
        if (stay == null) {
            stay = new Stay(now + firstRest(entity));
            STAYS.put(entity.getUUID(), stay);
        }
        if (entity.removeTag(TALK_TAG)) {
            interrupt(entity, stay, Busy.TRADING, now);
        }
        // 坐着交易照常往下走（收手入袖、抱袖），别的打断按各自的处理。
        Busy busy = busy(entity);
        if (busy == Busy.NONE || busy == Busy.TRADING && seated(entity)) {
            advance(entity, stay, pool, now);
        } else {
            interrupt(entity, stay, busy, now);
        }
    }

    private static int firstRest(LivingEntity entity) {
        // 宿主用标签指定了姿态的演员到场就摆。
        if (entity.entityTags().stream().anyMatch(tag -> tag.startsWith(TAG_PREFIX))) {
            return SCRIPTED_REST;
        }
        return entity.getRandom().nextIntBetweenInclusive(GENERIC_REST_MIN, GENERIC_REST_MAX);
    }

    private static Busy busy(LivingEntity entity) {
        if (entity instanceof Mob mob && (mob.getTarget() != null || mob.isAggressive())) {
            return Busy.FIGHTING;
        }
        if (entity instanceof AbstractVillager merchant && merchant.getTradingPlayer() != null) {
            return Busy.TRADING;
        }
        // 看速度不看位移：剧本把演员挪到座位上不算在走。没有 AI 的生物不走物理步，着地标记不更新，不看它。
        boolean stepping = entity.getDeltaMovement().horizontalDistanceSqr() > MOVING_STEP * MOVING_STEP;
        boolean scripted = entity instanceof Mob mob && mob.isNoAi();
        boolean airborne = !scripted && !entity.onGround();
        if (stepping || airborne || entity.isPassenger() || entity.isSleeping() || entity.isInWater()) {
            return Busy.MOVING;
        }
        return Busy.NONE;
    }

    private static void advance(LivingEntity entity, Stay stay, List<Pose> pool, long now) {
        boolean wanted = stay.pose != null && pool.contains(stay.pose);
        switch (stay.phase) {
            case RESTING -> {
                if (now >= stay.until) {
                    start(entity, stay, pool, now);
                }
            }
            case ENTERING -> {
                if (now >= stay.until) {
                    hold(entity, stay, pool, now);
                }
            }
            case HOLDING -> {
                if (now >= stay.until || !wanted) {
                    leave(entity, stay, now);
                }
            }
            case LEAVING -> {
                if (now >= stay.until) {
                    rest(entity, stay, now + restTicks(entity));
                }
            }
            case RISING -> {
                // 从火边起身只因打断或撤了火边标签，歇满打断后的时长再坐回去。
                if (now >= stay.until) {
                    rest(entity, stay, now + REST_AFTER_INTERRUPT);
                }
            }
            case SITTING_DOWN, WARMING, TUCKING, SLEEVED -> sit(entity, stay, pool, now);
        }
    }

    private static void start(LivingEntity entity, Stay stay, List<Pose> pool, long now) {
        stay.pose = choose(pool, stay.last, entity.getRandom().nextInt());
        if (stay.pose == Pose.CAMPFIRE) {
            play(entity, stay, Phase.SITTING_DOWN, "campfire_sit_down", now + SIT_DOWN_TICKS);
        } else {
            play(entity, stay, Phase.ENTERING, stay.pose.enter(), now + stay.pose.enterTicks);
        }
    }

    private static void hold(LivingEntity entity, Stay stay, List<Pose> pool, long now) {
        // 只指定了一种姿态的一直摆着；能换的摆 20–60 s。
        long until = Long.MAX_VALUE;
        if (pool.size() > 1) {
            until = now + entity.getRandom().nextIntBetweenInclusive(HOLD_MIN, HOLD_MAX);
        }
        play(entity, stay, Phase.HOLDING, stay.pose.loop(), until);
    }

    private static void leave(LivingEntity entity, Stay stay, long now) {
        play(entity, stay, Phase.LEAVING, stay.pose.exit(), now + stay.pose.exitTicks);
    }

    private static void sit(LivingEntity entity, Stay stay, List<Pose> pool, long now) {
        // 坐下 → 烤火；有收袖标签或在交易时收手入袖 → 抱袖。撤了火边标签就起身：抱着袖播整段起身，烤着火播快版。
        boolean wanted = pool.contains(Pose.CAMPFIRE);
        boolean sleeves = entity.entityTags().contains(SLEEVES_TAG) || busy(entity) == Busy.TRADING;
        if (!wanted && (stay.phase == Phase.WARMING || stay.phase == Phase.SLEEVED)) {
            riseFrom(entity, stay, now);
        } else if (stay.phase == Phase.SITTING_DOWN && now >= stay.until) {
            play(entity, stay, Phase.WARMING, "campfire_warm_loop", Long.MAX_VALUE);
        } else if (stay.phase == Phase.WARMING && sleeves) {
            tuck(entity, stay, now);
        } else if (stay.phase == Phase.TUCKING && now >= stay.until) {
            play(entity, stay, Phase.SLEEVED, "campfire_sleeves_loop", Long.MAX_VALUE);
        }
    }

    private static void tuck(LivingEntity entity, Stay stay, long now) {
        play(entity, stay, Phase.TUCKING, "campfire_hands_in_sleeves", now + TUCK_TICKS);
    }

    private static void riseFrom(LivingEntity entity, Stay stay, long now) {
        // 整段起身的首帧是抱袖坐；其余时候都用快版。
        if (stay.phase == Phase.SLEEVED) {
            play(entity, stay, Phase.RISING, "campfire_stand_up", now + STAND_UP_TICKS);
        } else {
            play(entity, stay, Phase.RISING, "campfire_stand_up_quick", now + QUICK_STAND_UP_TICKS);
        }
    }

    private static void interrupt(LivingEntity entity, Stay stay, Busy busy, long now) {
        // 退出段、起身播完就停下：保持末帧的片段不能一直盖着走路、打斗的自动层。
        boolean finished = now >= stay.until;
        switch (stay.phase) {
            case ENTERING, HOLDING -> {
                if (busy == Busy.FIGHTING) {
                    leave(entity, stay, now);
                } else {
                    rest(entity, stay, now + REST_AFTER_INTERRUPT);
                }
            }
            case LEAVING -> {
                if (busy == Busy.MOVING || finished) {
                    rest(entity, stay, now + REST_AFTER_INTERRUPT);
                }
            }
            case SITTING_DOWN, WARMING, TUCKING, SLEEVED -> {
                if (busy != Busy.TRADING) {
                    play(entity, stay, Phase.RISING, "campfire_stand_up_quick", now + QUICK_STAND_UP_TICKS);
                } else if (stay.phase == Phase.WARMING) {
                    tuck(entity, stay, now);
                }
            }
            case RESTING -> stay.until = Math.max(stay.until, now + postpone(busy));
            case RISING -> {
                if (finished) {
                    rest(entity, stay, now + REST_AFTER_INTERRUPT);
                }
            }
        }
    }

    private static int postpone(Busy busy) {
        // 走着的停下来照常歇一会儿再摆；打斗、交易过后歇久一点。
        if (busy == Busy.MOVING) {
            return GENERIC_REST_MIN;
        }
        return REST_AFTER_INTERRUPT;
    }

    static void hurt(LivingEntity entity) {
        Stay stay = STAYS.get(entity.getUUID());
        if (stay == null) {
            return;
        }
        long now = entity.level().getGameTime();
        switch (stay.phase) {
            case ENTERING, HOLDING, LEAVING -> rest(entity, stay, now + REST_AFTER_INTERRUPT);
            case SITTING_DOWN, WARMING, TUCKING, SLEEVED ->
                    play(entity, stay, Phase.RISING, "campfire_stand_up_quick", now + QUICK_STAND_UP_TICKS);
            case RESTING -> stay.until = Math.max(stay.until, now + REST_AFTER_INTERRUPT);
            case RISING -> {
                // 已经在起身。
            }
        }
    }

    private static int restTicks(LivingEntity entity) {
        return entity.getRandom().nextIntBetweenInclusive(GENERIC_REST_MIN, GENERIC_REST_MAX);
    }

    private static void play(LivingEntity entity, Stay stay, Phase phase, String clip, long until) {
        // 先起播下一段再停上一段：上一段末帧就是下一段首帧，两边按各自的淡入淡出接上。
        String next = clip + suffix(entity);
        MobAnimationServer.play(entity, next);
        if (stay.clip != null && !stay.clip.equals(next)) {
            MobAnimationServer.stop(entity, stay.clip);
        }
        stay.clip = next;
        stay.phase = phase;
        stay.until = until;
    }

    private static void rest(LivingEntity entity, Stay stay, long until) {
        if (stay.clip != null) {
            MobAnimationServer.stop(entity, stay.clip);
            stay.clip = null;
        }
        if (stay.pose != null) {
            stay.last = stay.pose;
        }
        stay.pose = null;
        stay.phase = Phase.RESTING;
        stay.until = until;
    }

    private static void release(LivingEntity entity, Stay stay) {
        // 标签撤掉、又不在通用池里：停下本控制器播的片段，不再管它。
        if (stay.clip != null) {
            MobAnimationServer.stop(entity, stay.clip);
        }
        STAYS.remove(entity.getUUID());
    }

    private static String suffix(LivingEntity entity) {
        // 村民、流浪商人挂袍子，用村民骨架版；其余（例如玩家模型的角色）用玩家版。
        if (entity instanceof AbstractVillager) {
            return ROBED_SUFFIX;
        }
        return "";
    }
}
