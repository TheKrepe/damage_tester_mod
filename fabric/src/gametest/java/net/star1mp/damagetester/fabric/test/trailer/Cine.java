package net.star1mp.damagetester.fabric.test.trailer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/** Frame-exact video capture for the trailer: cameras, frame timing and an ffmpeg pipe. */
public final class Cine {
    /** Vertical 1080x1920 capture for shorts (groups starting with K). */
    public static boolean VERTICAL = false;

    public static final int FPS = 30;
    public static final double TICKS_PER_FRAME = 20.0 / FPS;

    private Cine() {
    }

    // ================================================================== camera poses

    public record Pose(double x, double y, double z, float yaw, float pitch) {
        public static Pose look(Vec3 eye, Vec3 target) {
            double dx = target.x - eye.x;
            double dy = target.y - eye.y;
            double dz = target.z - eye.z;
            double h = Math.sqrt(dx * dx + dz * dz);
            float yaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
            float pitch = (float) (-(Mth.atan2(dy, h) * Mth.RAD_TO_DEG));
            return new Pose(eye.x, eye.y, eye.z, yaw, pitch);
        }
    }

    /** A camera: u is 0..1 over the shot, t is the shot time in ticks. */
    public interface Cam {
        Pose at(double u, double t, Minecraft mc, float partial);
    }

    public static double ease(double u) {
        u = Mth.clamp(u, 0.0, 1.0);
        double s = u * u * (3.0 - 2.0 * u);
        return 0.45 * u + 0.55 * s;
    }

    public static Vec3 lerp(double a, Vec3 p, Vec3 q) {
        return new Vec3(Mth.lerp(a, p.x, q.x), Mth.lerp(a, p.y, q.y), Mth.lerp(a, p.z, q.z));
    }

    /** Straight move from a to b while looking from target ta to tb. */
    public static Cam dolly(Vec3 a, Vec3 b, Vec3 ta, Vec3 tb) {
        return (u, t, mc, partial) -> {
            double e = ease(u);
            return Pose.look(lerp(e, a, b), lerp(e, ta, tb));
        };
    }

    /** Smooth path through several eye points and look targets (Catmull-Rom). */
    public static Cam path(Vec3[] eyes, Vec3[] targets) {
        return (u, t, mc, partial) -> {
            double e = ease(u);
            return Pose.look(spline(eyes, e), spline(targets, e));
        };
    }

    public static Vec3 spline(Vec3[] p, double u) {
        if (p.length == 1) {
            return p[0];
        }
        double f = u * (p.length - 1);
        int i = Mth.clamp((int) Math.floor(f), 0, p.length - 2);
        double s = f - i;
        Vec3 p0 = p[Math.max(i - 1, 0)];
        Vec3 p1 = p[i];
        Vec3 p2 = p[i + 1];
        Vec3 p3 = p[Math.min(i + 2, p.length - 1)];
        double s2 = s * s;
        double s3 = s2 * s;
        return new Vec3(
                cr(p0.x, p1.x, p2.x, p3.x, s, s2, s3),
                cr(p0.y, p1.y, p2.y, p3.y, s, s2, s3),
                cr(p0.z, p1.z, p2.z, p3.z, s, s2, s3));
    }

    private static double cr(double a, double b, double c, double d, double s, double s2, double s3) {
        return 0.5 * (2 * b + (-a + c) * s + (2 * a - 5 * b + 4 * c - d) * s2 + (-a + 3 * b - 3 * c + d) * s3);
    }

    /** Circles around a point. Angles in degrees (0 = south of the center). */
    public static Cam orbit(Vec3 center, double radius, double height, double fromDeg, double toDeg, double lookUp) {
        return (u, t, mc, partial) -> {
            double a = Math.toRadians(Mth.lerp(ease(u), fromDeg, toDeg));
            Vec3 eye = new Vec3(center.x - Math.sin(a) * radius, center.y + height, center.z + Math.cos(a) * radius);
            return Pose.look(eye, center.add(0, lookUp, 0));
        };
    }

    /** Orbit with a radius and height that change over the shot (crane). */
    public static Cam crane(Vec3 center, double r0, double r1, double h0, double h1, double fromDeg, double toDeg, double lookUp) {
        return (u, t, mc, partial) -> {
            double e = ease(u);
            double a = Math.toRadians(Mth.lerp(e, fromDeg, toDeg));
            double r = Mth.lerp(e, r0, r1);
            Vec3 eye = new Vec3(center.x - Math.sin(a) * r, center.y + Mth.lerp(e, h0, h1), center.z + Math.cos(a) * r);
            return Pose.look(eye, center.add(0, lookUp, 0));
        };
    }

