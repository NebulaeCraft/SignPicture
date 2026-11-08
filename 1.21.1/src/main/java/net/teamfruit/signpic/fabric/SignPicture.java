package net.teamfruit.signpic.fabric;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.net.URI;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.SignText;

public record SignPicture(String source, SignLayout layout) {
    private static final Cache<SignText, SignPicture> TEXTS =
            CacheBuilder.newBuilder().weakKeys().maximumSize(8192).build();
    private static final Cache<SignText, SignPicture> FILTERED_TEXTS =
            CacheBuilder.newBuilder().weakKeys().maximumSize(8192).build();
    private static final SignPicture NONE = new SignPicture("", null);

    public static SignPicture from(SignText text) {
        boolean filtered = Minecraft.getInstance().isTextFilteringEnabled();
        Cache<SignText, SignPicture> cache = filtered ? FILTERED_TEXTS : TEXTS;
        SignPicture picture = cache.getIfPresent(text);
        if (picture == null) {
            StringBuilder joined = new StringBuilder();
            for (Component line : text.getMessages(filtered)) {
                joined.append(line.getString());
            }
            picture = parse(joined.toString());
            cache.put(text, picture);
        }
        return picture == NONE ? null : picture;
    }

    private static SignPicture parse(String text) {
        String source;
        int start;
        int prefix = text.indexOf('#');
        if (prefix >= 0 && prefix < 2 && text.endsWith("}")) {
            start = text.lastIndexOf('{');
            if (start <= prefix + 1) {
                return NONE;
            }
            source = text.substring(prefix + 1, start);
        } else if (text.endsWith("]")) {
            start = text.lastIndexOf('[');
            if (start <= 0) {
                return NONE;
            }
            source = text.substring(0, start);
            if (!source.contains(".") || !source.contains("/")) {
                return NONE;
            }
        } else {
            return NONE;
        }

        if (source.startsWith("!")) {
            if (source.length() == 1 || ResourceLocation.tryParse(source.substring(1)) == null) {
                return NONE;
            }
        } else {
            if (source.startsWith("$")) {
                source = "https://" + source.substring(1);
            } else if (!source.startsWith("http://") && !source.startsWith("https://")) {
                source = "http://" + source;
            }
            try {
                URI uri = URI.create(source);
                if (uri.getHost() == null || uri.getUserInfo() != null) {
                    return NONE;
                }
            } catch (IllegalArgumentException e) {
                return NONE;
            }
        }
        return new SignPicture(source, new SignLayout(text.substring(start + 1, text.length() - 1)));
    }
}
