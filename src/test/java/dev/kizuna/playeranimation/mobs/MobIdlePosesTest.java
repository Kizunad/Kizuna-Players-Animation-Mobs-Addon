package dev.kizuna.playeranimation.mobs;

import com.google.gson.JsonParser;
import dev.kizuna.playeranimation.mobs.MobIdlePoses.Pose;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class MobIdlePosesTest {
    private static final String ANIMATIONS = "assets/kizuna_players_animation/animations/";
    private static final float TICK_SECONDS = 0.05f;
    private static final float TOLERANCE = 1e-4f;
    private static final int DRAWS = 64;

    @Test
    void scheduledTicksMatchPackagedClipLengths() throws IOException {
        // 控制器按 tick 排进入段、退出段和火边各段；时长要和 PA 打包的剪辑一致，玩家版和村民骨架版一样长。
        for (Pose pose : Pose.values()) {
            if (pose == Pose.CAMPFIRE) {
                continue;
            }
            assertTicks(pose.enter(), pose.enterTicks);
            assertTicks(pose.exit(), pose.exitTicks);
        }
        var campfire = Map.of("campfire_sit_down", MobIdlePoses.SIT_DOWN_TICKS,
                "campfire_hands_in_sleeves", MobIdlePoses.TUCK_TICKS,
                "campfire_stand_up", MobIdlePoses.STAND_UP_TICKS,
                "campfire_stand_up_quick", MobIdlePoses.QUICK_STAND_UP_TICKS);
        for (var entry : campfire.entrySet()) {
            assertTicks(entry.getKey(), entry.getValue());
        }
    }

    @Test
    void choiceNeverRepeatsThePreviousPoseWhenThereIsAnother() {
        var pool = List.of(Pose.AKIMBO, Pose.ARMS_CROSSED, Pose.SQUAT, Pose.HANDS_BEHIND);
        for (int draw = -DRAWS; draw < DRAWS; draw++) {
            assertNotEquals(Pose.SQUAT, MobIdlePoses.choose(pool, Pose.SQUAT, draw));
        }
        // 只指定了一种就一直是它。
        for (int draw = -DRAWS; draw < DRAWS; draw++) {
            assertEquals(Pose.ARMS_CROSSED, MobIdlePoses.choose(List.of(Pose.ARMS_CROSSED), Pose.ARMS_CROSSED, draw));
        }
    }

    private static void assertTicks(String clip, int ticks) throws IOException {
        for (String id : List.of(clip, clip + "_robed")) {
            float seconds = length(id);
            assertEquals(ticks * TICK_SECONDS, seconds, TOLERANCE, id);
        }
    }

    private static float length(String id) throws IOException {
        var stream = MobIdlePosesTest.class.getClassLoader().getResourceAsStream(ANIMATIONS + id + ".animation.json");
        assertNotNull(stream, "PA 没有打包 " + id);
        try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            var animations = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("animations");
            var clip = animations.entrySet().iterator().next().getValue().getAsJsonObject();
            return clip.get("animation_length").getAsFloat();
        }
    }
}
