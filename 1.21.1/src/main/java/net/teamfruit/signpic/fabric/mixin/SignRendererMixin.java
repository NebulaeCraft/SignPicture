package net.teamfruit.signpic.fabric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.Arrays;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.Model;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.SignRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.WoodType;
import net.minecraft.world.phys.Vec3;
import net.teamfruit.signpic.fabric.ImageCache.Image;
import net.teamfruit.signpic.fabric.SignPicture;
import net.teamfruit.signpic.fabric.SignPictureClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SignRenderer.class)
abstract class SignRendererMixin implements BlockEntityRenderer<SignBlockEntity> {
    @Shadow abstract void translateSign(PoseStack pose, float angle, BlockState state);
    @Shadow abstract Vec3 getTextOffset();
    @Shadow abstract void renderSign(PoseStack pose, MultiBufferSource buffers, int light, int overlay,
                                      WoodType wood, Model model);
    @Shadow abstract void renderSignText(BlockPos pos, SignText text, PoseStack pose, MultiBufferSource buffers,
                                          int light, int lineHeight, int lineWidth, boolean front);

    @Inject(method = "renderSignWithText", at = @At("HEAD"), cancellable = true)
    private void signpic$renderPicture(SignBlockEntity sign, PoseStack pose, MultiBufferSource buffers,
                                       int light, int overlay, BlockState state, SignBlock block,
                                       WoodType wood, Model model, CallbackInfo ci) {
        SignText frontText = sign.getFrontText();
        SignText backText = sign.getBackText();
        SignPicture front = SignPicture.from(frontText);
        SignPicture back = SignPicture.from(backText);
        Image frontImage = front == null ? null : SignPictureClient.IMAGES.get(front.source());
        Image backImage = back == null ? null : SignPictureClient.IMAGES.get(back.source());
        if (frontImage == null && backImage == null) {
            return;
        }

        boolean filtered = Minecraft.getInstance().isTextFilteringEnabled();
        boolean showFrontText = frontImage == null && signpic$hasText(frontText, filtered);
        boolean showBackText = backImage == null && signpic$hasText(backText, filtered);
        boolean showBoard = showFrontText || showBackText;
        boolean twoSided = !showBoard && (frontImage == null || backImage == null);
        float faceZ = showBoard ? (float) getTextOffset().z + 0.001f : 0;
        float baseY = block instanceof WallSignBlock ? 0.3125f : 0;

        pose.pushPose();
        try {
            translateSign(pose, -block.getYRotationDegrees(state), state);
            if (showBoard) {
                renderSign(pose, buffers, light, overlay, wood, model);
            }
            if (frontImage != null) {
                front.layout().draw(pose, buffers, light, frontImage, true, twoSided, baseY, faceZ);
            } else if (showFrontText) {
                renderSignText(sign.getBlockPos(), frontText, pose, buffers, light,
                        sign.getTextLineHeight(), sign.getMaxTextLineWidth(), true);
            }
            if (backImage != null) {
                back.layout().draw(pose, buffers, light, backImage, false, twoSided, baseY, faceZ);
            } else if (showBackText) {
                renderSignText(sign.getBlockPos(), backText, pose, buffers, light,
                        sign.getTextLineHeight(), sign.getMaxTextLineWidth(), false);
            }
        } finally {
            pose.popPose();
        }
        ci.cancel();
    }

    private static boolean signpic$hasText(SignText text, boolean filtered) {
        return Arrays.stream(text.getMessages(filtered)).anyMatch(line -> !line.getString().isEmpty());
    }

    @Override
    public boolean shouldRender(SignBlockEntity sign, Vec3 camera) {
        return BlockEntityRenderer.super.shouldRender(sign, camera)
                || (Vec3.atCenterOf(sign.getBlockPos()).closerThan(camera, 128)
                    && (SignPicture.from(sign.getFrontText()) != null || SignPicture.from(sign.getBackText()) != null));
    }

    @Override
    public boolean shouldRenderOffScreen(SignBlockEntity sign) {
        return SignPicture.from(sign.getFrontText()) != null || SignPicture.from(sign.getBackText()) != null;
    }
}
