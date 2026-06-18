package dev.cevapi.pearlbucket;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.block.Dispenser;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Directional;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.Event.Result;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCreativeEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.block.BlockFace;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import net.kyori.adventure.text.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;

public final class PearlBucketPlugin extends JavaPlugin implements Listener {
    private NamespacedKey bucketIdKey;
    private NamespacedKey ownerIdKey;
    private NamespacedKey ownerNameKey;
    private NamespacedKey pearlBucketModelKey;
    private NamespacedKey virtualPearlKey;

    private final Map<UUID, CapturedPearl> capturedPearls = new HashMap<>();
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();
    private final Map<BlockKey, List<PearlBucketSlot>> dispenserPearlBuckets = new HashMap<>();
    private final Set<UUID> suppressedBucketEmptyPlayers = new HashSet<>();

    @Override
    public void onEnable() {
        bucketIdKey = new NamespacedKey(this, "bucket_id");
        ownerIdKey = new NamespacedKey(this, "owner_id");
        ownerNameKey = new NamespacedKey(this, "owner_name");
        pearlBucketModelKey = new NamespacedKey(this, "pearl_bucket");
        virtualPearlKey = new NamespacedKey(this, "virtual_pearl");

        Bukkit.getPluginManager().registerEvents(this, this);
        loadPendingTeleports();
        Bukkit.getScheduler().runTaskTimer(this, this::reconcileLoadedPearlBuckets, 20L, 100L);
    }

    @Override
    public void onDisable() {
        savePendingTeleports();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void onPearlBucketCapture(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof EnderPearl pearl)) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack bucket = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (bucket.getType() != Material.BUCKET) {
            return;
        }

