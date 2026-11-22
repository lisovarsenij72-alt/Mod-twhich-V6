package dev.minime;

import com.mojang.authlib.GameProfile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Несколько мини-сущностей (слоты). У каждой своя физика (пружина, столкновения с блоками),
 * свои модель и скин. Улетают в тишине, прилетают при сообщении, лопаются от удара.
 */
public class MiniManager {
    private static class Mini {
        LivingEntity e;
        Vec3 vel = Vec3.ZERO;
        long respawnAt = 0;
        boolean cameFromAway = false;
        int awayTicks = 0;
    }

    private static final Mini[] MINIS = new Mini[Config.MAX_SLOTS];
    static { for (int i = 0; i < MINIS.length; i++) MINIS[i] = new Mini(); }
    private static volatile long lastMsg = System.currentTimeMillis();
    private static final int FAKE_ID = -777001;
    private static final Random RND = new Random();

    public static void onMessage() { lastMsg = System.currentTimeMillis(); }

    /** Первая живая мини (к ней привязываются пузыри). */
    public static LivingEntity first() {
        for (Mini m : MINIS) if (m.e != null && !m.e.isRemoved()) return m.e;
        return null;
    }

    public static double baseHeight(LivingEntity e) { return e instanceof MiniChibiEntity ? 1.4 : 1.8; }

    /** Удар по сущности: если это одна из мини — лопаем. */
    public static boolean onAttack(Entity target) {
        for (int i = 0; i < MINIS.length; i++) {
            if (MINIS[i].e != null && MINIS[i].e == target) { pop(i); return true; }
        }
        return false;
    }

    public static void tick(Minecraft mc) {
        Config cfg = MiniMeClient.config;
        ClientLevel level = mc.level;
        LocalPlayer p = mc.player;
        if (level != null) tickBursts(level);
        if (level == null || p == null || !cfg.enabled) { removeAll(); return; }

        long now = System.currentTimeMillis();
        boolean idle = cfg.autoAway && now - lastMsg > cfg.idleSeconds * 1000L;
        int n = 0;
        for (Config.Slot s : cfg.slots) if (s.enabled) n++;
        int k = 0;
        for (int i = 0; i < MINIS.length; i++) {
            Config.Slot s = cfg.slots.get(i);
            if (!s.enabled) { remove(i); continue; }
            tickOne(i, s, k, n, mc, cfg, level, p, idle, now);
            k++;
        }
    }

    private static void tickOne(int i, Config.Slot s, int k, int n, Minecraft mc, Config cfg, ClientLevel level, LocalPlayer p, boolean idle, long now) {
        Mini m = MINIS[i];
        if (now < m.respawnAt) return;
        if (idle && m.e == null) return;

        boolean chibi = (s.model.equals("chibi") || s.model.equals("fox")) && MiniMeMain.CHIBI != null;
        if (m.e != null && (m.e.level() != level || m.e.isRemoved())) m.e = null;
        else if (m.e != null && (m.e instanceof MiniChibiEntity) != chibi) remove(i);

        if (m.e == null) {
            m.e = chibi ? new MiniChibiEntity(MiniMeMain.CHIBI, level)
                        : new MiniEntity(level, new GameProfile(UUID.randomUUID(), "Mini"));
            m.e.setId(FAKE_ID - i);
            m.e.setNoGravity(true);
            m.e.setSilent(true);
            m.e.noPhysics = false;
            Vec3 start = m.cameFromAway ? awayTarget(p, k, n) : target(p, cfg, i, k, n);
            m.e.setPos(start.x, start.y, start.z);
            level.addEntity(m.e);
            m.vel = Vec3.ZERO;
            if (!m.cameFromAway) burst(level, start.add(0, 0.3 * cfg.scale, 0), 12, cfg.popEffect);
            m.cameFromAway = false;
            m.awayTicks = 0;
        }

        if (m.e instanceof MiniChibiEntity c) {
            c.fox = s.model.equals("fox");
            c.texture = SkinManager.skinFor(mc, s.skin).body().texturePath();
        } else if (m.e instanceof MiniEntity pl) {
            pl.skinName = s.skin;
        }

        AttributeInstance sc = m.e.getAttribute(Attributes.SCALE);
        if (sc != null && Math.abs(sc.getBaseValue() - cfg.scale) > 1e-4) sc.setBaseValue(cfg.scale);

        Vec3 here = m.e.position();
        Vec3 tgt;
        if (idle) {
            tgt = awayTarget(p, k, n);
            m.awayTicks++;
            if (here.distanceTo(p.position()) > 14 || m.awayTicks > 120) {
                remove(i);
                m.cameFromAway = true;
                return;
            }
        } else {
            m.awayTicks = 0;
            tgt = target(p, cfg, i, k, n);
        }
        if (here.distanceTo(tgt) > 60) {
            m.e.setPos(tgt.x, tgt.y, tgt.z);
            m.vel = Vec3.ZERO;
            here = tgt;
        }

        m.vel = m.vel.scale(0.8).add(tgt.subtract(here).scale(idle ? 0.03 : 0.05));
        double sp = m.vel.length();
        if (sp > 1.0) m.vel = m.vel.scale(1.0 / sp);
        m.e.move(MoverType.SELF, m.vel);

        float yaw = (float) (Mth.atan2(p.getZ() - m.e.getZ(), p.getX() - m.e.getX()) * 57.29578) - 90f;
        m.e.setYRot(yaw);
        m.e.setYHeadRot(yaw);
        m.e.setXRot(0f);
    }

