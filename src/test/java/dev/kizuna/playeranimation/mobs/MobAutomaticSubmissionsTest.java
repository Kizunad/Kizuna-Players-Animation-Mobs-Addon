package dev.kizuna.playeranimation.mobs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.kizuna.playeranimation.mobs.MobAutomaticSubmissions.State;
import org.junit.jupiter.api.Test;

class MobAutomaticSubmissionsTest {
    private static final int ENTITY_ID = 7;
    private static final State IDLE = new State("idle", "", 0, false);

    @Test
    void unchangedStateIsSubmittedOnceUntilRefresh() {
        // 状态不变只提交第一次；满重发间隔后补一次，PA 清过播放表也能恢复。
        var submissions = new MobAutomaticSubmissions();
        Object entity = new Object();
        assertTrue(submissions.due(ENTITY_ID, entity, IDLE, 0));
        for (long tick = 1; tick < MobAutomaticSubmissions.REFRESH_TICKS; tick++) {
            assertFalse(submissions.due(ENTITY_ID, entity, IDLE, tick));
        }
        assertTrue(submissions.due(ENTITY_ID, entity, IDLE, MobAutomaticSubmissions.REFRESH_TICKS));
        assertFalse(submissions.due(ENTITY_ID, entity, IDLE, MobAutomaticSubmissions.REFRESH_TICKS + 1));
    }

    @Test
    void anyChangedFieldIsSubmittedAtOnce() {
        var submissions = new MobAutomaticSubmissions();
        Object entity = new Object();
        assertTrue(submissions.due(ENTITY_ID, entity, IDLE, 0));
        assertTrue(submissions.due(ENTITY_ID, entity, new State("walk", "", 0, false), 1));
        assertTrue(submissions.due(ENTITY_ID, entity, new State("walk", "attack_main", 0, false), 2));
        assertTrue(submissions.due(ENTITY_ID, entity, new State("walk", "attack_main", 0.3f, false), 3));
        assertTrue(submissions.due(ENTITY_ID, entity, new State("walk", "attack_main", 0.3f, true), 4));
        assertFalse(submissions.due(ENTITY_ID, entity, new State("walk", "attack_main", 0.3f, true), 5));
    }

    @Test
    void reusedEntityIdAndForgottenEntitiesAreSubmittedAgain() {
        // 实体 ID 复用、死亡清层、卸载、资源重载之后，PA 那边已经没有这一层，相同状态也要重新提交。
        var submissions = new MobAutomaticSubmissions();
        Object entity = new Object();
        assertTrue(submissions.due(ENTITY_ID, entity, IDLE, 0));
        assertTrue(submissions.due(ENTITY_ID, new Object(), IDLE, 1));
        assertTrue(submissions.due(ENTITY_ID, entity, IDLE, 2));
        submissions.forget(ENTITY_ID);
        assertTrue(submissions.due(ENTITY_ID, entity, IDLE, 3));
        submissions.retain(id -> id != ENTITY_ID);
        assertTrue(submissions.due(ENTITY_ID, entity, IDLE, 4));
        submissions.clear();
        assertTrue(submissions.due(ENTITY_ID, entity, IDLE, 5));
    }
}
