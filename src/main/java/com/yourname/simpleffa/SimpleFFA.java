package com.yourname.simpleffa;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

public class SimpleFFA extends JavaPlugin implements CommandExecutor, Listener {

    // Store original inventories to restore later
    private HashMap<UUID, ItemStack[]> savedInventoryContents = new HashMap<>();
    private HashMap<UUID, ItemStack[]> savedArmorContents = new HashMap<>();

    // CONFIGURATION
    private final String PVP_WORLD_NAME = "pvp_world";
    private final String MAIN_LOBBY_WORLD = "world"; 
    private final String KIT_TAG = "Arena Kit Item"; // Helper to identify starter gear

    @Override
    public void onEnable() {
        // Register Commands
        this.getCommand("pvp").setExecutor(this);
        this.getCommand("leave").setExecutor(this);
        
        // Register Events
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("SimpleFFA Enabled! Free-For-All Mode.");
    }

    // --- COMMAND HANDLING ---
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player)) return true;
        Player player = (Player) sender;

        // COMMAND: /pvp (Join the Arena)
        if (cmd.getName().equalsIgnoreCase("pvp")) {
            joinArena(player);
            return true;
        }

        // COMMAND: /leave (Quit and Save Loot)
        if (cmd.getName().equalsIgnoreCase("leave")) {
            leaveArena(player);
            return true;
        }

        return false;
    }

    // --- JOIN LOGIC ---
    private void joinArena(Player p) {
        if (p.getWorld().getName().equals(PVP_WORLD_NAME)) {
            p.sendMessage(ChatColor.RED + "You are already in the Arena!");
            return;
        }

        World pvpWorld = Bukkit.getWorld(PVP_WORLD_NAME);
        if (pvpWorld == null) {
            p.sendMessage(ChatColor.RED + "Error: PVP World not loaded.");
            return;
        }

        // 1. Save Survival Inventory
        savedInventoryContents.put(p.getUniqueId(), p.getInventory().getContents());
        savedArmorContents.put(p.getUniqueId(), p.getInventory().getArmorContents());

        // 2. Clear & Teleport
        p.getInventory().clear();
        p.getInventory().setArmorContents(null);
        p.teleport(pvpWorld.getSpawnLocation());

        // 3. Give Kit & Heal
        givePvpKit(p);
        p.setHealth(20);
        p.setFoodLevel(20);
        p.sendMessage(ChatColor.RED + "Welcome to the FFA Arena! Kill players to steal their loot.");
        p.sendMessage(ChatColor.YELLOW + "Type " + ChatColor.WHITE + "/leave" + ChatColor.YELLOW + " to exit and save your stolen loot.");
    }

    // --- LEAVE LOGIC (The Safe Exit) ---
    private void leaveArena(Player p) {
        if (!savedInventoryContents.containsKey(p.getUniqueId())) {
            p.sendMessage(ChatColor.RED + "You don't have a saved inventory to restore.");
            p.teleport(Bukkit.getWorld(MAIN_LOBBY_WORLD).getSpawnLocation());
            return;
        }

        // 1. Get current loot (what they are holding now)
        ItemStack[] currentLoot = p.getInventory().getContents();

        // 2. Restore Original Inventory
        p.getInventory().setContents(savedInventoryContents.get(p.getUniqueId()));
        p.getInventory().setArmorContents(savedArmorContents.get(p.getUniqueId()));

        // 3. Clean up memory
        savedInventoryContents.remove(p.getUniqueId());
        savedArmorContents.remove(p.getUniqueId());

        // 4. Merge Loot (Give them the items they stole)
        for (ItemStack item : currentLoot) {
            if (item != null && item.getType() != Material.AIR) {
                // If it is NOT a default kit item, give it to them
                if (!isKitItem(item)) {
                    HashMap<Integer, ItemStack> overflow = p.getInventory().addItem(item);
                    // If inventory full, drop at feet in lobby
                    if (!overflow.isEmpty()) {
                        for (ItemStack drop : overflow.values()) {
                            p.getWorld().dropItemNaturally(p.getLocation(), drop);
                        }
                    }
                }
            }
        }

        // 5. Teleport Home
        p.teleport(Bukkit.getWorld(MAIN_LOBBY_WORLD).getSpawnLocation());
        p.sendMessage(ChatColor.GREEN + "You have left the arena. Loot saved!");
    }

    // --- DEATH LOGIC ---
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player loser = event.getEntity();

        // Only handle deaths in PVP world
        if (!loser.getWorld().getName().equals(PVP_WORLD_NAME)) return;

        // 1. Let items drop naturally! (Winner can pick them up)
        // Optional: Remove "Kit Items" from drops to prevent trash clutter? 
        // Currently DISABLED so winner can pick up extra arrows/potions from kit.
        
        event.setDroppedExp(0); // Optional: Stop XP drops
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player loser = event.getPlayer();

        // If they died while in the arena system
        if (savedInventoryContents.containsKey(loser.getUniqueId())) {
            
            // 1. Send to Lobby
            event.setRespawnLocation(Bukkit.getWorld(MAIN_LOBBY_WORLD).getSpawnLocation());

            // 2. Restore Original Inventory (They lost the arena loot by dying)
            Bukkit.getScheduler().runTaskLater(this, () -> {
                loser.getInventory().setContents(savedInventoryContents.get(loser.getUniqueId()));
                loser.getInventory().setArmorContents(savedArmorContents.get(loser.getUniqueId()));
                
                savedInventoryContents.remove(loser.getUniqueId());
                savedArmorContents.remove(loser.getUniqueId());
                
                loser.sendMessage(ChatColor.RED + "You died! Your arena loot was dropped.");
            }, 5L);
        }
    }

    // --- KIT HELPER ---
    private void givePvpKit(Player p) {
        p.getInventory().setHelmet(createKitItem(Material.IRON_HELMET));
        p.getInventory().setChestplate(createKitItem(Material.IRON_CHESTPLATE));
        p.getInventory().setLeggings(createKitItem(Material.IRON_LEGGINGS));
        p.getInventory().setBoots(createKitItem(Material.IRON_BOOTS));
        p.getInventory().addItem(createKitItem(Material.IRON_SWORD));
        p.getInventory().addItem(createKitItem(Material.BOW));
        p.getInventory().addItem(createKitItem(Material.ARROW, 32));
        p.getInventory().addItem(createKitItem(Material.COOKED_BEEF, 16));
        p.getInventory().setItemInOffHand(createKitItem(Material.SHIELD));
    }

    private ItemStack createKitItem(Material mat, int amount) {
        ItemStack item = new ItemStack(mat, amount);
        ItemMeta meta = item.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add(ChatColor.GRAY + KIT_TAG); // This tag lets us delete it later
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }
    
    private ItemStack createKitItem(Material mat) { return createKitItem(mat, 1); }

    // Check if an item is a "Trash Kit Item"
    private boolean isKitItem(ItemStack item) {
        if (item.hasItemMeta() && item.getItemMeta().hasLore()) {
            for (String line : item.getItemMeta().getLore()) {
                if (line.contains(KIT_TAG)) return true;
            }
        }
        return false;
    }
}
