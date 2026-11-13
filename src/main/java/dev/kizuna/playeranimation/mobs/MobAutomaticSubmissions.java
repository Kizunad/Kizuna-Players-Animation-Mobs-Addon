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

    /** 把一个状态交给 PA；PA 收下返回 true。 */
    interface Sender {
        boolean send(int entityId, State state);
    }

    /**
     * 状态变了或到了重发间隔就交给 sender。PA 收下才记成已发；
     * 拒收（几何还没注册、正在重载、实体还没加载）不记，下一 tick 照样再交。
     */
    void submit(int entityId, Object entity, State state, long tick, Sender sender) {
        Submitted last = submitted.get(entityId);
        // 实体 ID 会复用：同一个 ID 换了实体对象，PA 那边的旧层已随卸载清掉，必须重新提交。
        if (last != null && last.entity() == entity && last.state().equals(state)
                && tick - last.tick() < REFRESH_TICKS) {
            return;
        }
        if (sender.send(entityId, state)) {
            submitted.put(entityId, new Submitted(entity, state, tick));
        } else {
            submitted.remove(entityId);
        }
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
