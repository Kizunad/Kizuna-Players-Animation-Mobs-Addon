package dev.kizuna.playeranimation.mobs;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.kizuna.playeranimation.api.PlayerAnimationHost;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Automatic;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationRequest;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.EntityAnimationResponse;
import dev.kizuna.playeranimation.protocol.pb.EntityAnimationProtocol.Play;
import java.util.HashMap;
import java.util.Map;

/** 只在客户端验收中记录公开字节边界，所有请求仍由真实 PA 处理。 */
final class MobRecordingHost implements PlayerAnimationHost {
    private final PlayerAnimationHost delegate;
    private final Map<Integer, Automatic> automatic = new HashMap<>();
    private final Map<Integer, Play> explicit = new HashMap<>();

    private MobRecordingHost(PlayerAnimationHost delegate) {
        // 保留真实入口，记录不能替代实际动画计算或响应。
        this.delegate = delegate;
    }

    static MobRecordingHost install() throws ReflectiveOperationException {
        // 测试装饰附属持有的公开 host，生产代码不增加测试专用回调或 PA 内部依赖。
        var recording = new MobRecordingHost(MobAnimationClient.host());
        var field = MobAnimationClient.class.getDeclaredField("host");
        field.setAccessible(true /* flag */);
        field.set(null, recording);
        return recording;
    }

    @Override
    public byte[] exchange(byte[] payload) {
        // 在成功回执之后记录，非法请求不能被当成已播放证据。
        byte[] response = delegate.exchange(payload);
        try {
            if (EntityAnimationResponse.parseFrom(response).getCode() != EntityAnimationResponse.Code.OK) {
                return response;
            }
            var request = EntityAnimationRequest.parseFrom(payload);
            if (request.hasAutomatic()) {
                var state = request.getAutomatic();
                automatic.put(state.getEntityId(), state);
            } else if (request.hasPlay()) {
                var play = request.getPlay();
                explicit.put(play.getEntityId(), play);
            } else if (request.hasClear()) {
                int id = request.getClear().getEntityId();
                automatic.remove(id);
                explicit.remove(id);
            }
        } catch (InvalidProtocolBufferException ignored) {
            // 非法字节的回执仍交回测试断言，不把它登记为播放。
        }
        return response;
    }

    Automatic automatic(int entityId) {
        return automatic.get(entityId);
    }

    Play explicit(int entityId) {
        return explicit.get(entityId);
    }
}
