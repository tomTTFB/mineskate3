package dev.mineskate3.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.mineskate3.MineSkate3;
import dev.mineskate3.network.SkateNetwork;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import java.util.Set;
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
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.fml.ModContainer;
import dev.mineskate3.client.setup.SetupScreen;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.ChunkEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import org.lwjgl.glfw.GLFW;

/** Client entry point: the J key, input capture, camera, rendering and HUD. */
@Mod(value = MineSkate3.MODID, dist = Dist.CLIENT)
public final class MineSkate3Client {
    public static final KeyMapping TOGGLE = new KeyMapping("key.mineskate3.toggle",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_J, "key.categories.mineskate3");
    public static final KeyMapping TRICK_GUIDE = new KeyMapping("key.mineskate3.trick_guide",
            InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_G, "key.categories.mineskate3");
    private int lastPadButtons;

    /** Survival HUD pieces that make no sense on a board; Skate 3 shows only its own. */
    private static final Set<ResourceLocation> HIDDEN_WHILE_SKATING = Set.of(
            VanillaGuiLayers.CROSSHAIR, VanillaGuiLayers.HOTBAR, VanillaGuiLayers.JUMP_METER,
            VanillaGuiLayers.EXPERIENCE_BAR, VanillaGuiLayers.EXPERIENCE_LEVEL, VanillaGuiLayers.PLAYER_HEALTH,
            VanillaGuiLayers.ARMOR_LEVEL, VanillaGuiLayers.FOOD_LEVEL, VanillaGuiLayers.AIR_LEVEL,
            VanillaGuiLayers.SELECTED_ITEM_NAME, VanillaGuiLayers.VEHICLE_HEALTH);

    private long lastFrame = System.nanoTime();

    public MineSkate3Client(IEventBus modBus, ModContainer container) {
        modBus.addListener(this::onRegisterKeys);
        modBus.addListener(TrickHud::registerShaders);
        // Mods > MineSkate 3 > Config opens the Skate 3 setup (convert, add the trick HUD).
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> new SetupScreen(parent));
        SkateNetwork.setClientHandler(new SkateNetwork.ClientHandler() {
            @Override
            public void remoteState(SkateNetwork.RemoteState state) {
                SkaterRenderer.remoteState(state);
            }

            @Override
            public void remotePose(SkateNetwork.RemotePose pose) {
                SkaterRenderer.remotePose(pose);
            }

            @Override
            public void stopped(SkateNetwork.Stop stop) {
                SkateSession.get().forcedStop(stop.reason());
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
        NeoForge.EVENT_BUS.addListener(this::onRenderGuiLayer);
        NeoForge.EVENT_BUS.addListener(this::onLogout);
        NeoForge.EVENT_BUS.addListener(this::onChunkLoad);
    }

    private void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof net.minecraft.world.level.Level level && level.isClientSide()) {
            SkateSession.get().chunkLoaded(level, event.getChunk().getPos());
        }
    }

    private void onRegisterKeys(RegisterKeyMappingsEvent event) {
        event.register(TOGGLE);
        event.register(TRICK_GUIDE);
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
        while (TRICK_GUIDE.consumeClick()) {
            if (mc.screen == null) {
                TrickGuideScreen.open();
            }
        }
        // Back/View on a controller opens the guide while skating, as Skate's pause menu reaches it.
        PadInput pad = SkateSession.get().pad();
        int pressed = pad.buttons & ~lastPadButtons;
        lastPadButtons = pad.buttons;
        if (SkateSession.get().active() && mc.screen == null && pad.controllerName != null
                && (pressed & PadInput.BACK) != 0) {
            TrickGuideScreen.open();
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
        if (!Minecraft.getInstance().options.hideGui) {
            TrickHud.render(event.getGuiGraphics());
            MarkerHud.render(event.getGuiGraphics());
        }
        SkateHud.render(event.getGuiGraphics());
    }

    private void onRenderGuiLayer(RenderGuiLayerEvent.Pre event) {
        if (SkateSession.get().active() && HIDDEN_WHILE_SKATING.contains(event.getName())) {
            event.setCanceled(true);
        }
    }

    private void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        SkateSession.get().onLogout();
        SkaterRenderer.clearRemotes();
    }
}
