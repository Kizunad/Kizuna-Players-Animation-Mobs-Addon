package dev.kizuna.playeranimation.mobs;

import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationRequest;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 服务端只登记实体动画请求，客户端播放状态仍由 PA 持有。 */
public final class MobAnimationSyncState {
    private final Map<UUID, LinkedHashMap<String, EntityAnimationRequest>> entities = new HashMap<>();

    public void play(UUID entity, EntityAnimationRequest request) {
        // 保留原始请求 ID 与起点；追踪补发不能被客户端误认为同 tick 的新播放。
        String animation = request.getPlay().getAnimationId();
        var animations = entities.computeIfAbsent(entity, ignored -> new LinkedHashMap<>());
        animations.remove(animation);
        animations.put(animation, request);
    }

    public void stop(UUID entity, String animationId) {
        var animations = entities.get(entity);
        if (animations == null) {
            return;
        }
        if (animationId == null) {
            entities.remove(entity);
            return;
        }
        animations.remove(animationId);
        if (animations.isEmpty()) {
            entities.remove(entity);
        }
    }

    public List<EntityAnimationRequest> snapshot(UUID entity) {
        var animations = entities.get(entity);
        if (animations == null) {
            return List.of();
        }
        return List.copyOf(animations.values());
    }

    public Set<UUID> entities() {
        return Set.copyOf(entities.keySet());
    }

    public void clear() {
        entities.clear();
    }
}
