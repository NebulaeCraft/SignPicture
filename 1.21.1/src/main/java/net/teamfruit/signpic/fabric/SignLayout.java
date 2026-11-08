package net.teamfruit.signpic.fabric;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public final class SignLayout {
    private static final float TURN_UNIT = (float) (Math.PI / 4);
    private static final String NUMBER = "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?";
    private static final Pattern PROPERTY = Pattern.compile("([a-zA-Z])(" + NUMBER + ")?|(" + NUMBER + ")");
    private static final Pattern FRAME = Pattern.compile("\\((?:([^)]*?)~)?([^)]*)\\)");

    private float width = Float.NaN;
    private float height = Float.NaN;
    private final Vector3f offset = new Vector3f();
    private final Vector3f pivot = new Vector3f();
    private final Quaternionf rotation = new Quaternionf();
    private final Vector3f rotationAxis = new Vector3f();
    private float rotationAngle;
    private float u;
    private float v;
    private float uvWidth = 1;
    private float uvHeight = 1;
    private float columns = 1;
    private float rows = 1;
    private float opacity = 10;
    private int blockLight = -1;
    private int skyLight = -1;

    SignLayout(String metadata) {
        StringBuilder initial = new StringBuilder(FRAME.matcher(metadata).replaceAll(""));
        Matcher frames = FRAME.matcher(metadata);
        float time = 0;
        while (frames.find()) {
            time += number(frames.group(1), 0);
            if (time == 0) {
                initial.append(frames.group(2));
            }
        }

        Matcher properties = PROPERTY.matcher(initial);
        if (properties.lookingAt() && properties.group(3) != null) {
            width = number(properties.group(3), Float.NaN);
        }
        properties.reset();
        while (properties.find()) {
            if (properties.group(1) != null) {
                readProperty(properties.group(1).charAt(0), properties.group(2));
            }
        }
        if (rotationAngle != 0 && rotationAxis.lengthSquared() > 0) {
            rotation.premul(new Quaternionf().rotationAxis(rotationAngle, rotationAxis));
        }
    }

    private void readProperty(char key, String raw) {
        float shift = number(raw, raw == null ? 0.5f : 0);
        float turn = number(raw, 4) * TURN_UNIT;
        switch (key) {
            case 'x' -> height = number(raw, Float.NaN);
            case 'L' -> offset.x -= shift;
            case 'R' -> offset.x += shift;
            case 'D' -> offset.y -= shift;
            case 'U' -> offset.y += shift;
            case 'B' -> offset.z -= shift;
            case 'F' -> offset.z += shift;
            case 'M' -> pivot.x += shift;
            case 'N' -> pivot.y += shift;
            case 'O' -> pivot.z += shift;
            case 'X' -> rotation.rotateLocalX(turn);
            case 'Y' -> rotation.rotateLocalY(turn);
            case 'Z' -> rotation.rotateLocalZ(turn);
            case 'A' -> rotationAngle += number(raw, 0) * TURN_UNIT;
            case 'I' -> rotationAxis.x += number(raw, 1);
            case 'J' -> rotationAxis.y += number(raw, 1);
            case 'K' -> rotationAxis.z += number(raw, 1);
            case 'u' -> u = number(raw, 0);
            case 'v' -> v = number(raw, 0);
            case 'w' -> uvWidth = number(raw, 1);
            case 'h' -> uvHeight = number(raw, 1);
            case 'c' -> columns = number(raw, 1);
            case 's' -> rows = number(raw, 1);
            case 'o' -> opacity = number(raw, 10);
            case 'f' -> blockLight = (int) number(raw, -1);
            case 'g' -> skyLight = (int) number(raw, -1);
            default -> { }
        }
    }

    private static float number(String raw, float fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            float value = Float.parseFloat(raw);
            return Float.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public void draw(PoseStack pose, MultiBufferSource buffers, int light, ImageCache.Image image,
                     boolean front, boolean twoSided, float baseY, float faceZ) {
        float aspect = (float) image.width() / image.height();
        float w = width;
        float h = height;
        if (Float.isNaN(h)) {
            h = Float.isNaN(w) ? 1 : w / aspect;
        }
        if (Float.isNaN(w)) {
            w = h * aspect;
        }
        if (!Float.isFinite(w) || !Float.isFinite(h) || w == 0 || h == 0 || columns == 0 || rows == 0) {
            return;
        }

        pose.pushPose();
        try {
            if (!front) {
                pose.mulPose(Axis.YP.rotationDegrees(180));
            }
            pose.translate(offset.x, baseY + offset.y, offset.z + faceZ);
            pose.translate(pivot.x, pivot.y, pivot.z);
            pose.mulPose(rotation);
            pose.translate(-pivot.x, -pivot.y, -pivot.z);

            float right = Math.abs(w) / 2;
            float bottom = -0.5f;
            float top = bottom + Math.abs(h);
            float u2 = u + uvWidth / columns;
            float v2 = v + uvHeight / rows;
            float leftU = w < 0 ? u2 : u;
            float rightU = w < 0 ? u : u2;
            float topV = h < 0 ? v2 : v;
            float bottomV = h < 0 ? v : v2;
            int alpha = Math.round(Mth.clamp(opacity / 10, 0, 1) * 255);
            int block = blockLight < 0 ? LightTexture.block(light) : Mth.clamp(blockLight, 0, 15);
            int sky = skyLight < 0 ? LightTexture.sky(light) : Mth.clamp(skyLight, 0, 15);
            int imageLight = LightTexture.pack(block, sky);

            RenderType layer = twoSided ? RenderType.entityTranslucent(image.texture(), false)
                    : RenderType.entityTranslucentCull(image.texture());
            VertexConsumer vertices = buffers.getBuffer(layer);
            PoseStack.Pose matrix = pose.last();
            vertex(vertices, matrix, -right, top, leftU, topV, alpha, imageLight);
            vertex(vertices, matrix, -right, bottom, leftU, bottomV, alpha, imageLight);
            vertex(vertices, matrix, right, bottom, rightU, bottomV, alpha, imageLight);
            vertex(vertices, matrix, right, top, rightU, topV, alpha, imageLight);
        } finally {
            pose.popPose();
        }
    }

    private static void vertex(VertexConsumer vertices, PoseStack.Pose pose, float x, float y,
                               float u, float v, int alpha, int light) {
        vertices.addVertex(pose, x, y, 0)
                .setColor(255, 255, 255, alpha).setUv(u, v)
                .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                .setNormal(pose, 0, 0, 1);
    }
}
