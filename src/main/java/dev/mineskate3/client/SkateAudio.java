package dev.mineskate3.client;

import dev.mineskate3.SkateSounds;
import dev.mineskate3.network.SkateNetwork;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;

/**
 * Skate sounds, driven by what the Skate engine says each skater is doing:
 * a rolling loop while wheels are down, grind or slide loops on rails and
 * ledges, and one-shots for pops, landings, locking into grinds and bails.
 * The local skater is fed every frame; other skaters by their relayed status.
 */
public final class SkateAudio {
    private static final SkaterSounds LOCAL = new SkaterSounds();
    private static final Map<Integer, SkaterSounds> REMOTES = new HashMap<>();
    private static float lastPlaced = -1;
    private static float lastReturned = -1;

    private SkateAudio() {}

    /** A looping sound whose volume and pitch follow the skater. */
    private static final class Loop extends AbstractTickableSoundInstance {
        private float targetVolume;
        private float targetPitch = 1f;
        private boolean finished;

        Loop(SoundEvent event) {
            super(event, SoundSource.PLAYERS, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0f;
            this.attenuation = SoundInstance.Attenuation.LINEAR;
        }

        void set(float volume, float pitch, double x, double y, double z) {
            this.targetVolume = volume;
            this.targetPitch = pitch;
            this.x = x;
            this.y = y;
            this.z = z;
        }

        void finish() {
            finished = true;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }

        @Override
        public void tick() {
            if (finished) {
                stop();
                return;
            }
            // Smooth over the 20 Hz tick so speed changes do not step.
            this.volume += (targetVolume - this.volume) * 0.5f;
            this.pitch += (targetPitch - this.pitch) * 0.5f;
        }
    }

    /** One skater's sound state. */
    private static final class SkaterSounds {
        int lastState = -1;
        Loop roll;
        Loop grind;
        Loop slide;

        private static boolean ground(int s) {
            return s >= 100 && s <= 103;
        }

        private static boolean air(int s) {
            return s >= 200 && s <= 202;
        }

        private static boolean grinding(int s) {
            return s >= 400 && s <= 405;
        }

        /** Boardslides and tipslides are wood on the edge; the rest are trucks. */
        private static boolean woodSlide(int s) {
            return s == 400 || s == 402;
        }

        private Loop ensure(Loop loop, SoundEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (loop == null || loop.isStopped() || !mc.getSoundManager().isActive(loop)) {
                if (loop != null) {
                    loop.finish();
                }
                loop = new Loop(event);
                mc.getSoundManager().play(loop);
            }
            return loop;
        }

        void update(int state, int wheels, float impact, boolean wiping, float speed, double x, double y, double z) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || !SkateSettings.sounds()) {
                stop();
                return;
            }
            int previous = lastState;
            lastState = state;
            boolean wipe = state == 300 || wiping;

            float rolling = ground(state) && wheels >= 2 && !wipe ? Math.min(speed / 7f, 1f) : 0f;
            roll = ensure(roll, SkateSounds.ROLL.get());
            roll.set(rolling * 0.8f, 0.75f + Math.min(speed / 14f, 0.6f), x, y, z);

            boolean onRail = grinding(state) && !wipe;
            float railVolume = onRail ? 0.35f + Math.min(speed / 8f, 0.55f) : 0f;
            grind = ensure(grind, SkateSounds.GRIND.get());
            grind.set(onRail && !woodSlide(state) ? railVolume : 0f, 0.85f + Math.min(speed / 20f, 0.3f), x, y, z);
            slide = ensure(slide, SkateSounds.SLIDE.get());
            slide.set(onRail && woodSlide(state) ? railVolume : 0f, 0.9f + Math.min(speed / 25f, 0.25f), x, y, z);

            if (previous < 0 || previous == state) {
                return;
            }
            if (wipe && previous != 300) {
                play(SkateSounds.BAIL.get(), 1f, 1f, x, y, z);
            } else if (air(state) && (ground(previous) || grinding(previous))) {
                play(SkateSounds.POP.get(), 0.9f, 0.95f + (float) Math.random() * 0.1f, x, y, z);
            } else if (air(previous) && (ground(state) || grinding(state))) {
                boolean hard = impact > 5f;
                float volume = 0.55f + Math.min(impact / 8f, 0.45f);
                play(hard ? SkateSounds.LAND_HARD.get() : SkateSounds.LAND.get(), volume,
                        0.95f + (float) Math.random() * 0.1f, x, y, z);
            }
            if (grinding(state) && !grinding(previous)) {
                play(SkateSounds.LOCK.get(), 0.8f, woodSlide(state) ? 0.75f : 1f, x, y, z);
            }
        }

        void stop() {
            for (Loop loop : new Loop[] {roll, grind, slide}) {
                if (loop != null) {
                    loop.finish();
                }
            }
            roll = grind = slide = null;
            lastState = -1;
        }
    }

    private static void play(SoundEvent event, float volume, float pitch, double x, double y, double z) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) {
            mc.level.playLocalSound(x, y, z, event, SoundSource.PLAYERS, volume, pitch, false);
        }
    }

    /** The local skater, from the newest pose. */
    public static void local(float[] pose, double x, double y, double z) {
        int st = NativeSkate.STATUS;
        LOCAL.update((int) pose[st], (int) pose[st + 1], pose[st + 2], pose[st + 3] != 0f,
                SkaterRenderer.speed(pose), x, y, z);
        float placed = pose[st + 8];
        float returned = pose[st + 9];
        if (SkateSettings.sounds()) {
            if (lastPlaced >= 0 && placed > lastPlaced) {
                play(SkateSounds.MARKER_PLACE.get(), 0.7f, 1f, x, y, z);
            }
            if (lastReturned >= 0 && returned > lastReturned) {
                play(SkateSounds.MARKER_RETURN.get(), 0.8f, 1f, x, y, z);
            }
        }
        lastPlaced = placed;
        lastReturned = returned;
    }

    public static void stopLocal() {
        LOCAL.stop();
        lastPlaced = lastReturned = -1;
    }

    /** Another skater, from their relayed status. */
    public static void remote(int entityId, float[] data) {
        Minecraft mc = Minecraft.getInstance();
        Entity entity = mc.level != null ? mc.level.getEntity(entityId) : null;
        if (entity == null) {
            removeRemote(entityId);
            return;
        }
        int st = SkateNetwork.STATUS_AT;
        REMOTES.computeIfAbsent(entityId, id -> new SkaterSounds()).update((int) data[st], (int) data[st + 1],
                data[st + 2], data[st + 3] != 0f, data[st + 4], entity.getX(), entity.getY(), entity.getZ());
    }

    public static void removeRemote(int entityId) {
        SkaterSounds sounds = REMOTES.remove(entityId);
        if (sounds != null) {
            sounds.stop();
        }
    }

    public static void clear() {
        for (Iterator<SkaterSounds> it = REMOTES.values().iterator(); it.hasNext();) {
            it.next().stop();
            it.remove();
        }
        stopLocal();
    }
}
