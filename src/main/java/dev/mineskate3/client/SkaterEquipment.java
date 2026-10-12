package dev.mineskate3.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.armortrim.ArmorTrim;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.extensions.common.IClientItemExtensions;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Armour and held items on a skater, drawn on the same six part matrices that
 * pose the player model (and, as a stand-in, over the Skate 3 skater too).
 * Armour uses the vanilla armour boxes with the vanilla textures, tints, trims
 * and glint; mods' custom armour models are not used here.
 */
final class SkaterEquipment {
    private static final int HEAD = 0;
    private static final int BODY = 1;
    private static final int RIGHT_ARM = 2;
    private static final int LEFT_ARM = 3;
    private static final int RIGHT_LEG = 4;
    private static final int LEFT_LEG = 5;
    private static final EquipmentSlot[] ARMOUR = {
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD,
    };

    private static HumanoidModel<AbstractClientPlayer> inner;
    private static HumanoidModel<AbstractClientPlayer> outer;

    private SkaterEquipment() {}

    static void render(AbstractClientPlayer player, Matrix4f[] parts, PoseStack poseStack, MultiBufferSource buffers,
            int light, double ox, double oy, double oz) {
        if (player == null) {
            return;
        }
        for (EquipmentSlot slot : ARMOUR) {
            armour(player, slot, parts, poseStack, buffers, light, ox, oy, oz);
        }
        boolean rightMain = player.getMainArm() == HumanoidArm.RIGHT;
        held(player, rightMain ? player.getMainHandItem() : player.getOffhandItem(), HumanoidArm.RIGHT,
                parts[RIGHT_ARM], poseStack, buffers, light, ox, oy, oz);
        held(player, rightMain ? player.getOffhandItem() : player.getMainHandItem(), HumanoidArm.LEFT,
                parts[LEFT_ARM], poseStack, buffers, light, ox, oy, oz);
    }

    private static HumanoidModel<AbstractClientPlayer> model(boolean innerLayer) {
        if (inner == null) {
            var models = Minecraft.getInstance().getEntityModels();
            inner = new HumanoidModel<>(models.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
            outer = new HumanoidModel<>(models.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        }
        return innerLayer ? inner : outer;
    }

    private static void armour(AbstractClientPlayer player, EquipmentSlot slot, Matrix4f[] parts,
            PoseStack poseStack, MultiBufferSource buffers, int light, double ox, double oy, double oz) {
        ItemStack stack = player.getItemBySlot(slot);
        if (!(stack.getItem() instanceof ArmorItem item) || item.getEquipmentSlot() != slot) {
            return;
        }
        boolean innerLayer = slot == EquipmentSlot.LEGS;
        HumanoidModel<AbstractClientPlayer> model = model(innerLayer);
        Holder<ArmorMaterial> material = item.getMaterial();
        IClientItemExtensions extensions = IClientItemExtensions.of(stack);
        int fallback = extensions.getDefaultDyeColor(stack);
        for (int i = 0; i < material.value().layers().size(); i++) {
            ArmorMaterial.Layer layer = material.value().layers().get(i);
            int color = extensions.getArmorLayerTintColor(stack, player, layer, i, fallback);
            if (color != 0) {
                var texture = ClientHooks.getArmorTexture(player, stack, layer, innerLayer, slot);
                pieces(model, slot, parts, poseStack, buffers.getBuffer(RenderType.armorCutoutNoCull(texture)),
                        light, color, ox, oy, oz);
            }
        }
        ArmorTrim trim = stack.get(DataComponents.TRIM);
        if (trim != null) {
            TextureAtlasSprite sprite = Minecraft.getInstance().getModelManager().getAtlas(Sheets.ARMOR_TRIMS_SHEET)
                    .getSprite(innerLayer ? trim.innerTexture(material) : trim.outerTexture(material));
            VertexConsumer consumer = sprite.wrap(buffers.getBuffer(Sheets.armorTrimsSheet(
                    trim.pattern().value().decal())));
            pieces(model, slot, parts, poseStack, consumer, light, -1, ox, oy, oz);
        }
        if (stack.hasFoil()) {
            pieces(model, slot, parts, poseStack, buffers.getBuffer(RenderType.armorEntityGlint()), light, -1,
                    ox, oy, oz);
        }
    }

    /** The armour boxes a slot covers, as HumanoidArmorLayer picks them. */
    private static void pieces(HumanoidModel<AbstractClientPlayer> model, EquipmentSlot slot, Matrix4f[] parts,
            PoseStack poseStack, VertexConsumer consumer, int light, int color, double ox, double oy, double oz) {
        switch (slot) {
            case HEAD -> {
                piece(model.head, parts[HEAD], poseStack, consumer, light, color, ox, oy, oz);
                piece(model.hat, parts[HEAD], poseStack, consumer, light, color, ox, oy, oz);
            }
            case CHEST -> {
                piece(model.body, parts[BODY], poseStack, consumer, light, color, ox, oy, oz);
                piece(model.rightArm, parts[RIGHT_ARM], poseStack, consumer, light, color, ox, oy, oz);
                piece(model.leftArm, parts[LEFT_ARM], poseStack, consumer, light, color, ox, oy, oz);
            }
            case LEGS -> {
                piece(model.body, parts[BODY], poseStack, consumer, light, color, ox, oy, oz);
                piece(model.rightLeg, parts[RIGHT_LEG], poseStack, consumer, light, color, ox, oy, oz);
                piece(model.leftLeg, parts[LEFT_LEG], poseStack, consumer, light, color, ox, oy, oz);
            }
            case FEET -> {
                piece(model.rightLeg, parts[RIGHT_LEG], poseStack, consumer, light, color, ox, oy, oz);
                piece(model.leftLeg, parts[LEFT_LEG], poseStack, consumer, light, color, ox, oy, oz);
            }
            default -> {}
        }
    }

    private static void piece(ModelPart part, Matrix4f matrix, PoseStack poseStack, VertexConsumer consumer,
            int light, int color, double ox, double oy, double oz) {
        SkaterRenderer.part(part, matrix, poseStack, consumer, light, OverlayTexture.NO_OVERLAY, color, ox, oy, oz,
                true);
    }

    /**
     * An item in a hand, placed as ItemInHandLayer does: 10px down the arm
     * (which the part matrix stretches to the skater's knuckles), turned to
     * the third person hand pose, without the arm's stretch.
     */
    private static void held(AbstractClientPlayer player, ItemStack stack, HumanoidArm arm, Matrix4f armMatrix,
            PoseStack poseStack, MultiBufferSource buffers, int light, double ox, double oy, double oz) {
        if (stack.isEmpty() || armMatrix.m33() == 0f) {
            return;
        }
        boolean left = arm == HumanoidArm.LEFT;
        // Vanilla's rotate X -90, rotate Y 180, translate (±1/16, 2/16, -10/16), in arm space.
        Vector3f hand = armMatrix.transformPosition(new Vector3f(left ? 1f / 16f : -1f / 16f, 0.625f, -0.125f));
        Quaternionf turn = new Quaternionf().setFromNormalized(SkaterRenderer.normalMatrix(armMatrix))
                .rotateX((float) Math.toRadians(-90)).rotateY((float) Math.PI);
        poseStack.pushPose();
        PoseStack.Pose last = poseStack.last();
        last.pose().translate((float) ox, (float) oy, (float) oz).translate(hand).rotate(turn);
        last.normal().rotate(turn);
        Minecraft.getInstance().getEntityRenderDispatcher().getItemInHandRenderer().renderItem(player, stack,
                left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND, left,
                poseStack, buffers, light);
        poseStack.popPose();
    }
}