    /** Follows a client entity from a fixed offset, looking at it. */
    public static Cam follow(int entityId, Vec3 offset, double lookUp) {
        return (u, t, mc, partial) -> {
            Entity e = mc.level == null ? null : mc.level.getEntity(entityId);
            if (e == null) {
                return new Pose(0, 100, 0, 0, 0);
            }
            Vec3 p = new Vec3(Mth.lerp(partial, e.xo, e.getX()), Mth.lerp(partial, e.yo, e.getY()), Mth.lerp(partial, e.zo, e.getZ()));
            return Pose.look(p.add(offset), p.add(0, lookUp, 0));
        };
    }

    // ================================================================== rendering one frame

    private static DeltaTracker partialTick(float partial) {
        return new DeltaTracker() {
            @Override
            public float getGameTimeDeltaTicks() {
                return (float) TICKS_PER_FRAME;
            }

            @Override
            public float getGameTimeDeltaPartialTick(boolean ignoreFrozenGame) {
                return partial;
            }

            @Override
            public float getRealtimeDeltaTicks() {
                return (float) TICKS_PER_FRAME;
            }
        };
    }

    /** Places the (spectator) player exactly at the pose so the camera sits there. */
    public static void placeCamera(Minecraft mc, Pose pose) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        p.setPos(pose.x(), pose.y() - p.getEyeHeight(), pose.z());
        p.setYRot(pose.yaw());
        p.setXRot(pose.pitch());
        p.setYHeadRot(pose.yaw());
        p.setDeltaMovement(Vec3.ZERO);
        p.setOldPosAndRot();
    }

    /** Must run on the client thread. Renders at the given partial tick and queues the readback. */
    public static void renderFrame(Minecraft mc, float partial, Recorder recorder, int index) {
        DeltaTracker dt = partialTick(partial);
        mc.gameRenderer.update(dt);
        mc.gameRenderer.extract(dt, true);
        mc.gameRenderer.render();
        RenderSystem.getDevice().createCommandEncoder().submit();
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> recorder.frame(index, image));
    }

    // ================================================================== ffmpeg

    /** Pipes raw frames into ffmpeg. Frames arrive in order on the render thread. */
    public static final class Recorder implements AutoCloseable {
        private final Path file;
        private Process process;
        private OutputStream out;
        private int written;
        private int expected;
        private byte[] buffer;

        public Recorder(Path file) {
            this.file = file;
        }

        public synchronized void frame(int index, NativeImage image) {
            try {
                if (this.process == null) {
                    this.start(image.getWidth(), image.getHeight());
                }
                ByteBuffer bytes = image.getPixelBytes().duplicate();
                bytes.rewind();
                int n = bytes.remaining();
                if (this.buffer == null || this.buffer.length != n) {
                    this.buffer = new byte[n];
                }
                bytes.get(this.buffer);
                this.out.write(this.buffer);
                this.written++;
                if (index != this.written - 1) {
                    System.out.println("[trailer] frame order mismatch: " + index + " vs " + (this.written - 1));
                }
            } catch (IOException e) {
                throw new RuntimeException("ffmpeg pipe failed for " + this.file, e);
            } finally {
                image.close();
            }
        }

        public synchronized int written() {
            return this.written;
        }

        public void expect(int n) {
            this.expected = n;
        }

        public int expected() {
            return this.expected;
        }

        private void start(int w, int h) throws IOException {
            Files.createDirectories(this.file.getParent());
            List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-y", "-loglevel", "error",
                    "-f", "rawvideo", "-pix_fmt", "rgba", "-s", w + "x" + h, "-r", String.valueOf(FPS), "-i", "-",
                    "-vf", (VERTICAL ? "scale=1080:1920" : "scale=1920:1080") + ":flags=lanczos,format=yuv420p",
                    "-c:v", "libx264", "-preset", "veryfast", "-crf", "15", "-movflags", "+faststart",
                    this.file.toString()));
            this.process = new ProcessBuilder(cmd).inheritIO().redirectInput(ProcessBuilder.Redirect.PIPE).start();
            this.out = this.process.getOutputStream();
            System.out.println("[trailer] recording " + this.file.getFileName() + " at " + w + "x" + h);
        }

        @Override
        public void close() {
            try {
                if (this.out != null) {
                    this.out.close();
                }
                if (this.process != null) {
                    this.process.waitFor();
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    // ================================================================== event log (for sound effects in the edit)

    public static final class EventLog {
        private final Path file;

        public EventLog(Path file) {
            this.file = file;
        }

        public void log(String clip, double seconds, String type, String detail) {
            String line = String.format(Locale.ROOT, "%s,%.3f,%s,%s%n", clip, seconds, type, detail);
            try {
                Files.createDirectories(this.file.getParent());
                Files.writeString(this.file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    @SuppressWarnings("unused")
    private static void unused(Writer w) {
    }
}
