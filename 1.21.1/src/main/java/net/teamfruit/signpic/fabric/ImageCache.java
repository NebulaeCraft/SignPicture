package net.teamfruit.signpic.fabric;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FastColor;
import org.slf4j.Logger;

public final class ImageCache {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final int MAX_SIDE = 4096;
    private static final long IDLE_NANOS = TimeUnit.MINUTES.toNanos(2);
    private static final long PRUNE_INTERVAL = TimeUnit.SECONDS.toNanos(5);
    private final Map<String, Entry> entries = new HashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), task -> {
                Thread thread = new Thread(task, "SignPicture image loader");
                thread.setDaemon(true);
                return thread;
            });
    private long lastPrune = System.nanoTime();

    public record Image(ResourceLocation texture, int width, int height) { }

    enum Status { IDLE, LOADING, READY, FAILED }

    private static final class Entry {
        Image image;
        Status status = Status.IDLE;
        long lastUsed = System.nanoTime();
    }

    public Image get(String source) {
        Entry entry = entries.get(source);
        if (entry == null) {
            retry(source, false);
            return null;
        }
        entry.lastUsed = System.nanoTime();
        return entry.image;
    }

    Status status(String source) {
        Entry entry = entries.get(source);
        return entry == null ? Status.IDLE : entry.status;
    }

    boolean retry(String source, boolean redownload) {
        Entry entry = entries.computeIfAbsent(source, ignored -> new Entry());
        if (entry.status == Status.LOADING) {
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        try {
            workers.execute(() -> {
                try {
                    NativeImage image = toNativeImage(read(client, source, redownload));
                    client.execute(() -> finish(client, entry, image));
                } catch (IOException | RuntimeException e) {
                    LOGGER.warn("Could not load a sign image: {}", e.getMessage());
                    client.execute(() -> finish(client, entry, null));
                }
            });
        } catch (RejectedExecutionException e) {
            if (entry.status == Status.IDLE) {
                entries.remove(source);
            }
            return false;
        }
        entry.status = Status.LOADING;
        entry.lastUsed = System.nanoTime();
        return true;
    }

    private void finish(Minecraft client, Entry entry, NativeImage pixels) {
        release(client, entry);
        entry.status = Status.FAILED;
        entry.lastUsed = System.nanoTime();
        if (pixels == null) {
            return;
        }

        int width = pixels.getWidth();
        int height = pixels.getHeight();
        DynamicTexture texture = null;
        try {
            texture = new DynamicTexture(pixels);
            texture.setFilter(true, false);
            ResourceLocation id = client.getTextureManager().register("signpic", texture);
            entry.image = new Image(id, width, height);
            entry.status = Status.READY;
        } catch (RuntimeException e) {
            if (texture == null) {
                pixels.close();
            } else {
                texture.close();
            }
            LOGGER.warn("Could not upload a sign image", e);
        }
    }

    void prune(Minecraft client) {
        long now = System.nanoTime();
        if (now - lastPrune < PRUNE_INTERVAL) {
            return;
        }
        lastPrune = now;
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.status != Status.LOADING && now - entry.lastUsed > IDLE_NANOS) {
                release(client, entry);
                iterator.remove();
            }
        }
    }

    private static void release(Minecraft client, Entry entry) {
        if (entry.image != null) {
            client.getTextureManager().release(entry.image.texture());
            entry.image = null;
        }
    }

    private static BufferedImage read(Minecraft client, String source, boolean redownload) throws IOException {
        if (source.startsWith("!")) {
            ResourceLocation id = ResourceLocation.parse(source.substring(1));
            try (InputStream input = client.getResourceManager().open(id)) {
                return decode(readLimited(input));
            }
        }

        Path file = client.gameDirectory.toPath().resolve("signpic/fabric-cache").resolve(cacheName(source));
        if (!redownload && Files.isRegularFile(file)) {
            try (InputStream input = Files.newInputStream(file)) {
                return decode(readLimited(input));
            }
        }

        byte[] data = download(URI.create(source), redownload);
        BufferedImage image = decode(data);
        try {
            Files.createDirectories(file.getParent());
            Path temp = Files.createTempFile(file.getParent(), "image-", ".tmp");
            try {
                Files.write(temp, data);
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not cache a sign image: {}", e.getMessage());
        }
        return image;
    }

    private static byte[] download(URI uri, boolean redownload) throws IOException {
        for (int redirects = 0; redirects <= 5; redirects++) {
            String scheme = uri.getScheme();
            if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IOException("Invalid HTTP image URL");
            }
            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);
                connection.setRequestProperty("User-Agent", "SignPicture Fabric (Minecraft 1.21.1)");
                if (redownload) {
                    connection.setUseCaches(false);
                    connection.setRequestProperty("Cache-Control", "no-cache");
                }
                int status = connection.getResponseCode();
                String location = connection.getHeaderField("Location");
                if ((status == 301 || status == 302 || status == 303 || status == 307 || status == 308)
                        && location != null) {
                    uri = uri.resolve(location);
                    continue;
                }
                if (status != 200) {
                    throw new IOException("HTTP " + status);
                }
                if (connection.getContentLengthLong() > MAX_BYTES) {
                    throw new IOException("Image exceeds 16 MiB");
                }
                try (InputStream input = connection.getInputStream()) {
                    return readLimited(input);
                }
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many image redirects");
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        byte[] data = input.readNBytes(MAX_BYTES + 1);
        if (data.length > MAX_BYTES) {
            throw new IOException("Image exceeds 16 MiB");
        }
        return data;
    }

    private static BufferedImage decode(byte[] data) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("Unsupported image format");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
                    throw new IOException("Image dimensions exceed 4096 × 4096");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        }
    }

    private static NativeImage toNativeImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        NativeImage pixels = new NativeImage(width, height, false);
        try {
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                image.getRGB(0, y, width, 1, row, 0, width);
                for (int x = 0; x < width; x++) {
                    pixels.setPixelRGBA(x, y, FastColor.ABGR32.fromArgb32(row[x]));
                }
            }
            return pixels;
        } catch (RuntimeException e) {
            pixels.close();
            throw e;
        }
    }

    private static String cacheName(String source) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