        if (capturePearl(player, event.getHand(), pearl, true, false)) {
            suppressNextBucketEmpty(player.getUniqueId());
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.LOWEST)
    public void onPearlBucketFill(PlayerBucketFillEvent event) {
        Optional<EnderPearl> pearl = findPearlForBucketFill(event);
        if (pearl.isEmpty()) {
            return;
        }

        if (capturePearl(event.getPlayer(), event.getHand(), pearl.get(), true, false)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPearlBucketEmpty(PlayerBucketEmptyEvent event) {
        if (suppressedBucketEmptyPlayers.remove(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        ItemStack stack = pearlBucketFromHand(event.getPlayer(), event.getHand(), event.getItemStack());
        Optional<BucketData> data = readBucketData(stack);
        if (data.isEmpty()) {
            return;
        }

        BucketData bucket = data.get();
        CapturedPearl pearl = consumeCapturedPearl(bucket, stack);
        event.setCancelled(true);

        Block targetBlock = event.getBlock();
        if (canPlaceDispensedWater(targetBlock)) {
            targetBlock.setType(Material.WATER);
        }

        setBucketHand(event.getPlayer(), event.getHand(), new ItemStack(Material.BUCKET));
        targetBlock.getWorld().playSound(targetBlock.getLocation(), Sound.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.0f, 1.0f);

        Location spawnLocation = targetBlock.getLocation().add(0.5, 0.5, 0.5);
        Player owner = Bukkit.getPlayer(pearl.ownerId());
        if (owner == null) {
            pendingTeleports.put(pearl.ownerId(), PendingTeleport.from(spawnLocation));
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> releasePearl(pearl, spawnLocation));
    }

    @EventHandler(ignoreCancelled = false, priority = EventPriority.LOWEST)
    public void onPearlBucketUse(PlayerInteractEvent event) {
        if (event.getHand() == null || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }

        Player player = event.getPlayer();
        ItemStack stack = event.getHand() == EquipmentSlot.OFF_HAND
                ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        if (stack.getType() == Material.BUCKET && capturePearlFromWaterInteraction(event)) {
            suppressNextBucketEmpty(player.getUniqueId());
            event.setUseInteractedBlock(Result.DENY);
            event.setUseItemInHand(Result.DENY);
            event.setCancelled(true);
            return;
        }

        if (stack.getType() == Material.BUCKET && captureLookedAtPearl(player, event.getHand())) {
            suppressNextBucketEmpty(player.getUniqueId());
            event.setUseInteractedBlock(Result.DENY);
            event.setUseItemInHand(Result.DENY);
            event.setCancelled(true);
            return;
        }

        Optional<BucketData> data = readBucketData(stack);
        if (data.isEmpty()) {
            return;
        }

        Optional<PearlReleaseTarget> target = pearlReleaseTarget(event);
        if (target.isEmpty()) {
            return;
        }

        CapturedPearl pearl = consumeCapturedPearl(data.get(), stack);
        event.setCancelled(true);
        releasePearlBucket(player, event.getHand(), pearl, target.get());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPearlBucketDispense(BlockDispenseEvent event) {
        Optional<PearlBucketSlot> pearlBucketSlot = findDispensedPearlBucket(event);
        if (pearlBucketSlot.isEmpty()) {
            return;
        }

        PearlBucketSlot slot = pearlBucketSlot.get();
        BucketData bucket = slot.bucketData();
        CapturedPearl pearl = consumeCapturedPearl(bucket, slot.itemStack());
        dispenserPearlBuckets.remove(BlockKey.from(event.getBlock()));

        event.setCancelled(true);
        BlockFace facing = dispenserFacing(event.getBlock());
        Block targetBlock = event.getBlock().getRelative(facing);
        if (canPlaceDispensedWater(targetBlock)) {
            targetBlock.setType(Material.WATER);
        }

        Location spawnLocation = targetBlock.getLocation().add(0.5, 0.5, 0.5);
        replaceDispenserSlot(event.getBlock(), slot.slot(), new ItemStack(Material.BUCKET));

        Player owner = Bukkit.getPlayer(pearl.ownerId());
        if (owner == null) {
            pendingTeleports.put(pearl.ownerId(), PendingTeleport.from(spawnLocation));
            getLogger().warning("Pearl bucket was dispensed while its owner is offline; stored impact for next join.");
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> releasePearl(pearl, spawnLocation));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPearlBucketDrop(PlayerDropItemEvent event) {
        normalizeDroppedItem(event.getItemDrop());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onPearlBucketPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player) {
            normalizeDroppedItem(event.getItem());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInventoryClick(InventoryClickEvent event) {
        Bukkit.getScheduler().runTask(this, this::reconcileLoadedPearlBuckets);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCreativeInventory(InventoryCreativeEvent event) {
        Bukkit.getScheduler().runTask(this, this::reconcileLoadedPearlBuckets);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInventoryDrag(InventoryDragEvent event) {
        Bukkit.getScheduler().runTask(this, this::reconcileLoadedPearlBuckets);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        Bukkit.getScheduler().runTask(this, this::reconcileLoadedPearlBuckets);
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBucketItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Item item)) {
            return;
        }

        if (event.getCause() == EntityDamageEvent.DamageCause.LAVA
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE
                || event.getCause() == EntityDamageEvent.DamageCause.FIRE_TICK
                || event.getCause() == EntityDamageEvent.DamageCause.VOID
                || event.getCause() == EntityDamageEvent.DamageCause.CONTACT) {
            triggerDroppedBucket(item);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBucketDespawn(ItemDespawnEvent event) {
        triggerDroppedBucket(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Bukkit.getScheduler().runTaskLater(this, this::reconcileLoadedPearlBuckets, 2L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        PendingTeleport pendingTeleport = pendingTeleports.remove(event.getPlayer().getUniqueId());
        if (pendingTeleport != null) {
            Bukkit.getScheduler().runTask(this, () -> {
                Location location = pendingTeleport.toLocation();
                if (location != null) {
                    resolvePearlImpact(event.getPlayer(), location);
                }
            });
        }
        Bukkit.getScheduler().runTask(this, this::reconcileLoadedPearlBuckets);
    }

    private void releasePearl(CapturedPearl capturedPearl, Location location) {
        Player owner = Bukkit.getPlayer(capturedPearl.ownerId());
        if (owner == null || location.getWorld() == null) {
            pendingTeleports.put(capturedPearl.ownerId(), PendingTeleport.from(location));
            return;
        }

        Vector zeroVelocity = new Vector(0.0, 0.0, 0.0);
        EnderPearl pearl = owner.launchProjectile(EnderPearl.class, zeroVelocity, entity -> {
            entity.getPersistentDataContainer().set(virtualPearlKey, PersistentDataType.STRING, capturedPearl.bucketId().toString());
        });
        pearl.teleport(location);
        pearl.setVelocity(zeroVelocity);
    }

    private void triggerDroppedBucket(Item item) {
        Optional<BucketData> data = readBucketData(item.getItemStack());
        if (data.isEmpty()) {
            return;
        }

        CapturedPearl pearl = consumeCapturedPearl(data.get(), item.getItemStack());

        Player owner = Bukkit.getPlayer(pearl.ownerId());
        if (owner == null) {
            pendingTeleports.put(pearl.ownerId(), PendingTeleport.from(item.getLocation()));
        } else {
            resolvePearlImpact(owner, item.getLocation());
        }
    }

    private void resolvePearlImpact(Player owner, Location impactLocation) {
        if (impactLocation.getWorld() == null) {
            return;
        }

        Location destination = impactLocation.clone();
        World world = destination.getWorld();
        playPearlImpactEffects(world, owner.getLocation());
        playPearlImpactEffects(world, destination);
        owner.teleport(destination, PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        owner.damage(5.0);
    }

    private void playPearlImpactEffects(World world, Location location) {
        world.spawnParticle(Particle.PORTAL, location.clone().add(0.0, 0.5, 0.0), 32, 0.5, 1.0, 0.5, 0.0);
        world.playSound(location, Sound.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);
    }

    private Optional<UUID> pearlOwner(EnderPearl pearl) {
        ProjectileSource shooter = pearl.getShooter();
        if (shooter instanceof Player player) {
            return Optional.of(player.getUniqueId());
        }
        return Optional.empty();
    }

    private ItemStack pearlBucketFromHand(Player player, EquipmentSlot hand, ItemStack eventStack) {
        if (readBucketData(eventStack).isPresent()) {
            return eventStack;
        }

        if (hand == EquipmentSlot.OFF_HAND) {
            return player.getInventory().getItemInOffHand();
        }

        return player.getInventory().getItemInMainHand();
    }

    private void setBucketHand(Player player, EquipmentSlot hand, ItemStack stack) {
        if (hand == EquipmentSlot.OFF_HAND) {
            player.getInventory().setItemInOffHand(stack);
        } else {
            player.getInventory().setItemInMainHand(stack);
        }
    }

    private void consumeBucketFromHand(Player player, EquipmentSlot hand, ItemStack replacement) {
        PlayerInventory inventory = player.getInventory();
        ItemStack original = hand == EquipmentSlot.OFF_HAND ? inventory.getItemInOffHand() : inventory.getItemInMainHand();
        if (original.getAmount() <= 1) {
            setBucketHand(player, hand, replacement);
            return;
        }

        original.setAmount(original.getAmount() - 1);
        HashMap<Integer, ItemStack> leftovers = inventory.addItem(replacement);
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private void removeTrackedPearl(EnderPearl pearl) {
        pearlOwner(pearl).map(Bukkit::getPlayer).ifPresent(owner -> owner.getEnderPearls().stream()
                .filter(trackedPearl -> trackedPearl.getUniqueId().equals(pearl.getUniqueId()))
                .forEach(Entity::remove));
        pearl.remove();
    }

    private boolean captureLookedAtPearl(Player player, EquipmentSlot hand) {
        Optional<EnderPearl> lookedAtPearl = findLookedAtPearl(player);
        if (lookedAtPearl.isEmpty()) {
            return false;
        }

        return capturePearl(player, hand, lookedAtPearl.get(), true, false);
    }

    private boolean capturePearl(Player player, EquipmentSlot hand, EnderPearl pearl, boolean requireWater, boolean replaceFilledBucket) {
        Optional<UUID> ownerId = pearlOwner(pearl);
        if (ownerId.isEmpty() || (requireWater && !isCapturablePearl(pearl))) {
            return false;
        }

        UUID bucketId = capturePearlToBucket(pearl, ownerId.get());
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId.get());
        ItemStack pearlBucket = createBucketItem(bucketId, ownerId.get(), owner.getName(), Bukkit.getPlayer(ownerId.get()) != null);
        if (replaceFilledBucket) {
            setBucketHand(player, hand, pearlBucket);
        } else {
            consumeBucketFromHand(player, hand, pearlBucket);
        }
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_BUCKET_FILL, SoundCategory.PLAYERS, 1.0f, 1.0f);
        return true;
    }

    private boolean capturePearlFromWaterInteraction(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return false;
        }

        return interactCandidateWaterBlocks(event).stream()
                .filter(this::isWaterLike)
                .map(block -> findPearlForWaterInteraction(event.getPlayer(), block))
                .flatMap(Optional::stream)
                .min(Comparator.comparingDouble(pearl -> pearl.getLocation().distanceSquared(event.getPlayer().getLocation())))
                .map(pearl -> capturePearl(event.getPlayer(), event.getHand(), pearl, true, false))
                .orElse(false);
    }

    private UUID capturePearlToBucket(EnderPearl pearl, UUID ownerId) {
        UUID bucketId = UUID.randomUUID();
        OfflinePlayer owner = Bukkit.getOfflinePlayer(ownerId);
        capturedPearls.put(bucketId, new CapturedPearl(bucketId, ownerId, owner.getName()));

        removeWaterAroundCapturedPearl(pearl.getLocation());
        removeTrackedPearl(pearl);
        return bucketId;
    }

    private Optional<EnderPearl> findPearlForBucketFill(PlayerBucketFillEvent event) {
        return bucketFillCandidateBlocks(event).stream()
                .filter(this::isWaterLike)
                .map(block -> findPearlForWaterInteraction(event.getPlayer(), block))
                .flatMap(Optional::stream)
                .min(Comparator.comparingDouble(pearl -> pearl.getLocation().distanceSquared(event.getPlayer().getLocation())));
    }

    private List<Block> interactCandidateWaterBlocks(PlayerInteractEvent event) {
        Block clicked = event.getClickedBlock();
        List<Block> blocks = new ArrayList<>();
        blocks.add(clicked);
        BlockFace face = event.getBlockFace();
        if (face != null) {
            blocks.add(clicked.getRelative(face));
        }
        return blocks;
    }

    private List<Block> bucketFillCandidateBlocks(PlayerBucketFillEvent event) {
        Block clicked = event.getBlockClicked();
        Block involved = event.getBlock();
        Block relative = clicked.getRelative(event.getBlockFace());
        return List.of(involved, clicked, relative);
    }

    private Optional<EnderPearl> findPearlForWaterInteraction(Player player, Block waterBlock) {
        Block columnTop = highestConnectedWaterBlock(waterBlock);
        Location center = columnTop.getLocation().add(0.5, 0.5, 0.5);
        return player.getWorld()
                .getNearbyEntities(center, 1.35, 8.5, 1.35, entity -> entity instanceof EnderPearl)
                .stream()
                .map(EnderPearl.class::cast)
                .filter(pearl -> isPearlInBucketedBlock(pearl, waterBlock) || isPearlFloatingOnColumnTop(pearl, columnTop))
                .min(Comparator.comparingDouble(pearl -> pearl.getLocation().distanceSquared(center)));
    }

    private Optional<EnderPearl> findLookedAtPearl(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        Vector eyeVector = eye.toVector();
        List<EnderPearl> candidates = new ArrayList<>(player.getWorld()
                .getNearbyEntities(eye, 6.0, 6.0, 6.0, entity -> entity instanceof EnderPearl candidate && isCapturablePearl(candidate))
                .stream()
                .map(EnderPearl.class::cast)
                .toList());

        RayTraceResult blockTrace = player.getWorld().rayTraceBlocks(eye, direction, 6.0, FluidCollisionMode.ALWAYS, true);
        if (blockTrace != null && blockTrace.getHitPosition() != null) {
            Location focus = blockTrace.getHitPosition().toLocation(player.getWorld());
            player.getWorld()
                    .getNearbyEntities(focus, 1.75, 2.5, 1.75, entity -> entity instanceof EnderPearl candidate && isCapturablePearl(candidate))
                    .stream()
                    .map(EnderPearl.class::cast)
                    .filter(candidate -> candidates.stream().noneMatch(existing -> existing.getUniqueId().equals(candidate.getUniqueId())))
                    .forEach(candidates::add);
        }

        return candidates.stream()
                .map(EnderPearl.class::cast)
                .filter(pearl -> isPearlNearCursor(eyeVector, direction, pearl))
                .filter(pearl -> hasLineOfSightToPearl(eye, pearl))
                .min(Comparator
                        .comparingDouble((EnderPearl pearl) -> lookOffsetSquared(eyeVector, direction, pearl))
                        .thenComparingDouble(pearl -> eye.distanceSquared(pearl.getLocation())));
    }

    private boolean isPearlNearCursor(Vector eye, Vector direction, EnderPearl pearl) {
        Vector toPearl = eyeToPearlVector(eye, pearl);
        double alongLook = toPearl.dot(direction);
        if (alongLook < 0.0 || alongLook > 6.0) {
            return false;
        }

        return lookOffsetSquared(eye, direction, pearl) <= 1.0;
    }

    private double lookOffsetSquared(Vector eye, Vector direction, EnderPearl pearl) {
        Vector toPearl = eyeToPearlVector(eye, pearl);
        double alongLook = toPearl.dot(direction);
        return Math.max(0.0, toPearl.lengthSquared() - (alongLook * alongLook));
    }

    private Vector eyeToPearlVector(Vector eye, EnderPearl pearl) {
        return pearl.getLocation().toVector().subtract(eye).add(new Vector(0.0, -0.25, 0.0));
    }

    private boolean hasLineOfSightToPearl(Location eye, EnderPearl pearl) {
        Vector toPearl = eyeToPearlVector(eye.toVector(), pearl);
        double distance = toPearl.length();
        if (distance <= 0.001) {
            return true;
        }

        RayTraceResult blockHit = pearl.getWorld().rayTraceBlocks(
                eye,
                toPearl.clone().normalize(),
                distance,
                FluidCollisionMode.NEVER,
                true
        );
        return blockHit == null || blockHit.getHitPosition() == null
                || blockHit.getHitPosition().distanceSquared(eye.toVector()) + 0.04 >= (distance * distance);
    }

    private void suppressNextBucketEmpty(UUID playerId) {
        suppressedBucketEmptyPlayers.add(playerId);
        Bukkit.getScheduler().runTask(this, () -> suppressedBucketEmptyPlayers.remove(playerId));
    }

    private boolean isPearlInBucketedBlock(EnderPearl pearl, Block waterBlock) {
        return pearlSampledBlocks(pearl.getLocation()).stream()
                .anyMatch(block -> sameBlock(block, waterBlock) && isWaterLike(block))
                || isPearlInConnectedWaterColumn(pearl, waterBlock);
    }

    private boolean isPearlInConnectedWaterColumn(EnderPearl pearl, Block waterBlock) {
        if (!sameColumn(pearl.getLocation().getBlock(), waterBlock)) {
            return false;
        }

        Block lowest = lowestConnectedWaterBlock(waterBlock);
        Block highest = highestConnectedWaterBlock(waterBlock);

        int lowestY = lowest.getY();
        int highestY = highest.getY();
        return pearlSampledBlocks(pearl.getLocation()).stream()
                .anyMatch(block -> sameColumn(block, waterBlock)
                        && block.getY() >= lowestY
                        && block.getY() <= highestY
                        && isWaterLike(block));
    }

    private boolean sameColumn(Block first, Block second) {
        return first.getWorld().equals(second.getWorld())
                && first.getX() == second.getX()
                && first.getZ() == second.getZ();
    }

    private Block lowestConnectedWaterBlock(Block waterBlock) {
        Block lowest = waterBlock;
        while (isWaterLike(lowest.getRelative(BlockFace.DOWN)) && sameColumn(lowest.getRelative(BlockFace.DOWN), waterBlock)) {
            lowest = lowest.getRelative(BlockFace.DOWN);
        }
        return lowest;
    }

    private Block highestConnectedWaterBlock(Block waterBlock) {
        Block highest = waterBlock;
        while (isWaterLike(highest.getRelative(BlockFace.UP)) && sameColumn(highest.getRelative(BlockFace.UP), waterBlock)) {
            highest = highest.getRelative(BlockFace.UP);
        }
        return highest;
    }

    private List<Block> pearlSampledBlocks(Location location) {
        return List.of(
                location.getBlock(),
                location.clone().subtract(0.0, 0.25, 0.0).getBlock(),
                location.clone().subtract(0.0, 0.55, 0.0).getBlock(),
                location.clone().subtract(0.0, 0.95, 0.0).getBlock(),
                location.clone().subtract(0.0, 1.45, 0.0).getBlock(),
                location.clone().subtract(0.0, 1.95, 0.0).getBlock()
        );
    }

    private boolean sameBlock(Block first, Block second) {
        return first.getWorld().equals(second.getWorld())
                && first.getX() == second.getX()
                && first.getY() == second.getY()
                && first.getZ() == second.getZ();
    }

    private void removeWaterAroundCapturedPearl(Location location) {
        for (double yOffset : List.of(0.3, 0.0, -0.45, -0.95, -1.45, -1.95, -2.45, -2.95)) {
            Block block = location.clone().add(0.0, yOffset, 0.0).getBlock();
            if (block.getType() == Material.WATER || block.getType() == Material.BUBBLE_COLUMN) {
                block.setType(Material.AIR);
                return;
            }
        }
    }

    private Optional<PearlReleaseTarget> pearlReleaseTarget(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Block clicked = event.getClickedBlock();
            if (isWaterLike(clicked) || clicked.isPassable()) {
                return Optional.of(new PearlReleaseTarget(clicked, false));
            }

            BlockFace face = event.getBlockFace();
            if (face != null) {
                return Optional.of(new PearlReleaseTarget(clicked.getRelative(face), true));
            }
        }

        Player player = event.getPlayer();
        RayTraceResult result = player.getWorld().rayTraceBlocks(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                6.0,
                FluidCollisionMode.ALWAYS,
                true
        );

        if (result != null && result.getHitBlock() != null) {
            Block hit = result.getHitBlock();
            if (isWaterLike(hit) || hit.isPassable()) {
                return Optional.of(new PearlReleaseTarget(hit, false));
            }

            BlockFace face = result.getHitBlockFace();
            if (face != null) {
                return Optional.of(new PearlReleaseTarget(hit.getRelative(face), true));
            }
        }

        Location freeDrop = player.getEyeLocation().add(player.getEyeLocation().getDirection().normalize().multiply(3.0));
        return Optional.of(new PearlReleaseTarget(freeDrop.getBlock(), false));
    }

    private void releasePearlBucket(Player player, EquipmentSlot hand, CapturedPearl pearl, PearlReleaseTarget target) {
        placePearlBucketWater(target.block(), target.source());
        setBucketHand(player, hand, new ItemStack(Material.BUCKET));
        target.block().getWorld().playSound(target.block().getLocation(), Sound.ITEM_BUCKET_EMPTY, SoundCategory.BLOCKS, 1.0f, 1.0f);

        Location spawnLocation = target.block().getLocation().add(0.5, 0.5, 0.5);
        Player owner = Bukkit.getPlayer(pearl.ownerId());
        if (owner == null) {
            pendingTeleports.put(pearl.ownerId(), PendingTeleport.from(spawnLocation));
            return;
        }

        Bukkit.getScheduler().runTask(this, () -> releasePearl(pearl, spawnLocation));
    }

    private void placePearlBucketWater(Block block, boolean source) {
        if (isWaterLike(block)) {
            return;
        }

        if (!canPlaceDispensedWater(block)) {
            return;
        }

        if (source) {
            block.setType(Material.WATER);
            return;
        }

        BlockData data = Bukkit.createBlockData(Material.WATER);
        if (data instanceof Levelled levelled) {
            levelled.setLevel(Math.min(1, levelled.getMaximumLevel()));
        }
        block.setBlockData(data);
    }

    private boolean isWaterLike(Block block) {
        return block.getType() == Material.WATER || block.getType() == Material.BUBBLE_COLUMN;
    }

    private CapturedPearl consumeCapturedPearl(BucketData bucket, ItemStack stack) {
        CapturedPearl pearl = capturedPearls.remove(bucket.bucketId());
        if (pearl != null) {
            return pearl;
        }

        return new CapturedPearl(bucket.bucketId(), bucket.ownerId(), ownerName(stack));
    }

    private void removePearlBucketFromDispenser(Block block, UUID bucketId) {
        BlockState state = block.getState();
        if (!(state instanceof Dispenser dispenser)) {
            return;
        }

        Inventory inventory = dispenser.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int index = 0; index < contents.length; index++) {
            ItemStack stack = contents[index];
            Optional<BucketData> data = readBucketData(stack);
            if (data.isPresent() && data.get().bucketId().equals(bucketId)) {
                inventory.setItem(index, new ItemStack(Material.BUCKET, Math.max(1, stack.getAmount())));
                return;
            }
        }
    }

    private Optional<PearlBucketSlot> findDispensedPearlBucket(BlockDispenseEvent event) {
        Optional<BucketData> eventItemData = readBucketData(event.getItem());
        if (eventItemData.isPresent()) {
            return findPearlBucketInDispenser(event.getBlock(), eventItemData.get().bucketId())
                    .or(() -> findPearlBucketInSnapshot(event.getBlock(), eventItemData.get().bucketId()))
                    .or(() -> Optional.of(new PearlBucketSlot(-1, event.getItem(), eventItemData.get())));
        }

        if (event.getItem().getType() != Material.WATER_BUCKET && event.getItem().getType() != Material.BUCKET) {
            return Optional.empty();
        }

        return findPearlBucketInDispenser(event.getBlock(), null)
                .or(() -> findPearlBucketInSnapshot(event.getBlock(), null));
    }

    private Optional<PearlBucketSlot> findPearlBucketInDispenser(Block block, UUID bucketId) {
        BlockState state = block.getState();
        if (!(state instanceof Dispenser dispenser)) {
            return Optional.empty();
        }

        Inventory inventory = dispenser.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int index = 0; index < contents.length; index++) {
            ItemStack stack = contents[index];
            Optional<BucketData> data = readBucketData(stack);
            if (data.isPresent() && (bucketId == null || data.get().bucketId().equals(bucketId))) {
                return Optional.of(new PearlBucketSlot(index, stack, data.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<PearlBucketSlot> findPearlBucketInSnapshot(Block block, UUID bucketId) {
        List<PearlBucketSlot> snapshot = dispenserPearlBuckets.get(BlockKey.from(block));
        if (snapshot == null) {
            return Optional.empty();
        }

        return snapshot.stream()
                .filter(slot -> bucketId == null || slot.bucketData().bucketId().equals(bucketId))
                .findFirst();
    }

    private void replaceDispenserSlot(Block block, int slot, ItemStack replacement) {
        if (slot < 0) {
            return;
        }

        BlockState state = block.getState();
        if (state instanceof Dispenser dispenser) {
            dispenser.getInventory().setItem(slot, replacement);
        }
    }

    private BlockFace dispenserFacing(Block block) {
        BlockData data = block.getBlockData();
        if (data instanceof Directional directional) {
            return directional.getFacing();
        }
        return BlockFace.NORTH;
    }

    private boolean canPlaceDispensedWater(Block block) {
        Material type = block.getType();
        return type == Material.AIR
                || type == Material.CAVE_AIR
                || type == Material.VOID_AIR
                || type == Material.WATER
                || type == Material.BUBBLE_COLUMN
                || block.isPassable();
    }

    private boolean isCapturablePearl(EnderPearl pearl) {
        return pearlSampledBlocks(pearl.getLocation()).stream().anyMatch(this::isWaterLike)
                || hasWaterSupportBelow(pearl.getLocation());
    }

    private boolean hasWaterSupportBelow(Location location) {
        for (double yOffset : List.of(0.3, 0.0, -0.35, -0.7, -1.05, -1.4, -1.75, -2.1, -2.45, -2.8)) {
            Block block = location.clone().add(0.0, yOffset, 0.0).getBlock();
            if (isWaterLike(block)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPearlFloatingOnColumnTop(EnderPearl pearl, Block columnTop) {
        Location pearlLocation = pearl.getLocation();
        Location columnCenter = columnTop.getLocation().add(0.5, 0.5, 0.5);
        double horizontalDistanceSquared = square(pearlLocation.getX() - columnCenter.getX())
                + square(pearlLocation.getZ() - columnCenter.getZ());
        if (horizontalDistanceSquared > 0.75) {
            return false;
        }

        double verticalOffset = pearlLocation.getY() - columnTop.getY();
        return verticalOffset >= -0.5 && verticalOffset <= 2.25;
    }

    private double square(double value) {
        return value * value;
    }

    private boolean isPearlBucket(ItemStack stack) {
        return readBucketData(stack).isPresent();
    }

    private Optional<BucketData> readBucketData(ItemStack stack) {
        if (stack == null || stack.getType() != Material.WATER_BUCKET || !stack.hasItemMeta()) {
            return Optional.empty();
        }

        PersistentDataContainer container = stack.getItemMeta().getPersistentDataContainer();
        String bucketId = container.get(bucketIdKey, PersistentDataType.STRING);
        String ownerId = container.get(ownerIdKey, PersistentDataType.STRING);
        if (bucketId == null || ownerId == null) {
            return Optional.empty();
        }

        try {
            return Optional.of(new BucketData(UUID.fromString(bucketId), UUID.fromString(ownerId)));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    private ItemStack createBucketItem(UUID bucketId, UUID ownerId, String ownerName, boolean ownerOnline) {
        ItemStack stack = new ItemStack(Material.WATER_BUCKET);
        ItemMeta meta = stack.getItemMeta();
        PersistentDataContainer container = meta.getPersistentDataContainer();
        container.set(bucketIdKey, PersistentDataType.STRING, bucketId.toString());
        container.set(ownerIdKey, PersistentDataType.STRING, ownerId.toString());
        if (ownerName != null) {
            container.set(ownerNameKey, PersistentDataType.STRING, ownerName);
        }
        if (ownerOnline) {
            meta.setItemModel(pearlBucketModelKey);
            meta.displayName(Component.text("Pearl Bucket" + (ownerName == null ? "" : " (" + ownerName + ")")));
            meta.lore(List.of(
                    Component.text("Contains a suspended ender pearl."),
                    Component.text("Owner: " + (ownerName == null ? ownerId : ownerName))
            ));
        } else {
            meta.setItemModel(null);
            meta.displayName(null);
            meta.lore(null);
        }
        meta.setMaxStackSize(1);
        stack.setItemMeta(meta);
        return stack;
    }

    private void consumeOneAndGive(Player player, ItemStack original, ItemStack replacement) {
        PlayerInventory inventory = player.getInventory();
        if (original.getAmount() == 1) {
            inventory.setItemInMainHand(replacement);
            return;
        }

        original.setAmount(original.getAmount() - 1);
        HashMap<Integer, ItemStack> leftovers = inventory.addItem(replacement);
        leftovers.values().forEach(item -> player.getWorld().dropItemNaturally(player.getLocation(), item));
    }

    private void reconcileLoadedPearlBuckets() {
        Set<UUID> seenBucketIds = new HashSet<>();
        Set<BlockKey> seenDispensers = new HashSet<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            reconcileInventory(player.getInventory(), seenBucketIds);
            if (player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING) {
                reconcileInventory(player.getOpenInventory().getTopInventory(), seenBucketIds);
            }
        }

        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item item) {
                    normalizeDroppedItem(item);
                    readBucketData(item.getItemStack()).ifPresent(data -> seenBucketIds.add(data.bucketId()));
                }
            }
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                for (BlockState state : chunk.getTileEntities()) {
                    if (state instanceof Container container) {
                        reconcileInventory(container.getInventory(), seenBucketIds);
                        if (state instanceof Dispenser dispenser) {
                            snapshotDispenser(dispenser, seenDispensers);
                        }
                    }
                }
            }
        }

        capturedPearls.keySet().removeIf(bucketId -> !seenBucketIds.contains(bucketId));
        dispenserPearlBuckets.keySet().removeIf(blockKey -> !seenDispensers.contains(blockKey));
    }

    private void reconcileInventory(Inventory inventory, Set<UUID> seenBucketIds) {
        ItemStack[] contents = inventory.getContents();
        for (int index = 0; index < contents.length; index++) {
            ItemStack stack = contents[index];
            Optional<BucketData> data = readBucketData(stack);
            if (data.isEmpty()) {
                continue;
            }
            BucketData bucket = data.get();
            seenBucketIds.add(bucket.bucketId());
            capturedPearls.putIfAbsent(bucket.bucketId(), new CapturedPearl(bucket.bucketId(), bucket.ownerId(), ownerName(stack)));
            ItemStack normalized = createBucketItem(bucket.bucketId(), bucket.ownerId(), ownerName(stack), Bukkit.getPlayer(bucket.ownerId()) != null);
            if (!normalized.isSimilar(stack) || stack.getAmount() != 1) {
                inventory.setItem(index, normalized);
            }
        }
    }

    private void normalizeDroppedItem(Item item) {
        Optional<BucketData> data = readBucketData(item.getItemStack());
        if (data.isEmpty()) {
            return;
        }
        BucketData bucket = data.get();
        capturedPearls.putIfAbsent(bucket.bucketId(), new CapturedPearl(bucket.bucketId(), bucket.ownerId(), ownerName(item.getItemStack())));
        ItemStack normalized = createBucketItem(bucket.bucketId(), bucket.ownerId(), ownerName(item.getItemStack()), Bukkit.getPlayer(bucket.ownerId()) != null);
        item.setItemStack(normalized);
    }

    private void snapshotDispenser(Dispenser dispenser, Set<BlockKey> seenDispensers) {
        BlockKey key = BlockKey.from(dispenser.getBlock());
        seenDispensers.add(key);

        List<PearlBucketSlot> slots = new java.util.ArrayList<>();
        ItemStack[] contents = dispenser.getInventory().getContents();
        for (int index = 0; index < contents.length; index++) {
            ItemStack stack = contents[index];
            Optional<BucketData> data = readBucketData(stack);
            if (data.isPresent()) {
                slots.add(new PearlBucketSlot(index, stack.clone(), data.get()));
            }
        }

        if (slots.isEmpty()) {
            dispenserPearlBuckets.remove(key);
        } else {
            dispenserPearlBuckets.put(key, List.copyOf(slots));
        }
    }

    private String ownerName(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return null;
        }
        return stack.getItemMeta().getPersistentDataContainer().get(ownerNameKey, PersistentDataType.STRING);
    }

    private void loadPendingTeleports() {
        if (!getConfig().isConfigurationSection("pending-teleports")) {
            return;
        }

        for (String key : Objects.requireNonNull(getConfig().getConfigurationSection("pending-teleports")).getKeys(false)) {
            try {
                UUID ownerId = UUID.fromString(key);
                String path = "pending-teleports." + key + ".";
                pendingTeleports.put(ownerId, new PendingTeleport(
                        getConfig().getString(path + "world"),
                        getConfig().getDouble(path + "x"),
                        getConfig().getDouble(path + "y"),
                        getConfig().getDouble(path + "z"),
                        (float) getConfig().getDouble(path + "yaw"),
                        (float) getConfig().getDouble(path + "pitch")
                ));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Ignoring invalid pending pearl teleport id: " + key);
            }
        }
    }

    private void savePendingTeleports() {
        getConfig().set("pending-teleports", null);
        pendingTeleports.forEach((ownerId, teleport) -> {
            String path = "pending-teleports." + ownerId + ".";
            getConfig().set(path + "world", teleport.worldName());
            getConfig().set(path + "x", teleport.x());
            getConfig().set(path + "y", teleport.y());
            getConfig().set(path + "z", teleport.z());
            getConfig().set(path + "yaw", teleport.yaw());
            getConfig().set(path + "pitch", teleport.pitch());
        });
        saveConfig();
    }

    private record CapturedPearl(UUID bucketId, UUID ownerId, String ownerName) {
    }

    private record BucketData(UUID bucketId, UUID ownerId) {
    }

    private record PearlBucketSlot(int slot, ItemStack itemStack, BucketData bucketData) {
    }

    private record PearlReleaseTarget(Block block, boolean source) {
    }

    private record BlockKey(UUID worldId, int x, int y, int z) {
        static BlockKey from(Block block) {
            return new BlockKey(
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ()
            );
        }
    }

    private record PendingTeleport(String worldName, double x, double y, double z, float yaw, float pitch) {
        static PendingTeleport from(Location location) {
            return new PendingTeleport(
                    Objects.requireNonNull(location.getWorld()).getName(),
                    location.getX(),
                    location.getY(),
                    location.getZ(),
                    location.getYaw(),
                    location.getPitch()
            );
        }

        Location toLocation() {
            World world = Bukkit.getWorld(worldName);
            return world == null ? null : new Location(world, x, y, z, yaw, pitch);
        }
    }
}
