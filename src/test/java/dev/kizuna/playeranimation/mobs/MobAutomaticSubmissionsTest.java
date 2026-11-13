package dev.kizuna.playeranimation.mobs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.kizuna.playeranimation.mobs.MobAutomaticSubmissions.State;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MobAutomaticSubmissionsTest {
    private static final int ENTITY_ID = 7;
    private static final State IDLE = new State("idle", "", 0, false);
    private static final State WALK = new State("walk", "", 0, false);

    /** 代替 PA 的入口：记下交来的每个状态，按 accepting 决定收不收。 */
    private static final class Host implements MobAutomaticSubmissions.Sender {
        private final List<State> received = new ArrayList<>();
        private boolean accepting = true;

        @Override
        public boolean send(int entityId, State state) {
            received.add(state);
            return accepting;
        }
    }

    @Test
    void unchangedStateIsSubmittedOnceUntilRefresh() {
        // 状态不变只提交第一次；满重发间隔后补一次，PA 清过播放表也能恢复。
        var submissions = new MobAutomaticSubmissions();
        var host = new Host();
        Object entity = new Object();
        for (long tick = 0; tick < MobAutomaticSubmissions.REFRESH_TICKS; tick++) {
            submissions.submit(ENTITY_ID, entity, IDLE, tick, host);
        }
        assertEquals(List.of(IDLE), host.received);
        submissions.submit(ENTITY_ID, entity, IDLE, MobAutomaticSubmissions.REFRESH_TICKS, host);
        submissions.submit(ENTITY_ID, entity, IDLE, MobAutomaticSubmissions.REFRESH_TICKS + 1, host);
        assertEquals(List.of(IDLE, IDLE), host.received);
    }

    @Test
    void anyChangedFieldIsSubmittedAtOnce() {
        var submissions = new MobAutomaticSubmissions();
        var host = new Host();
        Object entity = new Object();
        List<State> states = List.of(
                IDLE,
                WALK,
                new State("walk", "attack_main", 0, false),
                new State("walk", "attack_main", 0.3f, false),
                new State("walk", "attack_main", 0.3f, true));
        long tick = 0;
        for (State state : states) {
            submissions.submit(ENTITY_ID, entity, state, tick++, host);
        }
        submissions.submit(ENTITY_ID, entity, states.getLast(), tick, host);
        assertEquals(states, host.received);
    }

    @Test
    void reusedEntityIdAndForgottenEntitiesAreSubmittedAgain() {
        // 实体 ID 复用、死亡清层、卸载、资源重载之后，PA 那边已经没有这一层，相同状态也要重新提交。
        var submissions = new MobAutomaticSubmissions();
        var host = new Host();
        Object entity = new Object();
        submissions.submit(ENTITY_ID, entity, IDLE, 0, host);
        submissions.submit(ENTITY_ID, new Object(), IDLE, 1, host);
        submissions.submit(ENTITY_ID, entity, IDLE, 2, host);
        submissions.forget(ENTITY_ID);
        submissions.submit(ENTITY_ID, entity, IDLE, 3, host);
        submissions.retain(id -> id != ENTITY_ID);
        submissions.submit(ENTITY_ID, entity, IDLE, 4, host);
        submissions.clear();
        submissions.submit(ENTITY_ID, entity, IDLE, 5, host);
        assertEquals(6, host.received.size());
    }

    @Test
    void rejectedStateIsSubmittedAgainEveryTickUntilAccepted() {
        // PA 拒收（几何还没注册、正在重载、实体还没加载）时不能当成已发，否则要等满重发间隔才补上。
        var submissions = new MobAutomaticSubmissions();
        var host = new Host();
        Object entity = new Object();
        host.accepting = false;
        submissions.submit(ENTITY_ID, entity, IDLE, 0, host);
        submissions.submit(ENTITY_ID, entity, IDLE, 1, host);
        submissions.submit(ENTITY_ID, entity, IDLE, 2, host);
        assertEquals(List.of(IDLE, IDLE, IDLE), host.received);
        host.accepting = true;
        submissions.submit(ENTITY_ID, entity, IDLE, 3, host);
        submissions.submit(ENTITY_ID, entity, IDLE, 4, host);
        assertEquals(List.of(IDLE, IDLE, IDLE, IDLE), host.received);
    }

    @Test
    void rejectionForgetsTheEarlierAcceptedState() {
        // 收下 IDLE 后 WALK 被拒：之后回到 IDLE 也要重新交，不能凭旧记录以为 PA 还停在 IDLE 上。
        var submissions = new MobAutomaticSubmissions();
        var host = new Host();
        Object entity = new Object();
        submissions.submit(ENTITY_ID, entity, IDLE, 0, host);
        host.accepting = false;
        submissions.submit(ENTITY_ID, entity, WALK, 1, host);
        host.accepting = true;
        submissions.submit(ENTITY_ID, entity, IDLE, 2, host);
        assertEquals(List.of(IDLE, WALK, IDLE), host.received);
    }
}
