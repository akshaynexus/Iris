package net.irisshaders.iris.compat.sodium.config;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.mixin.MetalSupport;
import net.irisshaders.iris.gui.screen.ShaderPackScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PreferredGraphicsApi;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.TextAlignment;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

public class ShaderPackScreenPlaceholder extends Screen {
    private Screen parent;
    private MultiLineLabel message;
    private Component confirmation = Component.literal("Switch");

    public ShaderPackScreenPlaceholder(Screen i) {
        super(Component.literal("Iris"));

        parent = i;
    }

    public static Screen create(Screen parent) {
        return MetalSupport.shaderPacksBlocked()
                ? new ShaderPackScreenPlaceholder(parent) : new ShaderPackScreen(parent);
    }

    @Override
    protected void init() {
        super.init();
        boolean metal = MetalSupport.installed();
        boolean supported = !metal || mcopt.metal.PlatformCheck.isSupported();
        this.confirmation = metal ? Component.translatable("iris.backend.switch") : Component.literal("Switch");
        this.message = MultiLineLabel.create(this.font, metal ? Component.translatable(supported ? "iris.backend.metal.switch" : "iris.backend.metal.unsupported") : Component.literal("Iris cannot run when using Vulkan. Would you like to switch to OpenGL?\nThis will close your game."), this.width - 50);
        int textSize = (this.message.getLineCount() + 1) * 9;

        if (supported) this.addRenderableWidget(
                Button.builder(this.confirmation, this::switchBackend)
                        .bounds(this.width / 2 - 155, 100 + textSize, 150, 20)
                        .build()
        );
        Button skipAndJoinButton = Button.builder(metal ? Component.translatable("iris.backend.return") : Component.literal("Return"), i -> onClose())
                .bounds(this.width / 2 - 155 + 160, 100 + textSize, 150, 20)
                .build();
        this.addRenderableWidget(skipAndJoinButton);
    }

    private void switchBackend(Button button) {
        if (MetalSupport.installed()) {
            try {
                mcopt.metal.Profile.selectMetal();
            } catch (java.io.IOException e) {
                Iris.logger.error("Could not save Metal backend selection", e);
                this.message = MultiLineLabel.create(this.font, Component.translatable("iris.backend.metal.saveFailed"), this.width - 50);
                return;
            }
        }
        Minecraft.getInstance().options.preferredGraphicsBackend().set(MetalSupport.installed() ? PreferredGraphicsApi.DEFAULT : PreferredGraphicsApi.OPENGL);
        Minecraft.getInstance().options.save();

        if (Minecraft.getInstance().isLocalServer() && Minecraft.getInstance().getSingleplayerServer() != null) {
            Minecraft.getInstance().getSingleplayerServer().halt(true);
        }

        Minecraft.getInstance().disconnectWithSavingScreen();
        Minecraft.getInstance().stop();
    }

    @Override
    public void extractRenderState(final GuiGraphicsExtractor graphics, final int mouseX, final int mouseY, final float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        ActiveTextCollector textRenderer = graphics.textRenderer();
        graphics.centeredText(this.font, this.title, this.width / 2, 50, -1);
        this.message.visitLines(TextAlignment.CENTER, this.width / 2, 70, 9, textRenderer);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
