package net.teamfruit.signpic.fabric;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class SignPictureScreen extends Screen {
    private final String source;
    private Button reload;
    private Button redownload;
    private boolean busy;

    SignPictureScreen(String source) {
        super(Component.translatable("signpic.retry.title"));
        this.source = source;
    }

    @Override
    protected void init() {
        int y = height / 2 + 8;
        reload = addRenderableWidget(Button.builder(Component.translatable("signpic.retry.reload"),
                button -> retry(false)).bounds(width / 2 - 104, y, 100, 20).build());
        redownload = addRenderableWidget(Button.builder(Component.translatable("signpic.retry.redownload"),
                button -> retry(true)).bounds(width / 2 + 4, y, 100, 20).build());
        updateButtons();
    }

    private void retry(boolean force) {
        busy = !SignPictureClient.IMAGES.retry(source, force);
        updateButtons();
    }

    private void updateButtons() {
        reload.active = SignPictureClient.IMAGES.status(source) != ImageCache.Status.LOADING;
        redownload.active = reload.active && !source.startsWith("!");
    }

    @Override
    public void tick() {
        updateButtons();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 33, 0xFFFFFF);
        String status = switch (SignPictureClient.IMAGES.status(source)) {
            case IDLE -> "idle";
            case LOADING -> "loading";
            case READY -> "ready";
            case FAILED -> "failed";
        };
        if (busy) {
            status = "busy";
        }
        graphics.drawCenteredString(font, Component.translatable("signpic.retry." + status),
                width / 2, height / 2 - 15, 0xCCCCCC);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