    private static Vec3 flatForward(LocalPlayer p) {
        double yaw = Math.toRadians(p.getYRot());
        return new Vec3(-Math.sin(yaw), 0, Math.cos(yaw));
    }

    private static Vec3 target(LocalPlayer p, Config cfg, int slot, int k, int n) {
        double bob = Math.sin(System.currentTimeMillis() / 450.0 + slot * 1.3) * 0.08;
        double d = cfg.distance;
        Vec3 fwd = flatForward(p);
        Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
        Vec3 base;
        switch (cfg.position) {
            case "side":
                base = p.position().add(fwd.scale(0.4 * d)).add(right.scale(0.45 * d)).add(0, 1.2 + bob, 0);
                break;
            case "back":
                base = p.position().add(fwd.scale(-0.6 * d)).add(0, 1.5 + bob, 0);
                break;
            default:
                base = p.getEyePosition().add(p.getLookAngle().scale(d)).add(0, 0.15 + bob, 0);
        }
        // несколько чибиков встают рядком
        double off = (k - (n - 1) / 2.0) * (0.45 + 0.6 * cfg.scale);
        return base.add(right.scale(off)).add(0, (k % 2) * 0.12, 0);
    }

    private static Vec3 awayTarget(LocalPlayer p, int k, int n) {
        Vec3 fwd = flatForward(p);
        Vec3 right = new Vec3(-fwd.z, 0, fwd.x);
        return p.position().add(fwd.scale(-14)).add(right.scale((k - (n - 1) / 2.0) * 3)).add(0, 9, 0);
    }

