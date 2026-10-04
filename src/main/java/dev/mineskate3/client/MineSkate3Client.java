package dev.mineskate3.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mineskate3.MineSkate3;
import dev.mineskate3.network.SkateNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.lwjgl.glfw.GLFW;

/** Client entry point: the J key, input capture, camera, rendering and HUD. */
@Mod(value = MineSkate3.MODID, dist = Dist.CLIENT)
public final class MineSkate3Client {
    public static final KeyMapping TOGGLE = new KeyMapping("key.mineskate3.toggle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.mineskate3");

    private long lastFrame = System.nanoTime();

    public MineSkate3Client(IEventBus modBus) {
        modBus.addListener(this::onRegisterKeys);
        SkateNetwork.setClientHandler(new SkateNetwork.ClientHandler() {
            @Override
            public void remoteState(SkateNetwork.RemoteState state) {
                SkaterRenderer.remoteState(state);
            }

            @Override
            public void remotePose(SkateNetwork.RemotePose pose) {
                SkaterRenderer.remotePose(pose);
            }
        });
        NeoForge.EVENT_BUS.addListener(this::onClientTickPre);
        NeoForge.EVENT_BUS.addListener(this::onClientTickPost);
        NeoForge.EVENT_BUS.addListener(this::onPlayerTick);
        NeoForge.EVENT_BUS.addListener(this::onRenderFrame);
        NeoForge.EVENT_BUS.addListener(this::onMovementInput);
        NeoForge.EVENT_BUS.addListener(this::onInteraction);
        NeoForge.EVENT_BUS.addListener(this::onRenderLevel);
        NeoForge.EVENT_BUS.addListener(this::onRenderPlayer);
        NeoForge.EVENT_BUS.addListener(this::onRenderHand);
        NeoForge.EVENT_BUS.addListener(this::onComputeFov);
        NeoForge.EVENT_BUS.addListener(this::onRenderGui);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
    }

    private void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
    }

    /** While skating, keys Skate uses must not also open the inventory, drop items and so on. */
    private void onClientTickPre(ClientTickEvent.Pre event) {
        if (!SkateSession.get().active()) {
            return;
        }
        var options = Minecraft.getInstance().options;
        for (KeyMapping key : new KeyMapping[] {options.keyInventory, options.keyDrop, options.keySwapOffhand,
                options.keyPlayerList, options.keyJump, options.keyShift}) {
            while (key.consumeClick()) {
                // swallowed
            }
        }
        for (KeyMapping key : options.keyHotbarSlots) {
            while (key.consumeClick()) {
                // swallowed
            }
        }
    }

    private void onClientTickPost(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (TOGGLE.consumeClick()) {
            if (mc.screen == null) {
                SkateSession.get().toggle();
            }
        }
        SkateSession.get().tick();
    }

    private void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof LocalPlayer player && player == Minecraft.getInstance().player) {
            SkateSession.get().afterPlayerTick(player);
        }
    }

    private void onRenderFrame(RenderFrameEvent.Pre event) {
        long now = System.nanoTime();
        float dt = (float) Math.min(0.1, (now - lastFrame) / 1e9);
        lastFrame = now;
        SkateSession.get().frame(dt);
    }

    private void onMovementInput(MovementInputUpdateEvent event) {
        if (SkateSession.get().active()) {
            Input input = event.getInput();
            input.up = input.down = input.left = input.right = false;
            input.jumping = input.shiftKeyDown = false;
            input.forwardImpulse = 0;
            input.leftImpulse = 0;
        }
    }

    private void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (SkateSession.get().active()) {
            event.setSwingHand(false);
            event.setCanceled(true);
        }
    }

    private void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            SkaterRenderer.renderLocal(event.getPoseStack(), event.getCamera().getPosition());
        }
    }

    private void onRenderPlayer(RenderPlayerEvent.Pre event) {
        if (!(event.getEntity() instanceof AbstractClientPlayer player)) {
            return;
        }
        if (player == Minecraft.getInstance().player) {
            if (SkateSession.get().active()) {
                event.setCanceled(true);
            }
            return;
        }
        if (SkaterRenderer.renderRemote(player, event.getPoseStack(), event.getMultiBufferSource(),
                event.getPackedLight())) {
            event.setCanceled(true);
        }
    }

    private void onRenderHand(RenderHandEvent event) {
        if (SkateSession.get().active()) {
            event.setCanceled(true);
        }
    }

    private void onComputeFov(ViewportEvent.ComputeFov event) {
        SkateSession.CameraPose camera = SkateSession.get().camera();
        if (camera != null) {
            event.setFOV(camera.fov());
        }
    }

    private void onRenderGui(RenderGuiEvent.Post event) {
        SkateHud.render(event.getGuiGraphics());
    }

    private void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SkateSession.get().onLogout();
        SkaterRenderer.clearRemotes();
    }
}
