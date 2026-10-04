package dev.mineskate3.mixin.client;

import dev.mineskate3.client.SkateSession;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While skating, the view is Skate 3's own camera. NeoForge's camera angle
 * event fires before the position is set, so the whole camera is replaced at
 * the end of setup instead.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    private boolean detached;

    @Shadow
    protected abstract void setPosition(Vec3 position);

    @Shadow
    protected abstract void setRotation(float yaw, float pitch, float roll);

    @Inject(method = "setup", at = @At("TAIL"))
    private void mineskate3$skateCamera(BlockGetter level, Entity entity, boolean detached, boolean mirror,
            float partialTick, CallbackInfo ci) {
        SkateSession.CameraPose camera = SkateSession.get().camera();
        if (camera == null) {
            return;
        }
        this.detached = true;
        this.setRotation(camera.yaw(), camera.pitch(), camera.roll());
        this.setPosition(camera.position());
    }
}