    private static void pop(int i) {
        Minecraft mc = Minecraft.getInstance();
        Mini m = MINIS[i];
        if (m.e == null || mc.level == null) return;
        Config cfg = MiniMeClient.config;
        Vec3 c = m.e.position().add(0, 0.5 * baseHeight(m.e) * cfg.scale, 0);
        burst(mc.level, c, 64, cfg.popEffect);
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ITEM_PICKUP, 1.4f));
        remove(i);
        m.cameFromAway = false;
        m.respawnAt = System.currentTimeMillis() + cfg.respawnSeconds * 1000L;
    }

    /** Кнопка «Тест» в меню: лопнуть первого. */
    public static void popFirst() {
        for (int i = 0; i < MINIS.length; i++) if (MINIS[i].e != null) { pop(i); return; }
    }

    /** Ключи и названия эффектов (для меню). */
    public static final String[] FX = {"poof", "snow", "cherry", "sand", "souls", "hearts", "flame", "stars", "notes", "magic",
            "portal", "totem", "firework", "sparks", "glow", "crit", "joy", "witch", "angry", "ink", "gold", "diamond", "emerald"};
    public static final String[] FXN = {"дым", "снег", "сакура", "песок", "души", "сердца", "огонь", "звёзды", "ноты", "магия",
            "портал", "тотем", "салют", "искры", "свечение", "крит", "радость", "ведьма", "злость", "чернила", "золото", "алмазы", "изумруды"};

    private static ParticleOptions effectOf(String effect) {
        switch (effect) {
            case "snow": return ParticleTypes.SNOWFLAKE;
            case "cherry": return ParticleTypes.CHERRY_LEAVES;
            case "sand": return ParticleTypes.WHITE_ASH;          // лёгкая светлая пыль, не падает кучей
            case "souls": return ParticleTypes.SOUL;
            case "hearts": return ParticleTypes.HEART;
            case "flame": return ParticleTypes.FLAME;
            case "stars": return ParticleTypes.END_ROD;
            case "notes": return ParticleTypes.NOTE;
            case "magic": return ParticleTypes.ENCHANT;
            case "portal": return ParticleTypes.PORTAL;
            case "totem": return ParticleTypes.TOTEM_OF_UNDYING;
            case "firework": return ParticleTypes.FIREWORK;
            case "sparks": return ParticleTypes.ELECTRIC_SPARK;
            case "glow": return ParticleTypes.GLOW;
            case "crit": return ParticleTypes.CRIT;
            case "joy": return ParticleTypes.HAPPY_VILLAGER;
            case "witch": return ParticleTypes.WITCH;
            case "angry": return ParticleTypes.ANGRY_VILLAGER;
            case "ink": return ParticleTypes.SQUID_INK;
            case "gold": return ParticleTypes.WAX_ON;             // золотые искры
            case "diamond": return ParticleTypes.SCRAPE;          // бирюзово-белые искры
            case "emerald": return ParticleTypes.COMPOSTER;       // зелёные искры
            default: return ParticleTypes.POOF;
        }
    }

    private static double speedOf(String fx) {
        switch (fx) {
            case "firework": return 0.30;
            case "sparks": return 0.25;
            case "stars": return 0.22;
            case "hearts": return 0.10;
            case "souls": return 0.15;
            case "snow": case "cherry": return 0.06;
            default: return 0.20;
        }
    }

    /** Эффекты, которые сами падают: их разбрасываем по сфере, а не из одной точки. */
    private static boolean heavy(String fx) {
        return fx.equals("snow") || fx.equals("cherry") || fx.equals("totem");
    }

    private static class Burst {
        Vec3 c; String fx; int total; int step = 0; final int steps = 8;
        Burst(Vec3 c, String fx, int total) { this.c = c; this.fx = fx; this.total = total; }
    }
    private static final List<Burst> BURSTS = new ArrayList<>();

    /** Ставит залп в очередь: частицы вылетают во все стороны волнами в течение ~0.4 с. */
    private static void burst(ClientLevel level, Vec3 c, int n, String effect) {
        BURSTS.add(new Burst(c, effect, n));
    }

    private static void tickBursts(ClientLevel level) {
        for (Iterator<Burst> it = BURSTS.iterator(); it.hasNext(); ) {
            Burst b = it.next();
            emit(level, b);
            if (++b.step >= b.steps) it.remove();
        }
    }

    private static void emit(ClientLevel level, Burst b) {
        ParticleOptions fx = effectOf(b.fx);
        int per = Math.max(1, b.total / b.steps);
        double speed = speedOf(b.fx);
        double maxR = heavy(b.fx) ? 0.9 : 0.2;
        double r = maxR * (b.step + 1) / b.steps;
        for (int i = 0; i < per; i++) {
            // равномерное случайное направление по сфере
            double ux = RND.nextGaussian(), uy = RND.nextGaussian(), uz = RND.nextGaussian();
            double len = Math.sqrt(ux * ux + uy * uy + uz * uz);
            if (len < 1e-4) { ux = 0; uy = 1; uz = 0; len = 1; }
            ux /= len; uy /= len; uz /= len;
            double rr = r * (0.4 + 0.6 * RND.nextDouble());
            double vx = ux * speed, vy = uy * speed, vz = uz * speed;
            if ("notes".equals(b.fx)) { vx = RND.nextDouble(); vy = 0; vz = 0; }   // у нот первая компонента — цвет
            level.addParticle(fx, b.c.x + ux * rr, b.c.y + uy * rr, b.c.z + uz * rr, vx, vy, vz);
        }
    }

    private static void remove(int i) {
        Mini m = MINIS[i];
        if (m.e != null) {
            ClientLevel level = Minecraft.getInstance().level;
            if (level != null) level.removeEntity(m.e.getId(), Entity.RemovalReason.DISCARDED);
            m.e = null;
            m.vel = Vec3.ZERO;
        }
    }

    public static void removeAll() { for (int i = 0; i < MINIS.length; i++) remove(i); }
}
