package net.teamfruit.signpic.fabric;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.entity.SignBlockEntity;

public final class SignPictureClient implements ClientModInitializer {
    public static final ImageCache IMAGES = new ImageCache();

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(IMAGES::prune);
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClientSide || player.isSpectator() || hand != InteractionHand.MAIN_HAND
                    || !player.getMainHandItem().isEmpty() || !player.getOffhandItem().isEmpty()
                    || !(world.getBlockEntity(hit.getBlockPos()) instanceof SignBlockEntity sign)) {
                return InteractionResult.PASS;
            }
            boolean front = sign.isFacingFrontText(player);
            var text = sign.getText(front);
            SignPicture picture = SignPicture.from(text);
            if (picture == null && !text.hasMessage(player)) {
                picture = SignPicture.from(sign.getText(!front));
            }
            if (picture == null) {
                return InteractionResult.PASS;
            }
            Minecraft.getInstance().setScreen(new SignPictureScreen(picture.source()));
            return InteractionResult.FAIL;
        });
    }
}
