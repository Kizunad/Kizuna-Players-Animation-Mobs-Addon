package dev.kizuna.playeranimation.mobs;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;
import com.google.protobuf.Parser;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.Identifier;

/** 生物附属网络载荷只携带 PA v1 protobuf 字节。 */
public final class MobAnimationPackets {
    public static final String MOD_ID = "kizuna_players_animation_mobs";
    public static final Identifier PLAY = new Identifier(MOD_ID, "play");
    public static final Identifier STOP = new Identifier(MOD_ID, "stop");
    public static final int MAX_BYTES = 32 * 1024;

    private MobAnimationPackets() { }

    public static <T extends MessageLite> T read(PacketByteBuf buffer, Parser<T> parser)
            throws InvalidProtocolBufferException {
        int size = buffer.readableBytes();
        if (size > MAX_BYTES) {
            throw new IllegalArgumentException("生物动画包超过 32 KiB");
        }
        byte[] payload = new byte[size];
        buffer.readBytes(payload);
        return parser.parseFrom(payload);
    }

    public static PacketByteBuf write(MessageLite message) {
        if (message.getSerializedSize() > MAX_BYTES) {
            throw new IllegalArgumentException("生物动画包超过 32 KiB");
        }
        PacketByteBuf buffer = PacketByteBufs.create();
        buffer.writeBytes(message.toByteArray());
        return buffer;
    }
}
