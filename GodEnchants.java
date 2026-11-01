package com.godenchants;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class GodEnchants implements ModInitializer {
    public static final String MOD_ID = "godenchants";

    public static final RegistryKey<Enchantment> VOID_REND = key("void_rend");
    public static final RegistryKey<Enchantment> SOUL_REAPER = key("soul_reaper");
    public static final RegistryKey<Enchantment> DIVINE_JUDGMENT = key("divine_judgment");

    // ---- Tuning ----
    private static final int VOID_COOLDOWN_TICKS = 5 * 20;
    private static final double VOID_RADIUS = 8.0;
    private static final int VOID_DURATION_TICKS = 30;
    private static final float VOID_EXTRA_DAMAGE = 8.0f;

    private static final float SOUL_HP_PER_KILL = 4.0f;   // 2 hearts
    private static final float SOUL_MAX_HP = 20.0f;       // 10 hearts

    private static final int JUDGMENT_COOLDOWN_TICKS = 8 * 20;
    private static final int JUDGMENT_HITS_NEEDED = 3;
    private static final double JUDGMENT_RADIUS = 5.0;
    private static final float JUDGMENT_DAMAGE = 14.0f;
    private static final int STRENGTH_TICKS = 5 * 20;

    // ---- State (in-memory) ----
    private static final Map<UUID, Long> voidCooldown = new HashMap<>();
    private static final Map<UUID, Long> judgmentCooldown = new HashMap<>();
    private static final Map<UUID, Integer> judgmentHits = new HashMap<>();
    private static final Map<UUID, Float> souls = new HashMap<>();
    private static final List<BlackHole> blackHoles = new ArrayList<>();

    /** Guards against our own extra damage re-triggering the enchantments. */
    private static boolean busy = false;

    private static RegistryKey<Enchantment> key(String path) {
        return RegistryKey.of(RegistryKeys.ENCHANTMENT, Identifier.of(MOD_ID, path));
    }

    @Override
    public void onInitialize() {
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            if (busy || blocked) return;
            if (!(source.getAttacker() instanceof ServerPlayerEntity player)) return;
            if (source.getSource() != player) return; // melee only
            ItemStack weapon = player.getMainHandStack();
            if (weapon.isEmpty()) return;

            ServerWorld world = worldOf(player);

            if (has(weapon, VOID_REND) && ready(voidCooldown, player, world, VOID_COOLDOWN_TICKS)) {
                blackHoles.add(new BlackHole(world, entity.getX(), entity.getY(), entity.getZ(), player.getUuid()));
                world.playSound(null, BlockPos.ofFloored(entity.getX(), entity.getY(), entity.getZ()),
                        SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 2.0f, 0.4f);
            }

            if (has(weapon, DIVINE_JUDGMENT)) {
                int hits = judgmentHits.merge(player.getUuid(), 1, Integer::sum);
                if (hits >= JUDGMENT_HITS_NEEDED && ready(judgmentCooldown, player, world, JUDGMENT_COOLDOWN_TICKS)) {
                    judgmentHits.put(player.getUuid(), 0);
                    divineJudgment(world, player, entity);
                }
            }
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(source.getAttacker() instanceof ServerPlayerEntity player)) return;
            if (!has(player.getMainHandStack(), SOUL_REAPER)) return;

            float stored = Math.min(SOUL_MAX_HP, souls.getOrDefault(player.getUuid(), 0f) + SOUL_HP_PER_KILL);
            souls.put(player.getUuid(), stored);

            ServerWorld world = worldOf(player);
            world.spawnParticles(ParticleTypes.SOUL, entity.getX(), entity.getY() + 1.0, entity.getZ(),
                    25, 0.4, 0.6, 0.4, 0.05);
            player.sendMessage(Text.literal("Souls: " + (int) (stored / 2) + "/" + (int) (SOUL_MAX_HP / 2)
                    + " hearts stored"), true);
        });

        // Right-click with Soul Reaper: consume souls
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (world.isClient() || !(player instanceof ServerPlayerEntity sp)) return ActionResult.PASS;
            ItemStack stack = player.getStackInHand(hand);
            if (!has(stack, SOUL_REAPER)) return ActionResult.PASS;

            float stored = souls.getOrDefault(sp.getUuid(), 0f);
            if (stored <= 0f) return ActionResult.PASS;
            souls.remove(sp.getUuid());

            float missing = sp.getMaxHealth() - sp.getHealth();
            float healed = Math.min(missing, stored);
            sp.heal(healed);
            float overflow = stored - healed;
            if (overflow > 0f) {
                sp.setAbsorptionAmount(Math.min(sp.getAbsorptionAmount() + overflow, 20f));
            }

            ServerWorld sw = worldOf(sp);
            sw.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, sp.getX(), sp.getY() + 1.0, sp.getZ(),
                    40, 0.5, 0.8, 0.5, 0.05);
            sw.playSound(null, sp.getBlockPos(), SoundEvents.PARTICLE_SOUL_ESCAPE.value(),
                    SoundCategory.PLAYERS, 2.0f, 1.0f);
            return ActionResult.SUCCESS;
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // Tick black holes
            Iterator<BlackHole> it = blackHoles.iterator();
            while (it.hasNext()) {
                BlackHole bh = it.next();
                if (bh.tick()) it.remove();
            }
            // Soul Reaper: reset souls if the weapon is no longer carried
            if (server.getTicks() % 10 == 0) checkSoulWeapons(server);
        });

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.getPlayer().getUuid();
            voidCooldown.remove(id);
            judgmentCooldown.remove(id);
            judgmentHits.remove(id);
            souls.remove(id);
        });
    }

    // ------------------------------------------------------------------

    private static ServerWorld worldOf(Entity entity) {
        return (ServerWorld) entity.getEntityWorld();
    }

    private static boolean has(ItemStack stack, RegistryKey<Enchantment> key) {
        if (stack == null || stack.isEmpty()) return false;
        ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants == null) return false;
        for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
            if (entry.matchesKey(key)) return true;
        }
        return false;
    }

    private static boolean ready(Map<UUID, Long> cooldowns, ServerPlayerEntity player, ServerWorld world, int ticks) {
        long now = world.getTime();
        Long next = cooldowns.get(player.getUuid());
        if (next != null && now < next) return false;
        cooldowns.put(player.getUuid(), now + ticks);
        return true;
    }

    private static DamageSource magicFrom(ServerWorld world, UUID owner) {
        ServerPlayerEntity p = world.getServer().getPlayerManager().getPlayer(owner);
        return p != null
                ? world.getDamageSources().indirectMagic(p, p)
                : world.getDamageSources().magic();
    }

    private static void hurt(ServerWorld world, LivingEntity target, DamageSource source, float amount) {
        busy = true;
        try {
            target.damage(world, source, amount);
        } finally {
            busy = false;
        }
    }

    private static void checkSoulWeapons(MinecraftServer server) {
        if (souls.isEmpty()) return;
        for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            if (!souls.containsKey(p.getUuid())) continue;
            if (!carriesSoulReaper(p)) {
                souls.remove(p.getUuid());
                p.sendMessage(Text.literal("Your stored souls fade away..."), true);
            }
        }
        // players that are offline are cleaned on disconnect
    }

    private static boolean carriesSoulReaper(ServerPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (has(inv.getStack(i), SOUL_REAPER)) return true;
        }
        return false;
    }

    // ---- Divine Judgment ----

    private static void divineJudgment(ServerWorld world, ServerPlayerEntity player, LivingEntity target) {
        double x = target.getX(), y = target.getY(), z = target.getZ();

        LightningEntity bolt = EntityType.LIGHTNING_BOLT.create(world, SpawnReason.TRIGGERED);
        if (bolt != null) {
            bolt.refreshPositionAfterTeleport(x, y, z);
            bolt.setCosmetic(true); // visuals only; we apply our own damage so the wielder is safe
            world.spawnEntity(bolt);
        }

        world.spawnParticles(ParticleTypes.EXPLOSION_EMITTER, x, y + 1.0, z, 1, 0, 0, 0, 0);
        world.playSound(null, BlockPos.ofFloored(x, y, z), SoundEvents.ENTITY_GENERIC_EXPLODE.value(),
                SoundCategory.PLAYERS, 4.0f, 0.8f);

        Box box = new Box(x - JUDGMENT_RADIUS, y - JUDGMENT_RADIUS, z - JUDGMENT_RADIUS,
                x + JUDGMENT_RADIUS, y + JUDGMENT_RADIUS, z + JUDGMENT_RADIUS);
        DamageSource src = world.getDamageSources().indirectMagic(player, player);
        for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                le -> le.isAlive() && le != player)) {
            double dx = e.getX() - x, dz = e.getZ() - z;
            if (dx * dx + dz * dz + Math.pow(e.getY() - y, 2) > JUDGMENT_RADIUS * JUDGMENT_RADIUS) continue;
            hurt(world, e, src, JUDGMENT_DAMAGE);
            e.setOnFireFor(5);
            double len = Math.max(0.1, Math.sqrt(dx * dx + dz * dz));
            e.setVelocity(e.getVelocity().add(dx / len * 0.8, 0.5, dz / len * 0.8));
            e.velocityModified = true;
        }

        player.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, STRENGTH_TICKS, 1));
    }

    // ---- Void Rend ----

    private static final class BlackHole {
        private final ServerWorld world;
        private final double x, y, z;
        private final UUID owner;
        private final Set<UUID> damaged = new HashSet<>();
        private int ticksLeft = VOID_DURATION_TICKS;

        BlackHole(ServerWorld world, double x, double y, double z, UUID owner) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.owner = owner;
        }

        /** @return true when finished */
        boolean tick() {
            double cy = y + 0.8;
            world.spawnParticles(ParticleTypes.REVERSE_PORTAL, x, cy, z, 30, 2.5, 1.5, 2.5, 0.2);
            world.spawnParticles(ParticleTypes.SQUID_INK, x, cy, z, 6, 0.3, 0.3, 0.3, 0.02);

            Box box = new Box(x - VOID_RADIUS, y - VOID_RADIUS, z - VOID_RADIUS,
                    x + VOID_RADIUS, y + VOID_RADIUS, z + VOID_RADIUS);
            DamageSource src = magicFrom(world, owner);

            for (LivingEntity e : world.getEntitiesByClass(LivingEntity.class, box,
                    le -> le.isAlive() && !le.getUuid().equals(owner))) {
                double dx = x - e.getX(), dy = cy - e.getY(), dz = z - e.getZ();
                double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (dist > VOID_RADIUS) continue;

                if (dist > 0.6) {
                    double s = 0.45;
                    e.setVelocity(new Vec3d(dx / dist * s, dy / dist * s * 0.6, dz / dist * s));
                } else {
                    e.setVelocity(Vec3d.ZERO);
                }
                e.velocityModified = true;
                e.fallDistance = 0;

                if (damaged.add(e.getUuid())) {
                    hurt(world, e, src, VOID_EXTRA_DAMAGE);
                }
            }
            return --ticksLeft <= 0;
        }
    }
}
