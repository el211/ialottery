package me.afelia.ialottery;

import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

public class IALottery extends JavaPlugin implements Listener {

    private Map<String, Reward> rewards = new HashMap<>();
    private Map<String, TicketConfig> ticketConfigs = new HashMap<>();
    private NamespacedKey lotteryTicketKey;

    private Map<UUID, List<PotionEffect>> playerEffects = new HashMap<>();
    private Map<UUID, Boolean> playerInAnimation = new HashMap<>();

    @Override
    public void onEnable() {
        this.saveDefaultConfig();

        lotteryTicketKey = new NamespacedKey(this, "lottery_ticket");

        ConfigurationSection rewardsConfig = getConfig().getConfigurationSection("tickets");
        for (String ticketKey : rewardsConfig.getKeys(false)) {
            ConfigurationSection ticketConfig = rewardsConfig.getConfigurationSection(ticketKey);

            ConfigurationSection rewardsSection = ticketConfig.getConfigurationSection("Rewards");
            Map<String, Reward> ticketRewards = new HashMap<>();
            for (String rewardKey : rewardsSection.getKeys(false)) {
                ConfigurationSection rewardConfig = rewardsSection.getConfigurationSection(rewardKey);
                String command = rewardConfig.getString("command");
                String message = ChatColor.translateAlternateColorCodes('&', rewardConfig.getString("message-after-teleport-back"));
                double chanceRate = rewardConfig.getDouble("chance-rate", 1.0);
                ticketRewards.put(rewardKey, new Reward(command, message, chanceRate));
            }

            String itemName = ChatColor.translateAlternateColorCodes('&', ticketConfig.getString("Ticket-Items.item-name"));
            Material itemMaterial = Material.valueOf(ticketConfig.getString("Ticket-Items.item"));
            int customModelData = ticketConfig.getInt("Ticket-Items.custom-model-data");
            boolean blindnessEffect = ticketConfig.getBoolean("Ticket-Items.blindness-effect");

            ConfigurationSection interactableBlock = ticketConfig.getConfigurationSection("interactable-block");
            Location interactableLocation = new Location(
                    Bukkit.getWorld(interactableBlock.getString("world")),
                    interactableBlock.getDouble("x"),
                    interactableBlock.getDouble("y"),
                    interactableBlock.getDouble("z")
            );

            ticketConfigs.put(ticketKey, new TicketConfig(ticketRewards, itemName, itemMaterial, customModelData, blindnessEffect, interactableLocation));
        }

        getServer().getPluginManager().registerEvents(this, this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (label.equalsIgnoreCase("ialotteryget")) {
            if (args.length == 1) {
                String ticketID = args[0];
                if (ticketConfigs.containsKey(ticketID)) {
                    if (sender instanceof Player) {
                        Player player = (Player) sender;
                        if (player.hasPermission("ialottery.get")) {
                            player.getInventory().addItem(createTicket(ticketID));
                            player.sendMessage(ChatColor.GREEN + "You received a " + ticketID + " ticket!");
                            return true;
                        } else {
                            player.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                            return false;
                        }
                    } else {
                        sender.sendMessage(ChatColor.RED + "Only players can use this command!");
                        return false;
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Invalid ticket ID!");
                    return false;
                }
            }
        } else if (label.equalsIgnoreCase("ialotterygive")) {
            if (args.length == 2) {
                if (sender instanceof Player) {
                    Player player = (Player) sender;
                    if (player.hasPermission("ialottery.give")) {
                        Player target = Bukkit.getPlayer(args[0]);
                        String ticketID = args[1];
                        if (target != null && ticketConfigs.containsKey(ticketID)) {
                            target.getInventory().addItem(createTicket(ticketID));
                            player.sendMessage(ChatColor.GREEN + "You gave a " + ticketID + " ticket to " + target.getName() + "!");
                            target.sendMessage(ChatColor.GREEN + "You received a " + ticketID + " ticket from " + player.getName() + "!");
                            return true;
                        } else {
                            if (target == null) {
                                sender.sendMessage(ChatColor.RED + "Player not found!");
                            } else {
                                sender.sendMessage(ChatColor.RED + "Invalid ticket ID!");
                            }
                            return false;
                        }
                    } else {
                        player.sendMessage(ChatColor.RED + "You don't have permission to use this command!");
                        return false;
                    }
                } else {
                    sender.sendMessage(ChatColor.RED + "Only players can use this command!");
                    return false;
                }
            }
        }
        return false;
    }

    private ItemStack createTicket(String ticketID) {
        TicketConfig ticketConfig = ticketConfigs.get(ticketID);
        ItemStack ticket = new ItemStack(ticketConfig.getItemMaterial());
        ItemMeta meta = ticket.getItemMeta();
        meta.setCustomModelData(ticketConfig.getCustomModelData());
        meta.setDisplayName(ChatColor.translateAlternateColorCodes('&', ticketConfig.getItemName()));
        meta.getPersistentDataContainer().set(lotteryTicketKey, PersistentDataType.BYTE, (byte) 1);
        ticket.setItemMeta(meta);
        return ticket;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        // Check if the event has already been cancelled to prevent multiple executions
        if (event.isCancelled()) {
            return;
        }

        // Cancel the event to prevent multiple executions
        event.setCancelled(true);

        getLogger().info("Player interact event triggered.");

        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            getLogger().info("Action is not right click block.");
            return;
        }

        Player player = event.getPlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isLotteryTicket(item)) {
            getLogger().info("Item is not a lottery ticket.");
            return;
        }

        for (Map.Entry<String, TicketConfig> entry : ticketConfigs.entrySet()) {
            getLogger().info("Checking ticket: " + entry.getKey());

            if (isTicket(item, entry.getKey())) {
                Location interactableLocation = entry.getValue().getInteractableLocation();
                getLogger().info("Interactable block location: " + interactableLocation.getBlockX() + ", " + interactableLocation.getBlockY() + ", " + interactableLocation.getBlockZ());

                if (isInteractableBlock(event.getClickedBlock().getLocation(), interactableLocation)) {
                    getLogger().info("Interacted with interactable block for ticket: " + entry.getKey());

                    player.getInventory().removeItem(item);
                    player.setGameMode(GameMode.SPECTATOR);
                    performLotteryAnimation(player, entry.getValue());
                    return;
                }
            }
        }
    }






    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.SPECTATOR) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        if (playerInAnimation.containsKey(player.getUniqueId()) && playerInAnimation.get(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You cannot use chat during the animation!");
        }
    }

    private void grantReward(Player player, Reward reward) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), reward.getCommand().replace("%player%", player.getName()));
        player.sendMessage(reward.getMessageAfterTeleportBack());
    }

    private Reward getRandomReward(TicketConfig ticketConfig) {
        double totalChanceRate = ticketConfig.getTotalChanceRate();
        double randomValue = new Random().nextDouble() * totalChanceRate;

        double cumulativeChance = 0;
        for (Reward reward : ticketConfig.getRewards().values()) {
            cumulativeChance += reward.getChanceRate();
            if (randomValue <= cumulativeChance) {
                return reward;
            }
        }
        return null;
    }

    private void performLotteryAnimation(final Player player, final TicketConfig ticketConfig) {
        player.setAllowFlight(true);
        player.setFlying(true);
        player.setCanPickupItems(false);
        player.setInvulnerable(true);

        final List<PotionEffect> appliedEffects = new ArrayList<>();
        if (ticketConfig.isBlindnessEffect()) {
            appliedEffects.add(new PotionEffect(PotionEffectType.BLINDNESS, 200, 1, false, false));
        }

        player.addPotionEffects(appliedEffects);

        new BukkitRunnable() {
            int tick = 0;

            @Override
            public void run() {
                if (tick >= 100) {
                    player.setGameMode(GameMode.SURVIVAL);
                    player.setAllowFlight(false);
                    player.setFlying(false);
                    player.setCanPickupItems(true);
                    player.setInvulnerable(false);

                    grantReward(player, getRandomReward(ticketConfig));

                    playerInAnimation.remove(player.getUniqueId());

                    cancel();
                    return;
                }

                player.sendTitle("Rolling Ticket...", String.valueOf(new Random().nextInt(100)), 0, 20, 0);
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BELL, SoundCategory.MASTER, 1, 1);

                tick++;
            }
            @Override
            public synchronized void cancel() throws IllegalStateException {
                super.cancel();
                // Remove potion effects applied during the animation
                for (PotionEffect effect : appliedEffects) {
                    player.removePotionEffect(effect.getType());
                }
            }


        }.runTaskTimer(this, 0L, 1L);

        playerEffects.put(player.getUniqueId(), appliedEffects);
        playerInAnimation.put(player.getUniqueId(), true);
    }

    private boolean isLotteryTicket(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(lotteryTicketKey, PersistentDataType.BYTE);
    }

    private boolean isTicket(ItemStack item, String ticketKey) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            String displayName = ChatColor.stripColor(meta.getDisplayName());
            return displayName.equalsIgnoreCase(ticketConfigs.get(ticketKey).getItemName());
        }
        return false;
    }

    private boolean isInteractableBlock(Location clickedLocation, Location interactableLocation) {
        return clickedLocation.getWorld().equals(interactableLocation.getWorld()) &&
                clickedLocation.getBlockX() == interactableLocation.getBlockX() &&
                clickedLocation.getBlockY() == interactableLocation.getBlockY() &&
                clickedLocation.getBlockZ() == interactableLocation.getBlockZ();
    }






    private class Reward {
        private String command;
        private String messageAfterTeleportBack;
        private double chanceRate;

        public Reward(String command, String messageAfterTeleportBack, double chanceRate) {
            this.command = command;
            this.messageAfterTeleportBack = messageAfterTeleportBack;
            this.chanceRate = chanceRate;
        }

        public String getCommand() {
            return command;
        }

        public String getMessageAfterTeleportBack() {
            return messageAfterTeleportBack;
        }

        public double getChanceRate() {
            return chanceRate;
        }
    }

    private class TicketConfig {
        private Map<String, Reward> rewards;
        private String itemName;
        private Material itemMaterial;
        private int customModelData;
        private boolean blindnessEffect;
        private Location interactableLocation;
        private Location teleportLocation;

        public TicketConfig(Map<String, Reward> rewards, String itemName, Material itemMaterial, int customModelData, boolean blindnessEffect, Location interactableLocation) {
            this.rewards = rewards;
            this.itemName = itemName;
            this.itemMaterial = itemMaterial;
            this.customModelData = customModelData;
            this.blindnessEffect = blindnessEffect;
            this.interactableLocation = interactableLocation;
        }



        public Map<String, Reward> getRewards() {
            return rewards;
        }

        public String getItemName() {
            return itemName;
        }

        public Material getItemMaterial() {
            return itemMaterial;
        }

        public int getCustomModelData() {
            return customModelData;
        }

        public boolean isBlindnessEffect() {
            return blindnessEffect;
        }

        public Location getInteractableLocation() {
            return interactableLocation;
        }

        public Location getTeleportLocation() {
            return teleportLocation;
        }

        public double getTotalChanceRate() {
            return rewards.values().stream().mapToDouble(Reward::getChanceRate).sum();
        }
    }

}
