package pl.holovis;

import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;
import pl.holovis.net.KosaNet;

/**
 * NameTag: własny napis (1-2 linie, ikonka, kolor, czcionka) nad głową. Widzisz go u siebie (F5),
 * a gdy konta online są podłączone - widzą go też inni gracze z KosaVisuals.
 */
public final class CustomNametag {
    private CustomNametag() {}

    public static final String[] ICONS = {"Play", "Live", "Czat", "Aparat", "Nutka", "Pad", "Serce", "Gwiazda", "Korona", "Ogień"};
    public static final String[] FONTS = {"Minecraft", "Pogrubiona", "Kursywa"};
    public static final String[] MODES = {"Jedna linia", "Dwie linie"};
    private static final Identifier FONT = Identifier.of("holovis", "kosa");

    /** Dane napisu (własne albo innego gracza z bazy). */
    public record Data(String l1, String l2, int icon, float size, int font, int color, int mode) {}

    public static Data mine() {
        HoloConfig c = HoloConfig.get();
        if (!c.ntOn) return null;
        if (c.ntLine1.isBlank() && (c.ntMode == 0 || c.ntLine2.isBlank()) && c.ntIcon < 0) return null;
        return new Data(c.ntLine1, c.ntLine2, c.ntIcon, c.ntSize, c.ntFont, c.ntColor.rgb(), c.ntMode);
    }

    public static Text line(String s, int icon, int font, int color) {
        MutableText t = Text.empty();
        if (icon >= 0 && icon < ICONS.length) {
            t.append(Text.literal(String.valueOf((char) (0xE010 + icon))).setStyle(Style.EMPTY.withFont(FONT).withColor(0xFFFFFF)));
            if (!s.isEmpty()) t.append(Text.literal(" "));
        }
        Style st = Style.EMPTY.withColor(color & 0xFFFFFF).withBold(font == 1).withItalic(font == 2);
        t.append(Text.literal(s).setStyle(st));
        return t;
    }

    public static void render(WorldRenderContext ctx) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) return;
        MatrixStack ms = ctx.matrixStack();
        VertexConsumerProvider vcp = ctx.consumers();
        if (ms == null || vcp == null) return;
        Vec3d cam = ctx.camera().getPos();
        float td = ctx.tickCounter().getTickDelta(false);
        boolean any = false;
        for (PlayerEntity p : mc.world.getPlayers()) {
            Data d;
            if (p == mc.player) {
                if (mc.options.getPerspective().isFirstPerson()) continue;
                d = mine();
            } else {
                d = KosaNet.tagOf(p.getUuid());
            }
            if (d == null || p.isInvisible()) continue;
            Vec3d lp = p.getLerpedPos(td);
            if (lp.squaredDistanceTo(cam) > 48 * 48) continue;
            draw(mc.textRenderer, ms, vcp, d, (float) (lp.x - cam.x), (float) (lp.y - cam.y) + p.getHeight() + (p == mc.player ? 0.45f : 0.75f), (float) (lp.z - cam.z), ctx);
            any = true;
        }
        if (any && vcp instanceof VertexConsumerProvider.Immediate imm) imm.draw();
    }

    private static void draw(TextRenderer tr, MatrixStack ms, VertexConsumerProvider vcp, Data d, float x, float y, float z, WorldRenderContext ctx) {
        float s = 0.0125f * Math.max(1f, Math.min(5f, d.size()));
        ms.push();
        ms.translate(x, y, z);
        ms.multiply(ctx.camera().getRotation());
        ms.scale(s, -s, s);
        Matrix4f m = ms.peek().getPositionMatrix();
        int bg = 0x50000000;
        int light = 0xF000F0;
        if (d.mode() == 1 && !d.l2().isBlank()) {
            Text t2 = line(d.l2(), -1, d.font(), d.color());
            tr.draw(t2, -tr.getWidth(t2) / 2f, -10f, 0xFFFFFFFF, false, m, vcp, TextRenderer.TextLayerType.NORMAL, bg, light);
            Text t1 = line(d.l1(), d.icon(), d.font(), d.color());
            tr.draw(t1, -tr.getWidth(t1) / 2f, -21f, 0xFFFFFFFF, false, m, vcp, TextRenderer.TextLayerType.NORMAL, bg, light);
        } else {
            Text t1 = line(d.l1(), d.icon(), d.font(), d.color());
            tr.draw(t1, -tr.getWidth(t1) / 2f, -10f, 0xFFFFFFFF, false, m, vcp, TextRenderer.TextLayerType.NORMAL, bg, light);
        }
        ms.pop();
    }
}
