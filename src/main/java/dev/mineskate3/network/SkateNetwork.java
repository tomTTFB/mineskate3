package dev.mineskate3.network;

import dev.mineskate3.MineSkate3;
import dev.mineskate3.server.SkateServer;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Skating is simulated on the skater's own client. The server only learns who
 * is skating (to waive fall damage and the flying kick) and relays each
 * skater's pose to the players who can see them.
 */
public final class SkateNetwork {
    /**
     * Six model parts and the board, each a 3x4 matrix relative to the
     * player's feet, then the skater's status for sound: physical state id,
     * wheel contacts, impact speed, wiping out (0/1) and speed in m/s.
     */
    public static final int POSE_FLOATS = 7 * 12 + 5;
    public static final int STATUS_AT = 7 * 12;
    /** Upper bound on relayed skin bones, against malformed packets. */
    public static final int MAX_BONES = 512;

    private SkateNetwork() {}

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MineSkate3.MODID, path);
    }

    private static float[] readPose(ByteBuf buf) {
        float[] data = new float[POSE_FLOATS];
        for (int i = 0; i < POSE_FLOATS; i++) {
            data[i] = buf.readFloat();
        }
        return data;
    }

    private static void writePose(ByteBuf buf, float[] data) {
        for (int i = 0; i < POSE_FLOATS; i++) {
            buf.writeFloat(i < data.length ? data[i] : 0f);
        }
    }

    /**
     * The skin's bones (12 values each, relative to the player's feet) as half
     * floats, with the bone layout's fingerprint. Receivers skin their own copy
     * of the board and skater with them when the fingerprints match.
     */
    public record Bones(int layoutHash, short[] halves) {
        public static final Bones NONE = new Bones(0, new short[0]);

        public static Bones of(int layoutHash, float[] values, int count) {
            short[] halves = new short[count];
            for (int i = 0; i < count; i++) {
                halves[i] = Float.floatToFloat16(values[i]);
            }
            return new Bones(layoutHash, halves);
        }

        public float[] values() {
            float[] out = new float[halves.length];
            for (int i = 0; i < halves.length; i++) {
                out[i] = Float.float16ToFloat(halves[i]);
            }
            return out;
        }

        void write(ByteBuf buf) {
            buf.writeInt(layoutHash);
            ByteBufCodecs.VAR_INT.encode(buf, halves.length);
            for (short h : halves) {
                buf.writeShort(h);
            }
        }

        static Bones read(ByteBuf buf) {
            int hash = buf.readInt();
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            if (count < 0 || count > MAX_BONES * 12) {
                throw new IllegalArgumentException("Too many skate bones: " + count);
            }
            short[] halves = new short[count];
            for (int i = 0; i < count; i++) {
                halves[i] = buf.readShort();
            }
            return new Bones(hash, halves);
        }
    }

    /** Client to server: this player started or stopped skating. */
    public record State(boolean skating) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(id("state"));
        public static final StreamCodec<ByteBuf, State> CODEC = ByteBufCodecs.BOOL.map(State::new, State::skating);

        @Override
        public Type<State> type() {
            return TYPE;
        }
    }

    /** Client to server: this player's current pose. */
    public record Pose(float[] data, Bones bones) implements CustomPacketPayload {
        public static final Type<Pose> TYPE = new Type<>(id("pose"));
        public static final StreamCodec<ByteBuf, Pose> CODEC = StreamCodec.of(
                (buf, pose) -> {
                    writePose(buf, pose.data());
                    pose.bones().write(buf);
                },
                buf -> new Pose(readPose(buf), Bones.read(buf)));

        @Override
        public Type<Pose> type() {
            return TYPE;
        }
    }

    /** Server to client: another player started or stopped skating. */
    public record RemoteState(int entityId, boolean skating) implements CustomPacketPayload {
        public static final Type<RemoteState> TYPE = new Type<>(id("remote_state"));
        public static final StreamCodec<ByteBuf, RemoteState> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, RemoteState::entityId,
                ByteBufCodecs.BOOL, RemoteState::skating,
                RemoteState::new);

        @Override
        public Type<RemoteState> type() {
            return TYPE;
        }
    }

    /** Server to client: another skater's pose. */
    public record RemotePose(int entityId, float[] data, Bones bones) implements CustomPacketPayload {
        public static final Type<RemotePose> TYPE = new Type<>(id("remote_pose"));
        public static final StreamCodec<ByteBuf, RemotePose> CODEC = StreamCodec.of(
                (buf, pose) -> {
                    ByteBufCodecs.VAR_INT.encode(buf, pose.entityId());
                    writePose(buf, pose.data());
                    pose.bones().write(buf);
                },
                buf -> new RemotePose(ByteBufCodecs.VAR_INT.decode(buf), readPose(buf), Bones.read(buf)));

        @Override
        public Type<RemotePose> type() {
            return TYPE;
        }
    }

    /** Where the client side of the relay lands; set by the client entry point. */
    public interface ClientHandler {
        void remoteState(RemoteState state);

        void remotePose(RemotePose pose);
    }

    private static volatile ClientHandler clientHandler;

    public static void setClientHandler(ClientHandler handler) {
        clientHandler = handler;
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("2").optional();
        registrar.playToServer(State.TYPE, State.CODEC,
                (payload, context) -> context.enqueueWork(() -> SkateServer.onState(context.player(), payload.skating())));
        registrar.playToServer(Pose.TYPE, Pose.CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> SkateServer.onPose(context.player(), payload.data(), payload.bones())));
        registrar.playToClient(RemoteState.TYPE, RemoteState.CODEC, (payload, context) -> context.enqueueWork(() -> {
            ClientHandler handler = clientHandler;
            if (handler != null) {
                handler.remoteState(payload);
            }
        }));
        registrar.playToClient(RemotePose.TYPE, RemotePose.CODEC, (payload, context) -> context.enqueueWork(() -> {
            ClientHandler handler = clientHandler;
            if (handler != null) {
                handler.remotePose(payload);
            }
        }));
    }
}
