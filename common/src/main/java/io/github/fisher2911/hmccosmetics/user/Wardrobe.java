package io.github.fisher2911.hmccosmetics.user;

import io.github.fisher2911.hmccosmetics.HMCCosmetics;
import io.github.fisher2911.hmccosmetics.config.WardrobeSettings;
import io.github.fisher2911.hmccosmetics.gui.ArmorItem;
import io.github.fisher2911.hmccosmetics.inventory.PlayerArmor;
import io.github.fisher2911.hmccosmetics.packet.PacketManager;
import io.github.fisher2911.hmccosmetics.task.SupplierTask;
import io.github.fisher2911.hmccosmetics.task.Task;
import io.github.fisher2911.hmccosmetics.task.TaskChain;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public class Wardrobe extends User {

    private final HMCCosmetics plugin;
    private final UUID ownerUUID;
    private boolean active;
    private boolean cameraLocked;

    private boolean spawned;

    private Location currentLocation;

    public Wardrobe(
            final HMCCosmetics plugin,
            final UUID uuid,
            final UUID ownerUUID,
            final PlayerArmor playerArmor,
            final EntityIds entityIds,
            final boolean active) {
        super(uuid, playerArmor, entityIds);
        this.plugin = plugin;
        this.ownerUUID = ownerUUID;
        this.active = active;
        this.wardrobe = this;
    }

    public void spawnFakePlayer(final Player viewer) {
        final WardrobeSettings settings = this.plugin.getSettings().getWardrobeSettings();
        if (settings.inDistanceOfStatic(viewer.getLocation())) {
            this.currentLocation = settings.getWardrobeLocation();
            new TaskChain(this.plugin).chain(
                    () -> {
                        viewer.teleport(settings.getViewerLocation());
                        this.cameraLocked = true;
                        this.hidePlayer();
                    }
            ).execute();
            // for if we ever switch to packets
//            final Location viewerLocation = settings.getViewerLocation();
//            final UUID viewerUUID = UUID.randomUUID();
//            new TaskChain(this.plugin).chain(() -> {
//                viewer.setGameMode(GameMode.SPECTATOR);
//            }).chain(
//                    () -> {
//                        PacketManager.sendPacket(
//                                viewer,
//                                PacketManager.getEntitySpawnPacket(
//                                        viewerLocation,
//                                        this.viewerId,
//                                        EntityType.ZOMBIE,
//                                        viewerUUID
//                                ),
//                                PacketManager.getLookPacket(this.viewerId, viewerLocation),
//                                PacketManager.getRotationPacket(this.viewerId, viewerLocation),
//                                PacketManager.getSpectatePacket(this.viewerId)
//                                );
//                    },
//                    true
//            ).execute();


        } else if (this.currentLocation == null) {
            this.currentLocation = viewer.getLocation().clone();
            this.currentLocation.setPitch(0);
            this.currentLocation.setYaw(0);
        } else if (this.spawned) {
            return;
        }



        Bukkit.getScheduler().runTaskLaterAsynchronously(
                this.plugin,
                () -> {
                    final int entityId = this.getEntityId();
                    PacketManager.sendFakePlayerSpawnPacket(this.currentLocation, this.getId(), entityId, viewer);
                    PacketManager.sendFakePlayerInfoPacket(viewer, this.getId(), viewer);
                    this.updateOutsideCosmetics(viewer, this.currentLocation, plugin.getSettings());
                    PacketManager.sendLookPacket(entityId, this.currentLocation, viewer);
                    PacketManager.sendRotationPacket(entityId, this.currentLocation, true, viewer);
                    PacketManager.sendPlayerOverlayPacket(entityId, viewer);
                },
                settings.getSpawnDelay()
        );

        this.spawned = true;
        this.startSpinTask(viewer);
    }

    public void despawnFakePlayer(final Player viewer, final UserManager userManager) {
        this.active = false;
        final WardrobeSettings settings = this.plugin.getSettings().getWardrobeSettings();
        Bukkit.getScheduler().runTaskLaterAsynchronously(
                this.plugin,
                () -> {
                    final int entityId = this.getEntityId();
                    PacketManager.sendEntityDestroyPacket(entityId, viewer);
                    PacketManager.sendRemovePlayerPacket(viewer, this.id, viewer);
                    this.despawnAttached();
                    this.despawnBalloon();
                    this.showPlayer(this.plugin.getUserManager());
                    this.spawned = false;
                    this.cameraLocked = false;
                    this.currentLocation = null;
                    final Collection<ArmorItem> armorItems = new ArrayList<>(this.getPlayerArmor().getArmorItems());
                    if (settings.isApplyCosmeticsOnClose()) {
                        final Optional<User> optionalUser = userManager.get(this.ownerUUID);
                        optionalUser.ifPresent(user -> Bukkit.getScheduler().runTask(
                                plugin,
                                () -> {
                                    for (final ArmorItem armorItem : armorItems) {
                                        if (!user.hasPermissionToUse(armorItem)) continue;
                                        userManager.setItem(user, armorItem);
                                    }
                                }
                        ));
                    }
                    this.getPlayerArmor().clear();
                    Bukkit.getScheduler().runTask(this.plugin, () -> {
                        if (viewer == null || !viewer.isOnline()) return;
                        viewer.teleport(settings.getLeaveLocation());
                    });

                    if (settings.isAlwaysDisplay()) {
                        this.currentLocation = settings.getWardrobeLocation();
                        if (this.currentLocation == null) return;
                        this.spawnFakePlayer(viewer);
                    }
                },
                settings.getDespawnDelay()
        );
    }

    private void startSpinTask(final Player player) {
        final AtomicInteger data = new AtomicInteger();
        final int rotationSpeed = this.plugin.getSettings().getWardrobeSettings().getRotationSpeed();
        final int entityId = this.getEntityId();
        final Task task = new SupplierTask(
                () -> {
                    if (this.currentLocation == null) return;
                    final Location location = this.currentLocation.clone();
                    final int yaw = data.get();
                    location.setYaw(yaw);
                    PacketManager.sendLookPacket(entityId, location, player);
                    this.updateOutsideCosmetics(player, location, this.plugin.getSettings());
                    location.setYaw(this.getNextYaw(yaw - 30, rotationSpeed));
                    PacketManager.sendRotationPacket(entityId, location, true, player);
                    data.set(this.getNextYaw(yaw, rotationSpeed));
                },
                () -> !this.spawned || this.currentLocation == null
        );
        this.plugin.getTaskManager().submit(task);
    }

    private int getNextYaw(final int current, final int rotationSpeed) {
        if (current + rotationSpeed > 179) return -179;
        return current + rotationSpeed;
    }

    public boolean isCameraLocked() {
        return this.active && this.cameraLocked;
    }

    @Override
    public boolean hasPermissionToUse(final ArmorItem armorItem) {
        return true;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(final boolean active) {
        this.active = active;
    }

    public void setCurrentLocation(final Location currentLocation) {
        this.currentLocation = currentLocation;
    }

    @Nullable
    public Location getCurrentLocation() {
        return currentLocation;
    }

    @Override
    public @Nullable Vector getVelocity() {
        return new Vector();
    }

    @Override
    @Nullable
    public Player getPlayer() {
        return Bukkit.getPlayer(this.ownerUUID);
    }

    private void hidePlayer() {
        Bukkit.getScheduler().runTask(this.plugin,
                () -> {
                    final Player player = this.getPlayer();
                    if (player == null) return;
                    for (final Player p : Bukkit.getOnlinePlayers()) {
                        p.hidePlayer(this.plugin, player);
                        player.hidePlayer(this.plugin, p);
                    }
                });
    }

    private void showPlayer(final UserManager userManager) {
        Bukkit.getScheduler().runTask(
                this.plugin,
                () -> {
                    final Player player = this.getPlayer();
                    if (player == null) return;
                    final Optional<User> optionalUser = userManager.get(player.getUniqueId());
                    for (final Player p : Bukkit.getOnlinePlayers()) {
                        final Optional<User> optional = userManager.get(p.getUniqueId());
                        if (optional.isEmpty()) continue;
                        if (optional.get().getWardrobe().isActive()) continue;
                        player.showPlayer(this.plugin, p);
                        p.showPlayer(this.plugin, player);
                        Bukkit.getScheduler().runTaskLaterAsynchronously(
                                this.plugin,
                                () -> {
                                    optional.ifPresent(user -> userManager.updateCosmetics(user, player));
                                    optionalUser.ifPresent(userManager::updateCosmetics);
                                },
                                1
                        );
                    }
                });
    }

    @Override
    public Equipment getEquipment() {
        return new Equipment();
    }
}
