package dev.kizuna.playeranimation.mobs;

import static org.junit.jupiter.api.Assertions.*;

import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationRequest;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Play;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class MobAnimationSyncStateTest {
    @Test
    void trackingSnapshotPreservesIdentityAndStartTick() {
        MobAnimationSyncState state = new MobAnimationSyncState();
        UUID entity = UUID.randomUUID();
        EntityAnimationRequest first = play("first", "walk");
        state.play(entity, first);
        List<EntityAnimationRequest> snapshot = state.snapshot(entity);
        state.play(entity, play("second", "walk"));
        assertEquals(List.of(first), snapshot);
        assertEquals("second", state.snapshot(entity).get(0).getRequestId());
        assertEquals(first.getPlay().getStartTick(), state.snapshot(entity).get(0).getPlay().getStartTick());
    }

    @Test
    void selectiveStopAndRemovalDoNotAffectOtherEntities() {
        MobAnimationSyncState state = new MobAnimationSyncState();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        state.play(first, play("walk", "walk"));
        state.play(first, play("run", "run"));
        state.play(second, play("other", "walk"));
        state.stop(first, "walk");
        assertEquals("run", state.snapshot(first).get(0).getPlay().getAnimationId());
        state.stop(first, null);
        assertTrue(state.snapshot(first).isEmpty());
        assertEquals(1, state.snapshot(second).size());
        state.clear();
        assertTrue(state.entities().isEmpty());
    }

    private static EntityAnimationRequest play(String requestId, String animation) {
        Play operation = Play.newBuilder()
                .setEntityId(1)
                .setAnimationId(animation)
                .setStartTick(1)
                .build();
        return EntityAnimationRequest.newBuilder()
                .setRequestId(requestId)
                .setPlay(operation)
                .build();
    }
}
