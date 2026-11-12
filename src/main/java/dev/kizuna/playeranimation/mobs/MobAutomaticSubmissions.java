package dev.kizuna.playeranimation.mobs;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntPredicate;

/** 记住每个实体上一次交给 PA 的自动层状态；状态没变就不再提交，省掉每 tick 一轮请求和回执的编解码。 */
final class MobAutomaticSubmissions {
    /**
     * 状态一直不变时隔这么多 tick 仍提交一次。PA 会在资源重载等时刻清空播放表，
     * 附属只能通过字节入口提交、查不到 PA 是否还留着这一层，靠这次重发补回来。
     */
    static final int REFRESH_TICKS = 20;

    record State(String base, String action, float progress, boolean leftHanded) { }

    private record Submitted(Object entity, State state, long tick) { }

    private final Map<Integer, Submitted> submitted = new HashMap<>();

    /** 这个状态需要提交时返回 true 并记下；与上次相同且未到重发间隔时返回 false。 */
    boolean due(int entityId, Object entity, State state, long tick) {
        Submitted last = submitted.get(entityId);
        // 实体 ID 会复用：同一个 ID 换了实体对象，PA 那边的旧层已随卸载清掉，必须重新提交。
        if (last != null && last.entity() == entity && last.state().equals(state)
                && tick - last.tick() < REFRESH_TICKS) {
            return false;
        }
        submitted.put(entityId, new Submitted(entity, state, tick));
        return true;
    }

    void forget(int entityId) {
        submitted.remove(entityId);
    }

    void retain(IntPredicate loaded) {
        submitted.keySet().removeIf(id -> !loaded.test(id));
    }

    void clear() {
        submitted.clear();
    }
}
