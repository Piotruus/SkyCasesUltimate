package pl.skycases.ultimate;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class SkyCasesPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private final Map<String, Crate> crates = new LinkedHashMap<>();
    private final Map<String, String> placed = new HashMap<>();
    private final Set<UUID> opening = new HashSet<>();
    private final Map<UUID, Long> lastDaily = new HashMap<>();
    private final Map<UUID, Integer> streak = new HashMap<>();
    private final Map<UUID, String> editing = new HashMap<>();
    private final Map<UUID, Integer> rewardPages = new HashMap<>();
    private final Set<String> redeemed = new HashSet<>();
    private final Map<String, String> coupons = new HashMap<>();
    private final Map<String, Integer> couponLimits = new HashMap<>();
    private File extrasFile;
    private static final List<String> FEATURES = List.of("animated-open", "instant-open", "broadcast-rare", "effects", "history", "ranking", "coupons", "daily");
    private File cratesFile;
    private Connection database;

    private static final class Reward {
        String id;
        ItemStack item;
        double weight;
        Reward(String id, ItemStack item, double weight) { this.id=id; this.item=item; this.weight=weight; }
    }
    private static final class Crate {
        String id;
        String name;
        Material icon = Material.CHEST;
        String animation = "snake";
        int animatedSlot = 48;
        int instantSlot = 50;
        Material animatedIcon = Material.LIME_DYE;
        Material instantIcon = Material.LIGHT_BLUE_DYE;
        boolean enabled = true;
        LinkedHashMap<String, Boolean> overrides = new LinkedHashMap<>();
        LinkedHashMap<String, Reward> rewards = new LinkedHashMap<>();
        Crate(String id, String name) { this.id=id; this.name=name; }
    }
    private static final class CaseHolder implements InventoryHolder {
        final String id;
        final String mode;
        Inventory inventory;
        CaseHolder(String id) { this(id,"preview"); }
        CaseHolder(String id, String mode) { this.id=id; this.mode=mode; }
        @Override public Inventory getInventory() { return inventory; }
    }

    @Override public void onEnable() {
        saveDefaultConfig();
        cratesFile = new File(getDataFolder(), "crates.yml");
        extrasFile = new File(getDataFolder(), "extras.yml");
        try {
            database = DriverManager.getConnection("jdbc:sqlite:" + new File(getDataFolder(), "keys.db").getAbsolutePath());
            try (Statement statement = database.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS keys (uuid TEXT NOT NULL, crate TEXT NOT NULL, amount INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(uuid, crate))");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS deliveries (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, item TEXT NOT NULL, claimed INTEGER NOT NULL DEFAULT 0)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS openings (id INTEGER PRIMARY KEY AUTOINCREMENT, uuid TEXT NOT NULL, crate TEXT NOT NULL, reward TEXT NOT NULL, time INTEGER NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS stats (uuid TEXT PRIMARY KEY, total INTEGER NOT NULL DEFAULT 0)");
            }
        } catch (SQLException e) {
            getLogger().severe("Cannot open SQLite: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        loadCrates();
        loadExtras();
        Objects.requireNonNull(getCommand("case")).setExecutor(this);
        Objects.requireNonNull(getCommand("case")).setTabCompleter(this);
        getServer().getPluginManager().registerEvents(this, this);
        getLogger().info("SkyCases Ultimate 1.21.4 candidate enabled. Crates: " + crates.size());
    }
    @Override public void onDisable() {
        opening.clear();
        saveExtras();
        try { if (database != null) database.close(); } catch (SQLException ignored) { }
    }

    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }
    private void msg(CommandSender sender, String message) {
        sender.sendMessage(color(getConfig().getString("messages.prefix", "&dSkyCases &8» &r") + message));
    }
    private String loc(Block block) {
        return block.getWorld().getUID() + ":" + block.getX() + ":" + block.getY() + ":" + block.getZ();
    }
    private boolean validButtonSlot(int slot) { return slot >= 45 && slot <= 53; }
    private boolean validId(String s) { return s.matches("[a-z0-9_-]{1,32}"); }

    private void loadCrates() {
        crates.clear(); placed.clear();
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(cratesFile);
        ConfigurationSection section = yml.getConfigurationSection("crates");
        if (section != null) for (String id : section.getKeys(false)) {
            String base = "crates." + id;
            Crate crate = new Crate(id, yml.getString(base + ".name", id));
            try { crate.icon = Material.valueOf(yml.getString(base + ".icon", "CHEST")); } catch (IllegalArgumentException ignored) { }
            crate.animation = yml.getString(base + ".animation", "snake");
            crate.animatedSlot = validButtonSlot(yml.getInt(base + ".gui.animated-slot", 48)) ? yml.getInt(base + ".gui.animated-slot", 48) : 48;
            crate.instantSlot = validButtonSlot(yml.getInt(base + ".gui.instant-slot", 50)) ? yml.getInt(base + ".gui.instant-slot", 50) : 50;
            try { crate.animatedIcon = Material.valueOf(yml.getString(base + ".gui.animated-icon", "LIME_DYE")); } catch (IllegalArgumentException ignored) { }
            try { crate.instantIcon = Material.valueOf(yml.getString(base + ".gui.instant-icon", "LIGHT_BLUE_DYE")); } catch (IllegalArgumentException ignored) { }
            if (crate.animatedSlot == crate.instantSlot) crate.instantSlot = 50;
            crate.enabled = yml.getBoolean(base + ".enabled", true);
            ConfigurationSection opts = yml.getConfigurationSection(base + ".features");
            if (opts != null) for (String feature : opts.getKeys(false)) crate.overrides.put(feature, opts.getBoolean(feature));
            ConfigurationSection rewards = yml.getConfigurationSection(base + ".rewards");
            if (rewards != null) for (String rewardId : rewards.getKeys(false)) {
                ItemStack item = yml.getItemStack(base + ".rewards." + rewardId + ".item");
                double weight = yml.getDouble(base + ".rewards." + rewardId + ".weight", 1);
                if (item != null && !item.getType().isAir() && weight > 0 && Double.isFinite(weight))
                    crate.rewards.put(rewardId, new Reward(rewardId, item, weight));
            }
            crates.put(id, crate);
        }
        ConfigurationSection locations = yml.getConfigurationSection("locations");
        if (locations != null) for (String key : locations.getKeys(false)) {
            String crate = locations.getString(key);
            if (crates.containsKey(crate)) placed.put(key, crate);
        }
    }
    private void saveCrates() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Crate crate : crates.values()) {
            String base = "crates." + crate.id;
            yml.set(base + ".name", crate.name);
            yml.set(base + ".icon", crate.icon.name());
            yml.set(base + ".animation", crate.animation);
            yml.set(base + ".gui.animated-slot", crate.animatedSlot);
            yml.set(base + ".gui.instant-slot", crate.instantSlot);
            yml.set(base + ".gui.animated-icon", crate.animatedIcon.name());
            yml.set(base + ".gui.instant-icon", crate.instantIcon.name());
            yml.set(base + ".enabled", crate.enabled);
            for (var option : crate.overrides.entrySet()) yml.set(base + ".features." + option.getKey(), option.getValue());
            for (Reward reward : crate.rewards.values()) {
                yml.set(base + ".rewards." + reward.id + ".item", reward.item);
                yml.set(base + ".rewards." + reward.id + ".weight", reward.weight);
            }
        }
        for (Map.Entry<String, String> entry : placed.entrySet()) yml.set("locations." + entry.getKey(), entry.getValue());
        try { yml.save(cratesFile); } catch (IOException e) { getLogger().severe("Could not save crates.yml: " + e.getMessage()); }
    }
    private void loadExtras() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(extrasFile);
        coupons.clear(); couponLimits.clear(); redeemed.clear(); lastDaily.clear(); streak.clear();
        ConfigurationSection c = y.getConfigurationSection("coupons");
        if (c != null) for (String code : c.getKeys(false)) {
            coupons.put(code, y.getString("coupons."+code+".crate", ""));
            couponLimits.put(code, y.getInt("coupons."+code+".uses", 1));
        }
        redeemed.addAll(y.getStringList("redeemed"));
        ConfigurationSection d = y.getConfigurationSection("daily");
        if (d != null) for (String uuid : d.getKeys(false)) try { lastDaily.put(UUID.fromString(uuid), d.getLong(uuid)); } catch (IllegalArgumentException ignored) { }
    }
    private void saveExtras() {
        if (extrasFile == null) return;
        YamlConfiguration y = new YamlConfiguration();
        for (String code : coupons.keySet()) {
            y.set("coupons."+code+".crate", coupons.get(code));
            y.set("coupons."+code+".uses", couponLimits.getOrDefault(code, 1));
        }
        y.set("redeemed", new ArrayList<>(redeemed));
        for (var d : lastDaily.entrySet()) y.set("daily."+d.getKey(), d.getValue());
        try { y.save(extrasFile); } catch (IOException e) { getLogger().severe("Cannot save extras: "+e.getMessage()); }
    }
    private boolean feature(Crate crate, String name) {
        return crate != null && crate.overrides.containsKey(name) ? crate.overrides.get(name) : getConfig().getBoolean("settings."+name, true);
    }
    private String encodeItem(ItemStack item) { return Base64.getEncoder().encodeToString(item.serializeAsBytes()); }
    private ItemStack decodeItem(String raw) { return ItemStack.deserializeBytes(Base64.getDecoder().decode(raw)); }
    private void pending(UUID uuid, ItemStack item) throws SQLException {
        try (PreparedStatement ps = database.prepareStatement("INSERT INTO deliveries(uuid,item) VALUES(?,?)")) {
            ps.setString(1, uuid.toString()); ps.setString(2, encodeItem(item)); ps.executeUpdate();
        }
    }
    private void claim(Player player) {
        try (PreparedStatement ps = database.prepareStatement("SELECT id,item FROM deliveries WHERE uuid=? AND claimed=0 ORDER BY id")) {
            ps.setString(1, player.getUniqueId().toString());
            
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ItemStack item = decodeItem(rs.getString(2));
                    // Deliver only when inventory has capacity; leave other rewards in mailbox.
                    if (!canFit(player, item)) { msg(player, "&eMasz oczekującą nagrodę. Zwolnij miejsce i użyj /case claim."); break; }

                    try (PreparedStatement mark = database.prepareStatement("UPDATE deliveries SET claimed=1 WHERE id=? AND claimed=0")) {
                        mark.setLong(1, rs.getLong(1));
                        if (mark.executeUpdate() == 1) {
                            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(item);
                            if (!leftovers.isEmpty()) {
                                for (ItemStack leftover : leftovers.values()) pending(player.getUniqueId(), leftover);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) { getLogger().severe("Pending delivery error: "+e.getMessage()); }
    }
    private boolean canFit(Player p, ItemStack item) {
        int space=0;
        for (ItemStack current : p.getInventory().getStorageContents()) {
            if (current == null || current.getType().isAir()) space += item.getMaxStackSize();
            else if (current.isSimilar(item)) space += Math.max(0, current.getMaxStackSize()-current.getAmount());
            if (space >= item.getAmount()) return true;
        }
        return false;
    }
    @EventHandler public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        Bukkit.getScheduler().runTaskLater(this, () -> { if (e.getPlayer().isOnline()) claim(e.getPlayer()); }, 20L);
    }
    private void logOpen(UUID uuid, Crate crate, Reward reward) throws SQLException {
        try (PreparedStatement ps = database.prepareStatement("INSERT INTO openings(uuid,crate,reward,time) VALUES(?,?,?,?)")) {
            ps.setString(1, uuid.toString()); ps.setString(2, crate.id); ps.setString(3, reward.id);
            ps.setLong(4, System.currentTimeMillis()); ps.executeUpdate();
        }
        try (PreparedStatement ps = database.prepareStatement("INSERT INTO stats(uuid,total) VALUES(?,1) ON CONFLICT(uuid) DO UPDATE SET total=total+1")) {
            ps.setString(1, uuid.toString()); ps.executeUpdate();
        }
    }
    private void admin(Player p) {
        CaseHolder h = new CaseHolder("", "admin");
        Inventory inv = Bukkit.createInventory(h, 54, color("&8SkyCases &5Studio")); h.inventory = inv;
        int i=0;
        for (String f : FEATURES) {
            boolean active = getConfig().getBoolean("settings."+f, true);
            inv.setItem(i++, named(active ? Material.LIME_DYE : Material.GRAY_DYE,
                (active ? "&aON &f" : "&cOFF &f")+f, "&7Kliknij, aby przełączyć"));
        }
        inv.setItem(31, named(Material.CHEST, "&eEdytuj skrzynki", "&7Kliknij, aby wybrać skrzynkę"));
        inv.setItem(49, named(Material.BARRIER,"&cZamknij"));
        p.openInventory(inv);
    }
    private void crateSelector(Player p) {
        CaseHolder h = new CaseHolder("", "selector");
        Inventory inv = Bukkit.createInventory(h, 54, color("&8Wybierz skrzynkę")); h.inventory = inv;
        int slot = 0;
        for (Crate c : crates.values()) {
            if (slot >= 45) break;
            inv.setItem(slot++, named(c.icon, "&e" + c.id, "&7" + c.name, "&aKliknij, aby edytować"));
        }
        inv.setItem(49, named(Material.ARROW, "&ePowrót"));
        p.openInventory(inv);
    }
    private void editor(Player p, Crate crate) {
        CaseHolder h = new CaseHolder(crate.id, "editor");
        Inventory inv = Bukkit.createInventory(h, 54, color("&8Edytor: &d"+crate.name)); h.inventory = inv;
        inv.setItem(10, named(crate.icon, "&eIkona", "&7Ustaw item trzymany w ręce: /case icon "+crate.id));
        inv.setItem(12, named(Material.NAME_TAG, "&eNazwa", "&7/case rename "+crate.id+" <nazwa>"));
        inv.setItem(14, named(Material.CHEST, "&eNagrody i szanse", "&7Kliknij, aby otworzyć edytor"));
        inv.setItem(16, named(Material.CLOCK, "&eAnimacja: &f"+crate.animation, "&7Kliknij, aby zmienić"));
        inv.setItem(28, named(crate.enabled?Material.LIME_DYE:Material.GRAY_DYE,"&eSkrzynka: "+(crate.enabled?"&aON":"&cOFF"),"&7Kliknij, aby przełączyć"));
        inv.setItem(30, named(Material.BOOK,"&eFunkcje skrzynki", "&7/case feature <id> <moduł> on/off/inherit"));
        inv.setItem(32, named(Material.TRIPWIRE_HOOK,"&eKlucze", "&7/case givekey <gracz> <id> <liczba>"));
        inv.setItem(34, named(Material.PAINTING, "&ePrzyciski GUI", "&7/case gui "+crate.id+" animated-slot 48", "&7/case gui "+crate.id+" instant-slot 50", "&7/case gui "+crate.id+" animated-icon", "&7/case gui "+crate.id+" instant-icon"));
        inv.setItem(49, named(Material.ARROW,"&ePowrót do panelu"));
        p.openInventory(inv);
    }
    private void rewardEditor(Player p, Crate crate, int page) {
        int total = crate.rewards.size();
        int maxPage = Math.max(0, (total-1)/45);
        page = Math.max(0, Math.min(page, maxPage));
        rewardPages.put(p.getUniqueId(), page);
        CaseHolder h = new CaseHolder(crate.id, "rewards");
        Inventory inv = Bukkit.createInventory(h, 54, color("&8Nagrody: &d"+crate.id)); h.inventory=inv;
        List<Reward> rewards = new ArrayList<>(crate.rewards.values());
        for (int i=0; i<45 && page*45+i<rewards.size(); i++) {
            Reward r = rewards.get(page*45+i);
            ItemStack icon=r.item.clone(); ItemMeta meta=icon.getItemMeta();
            List<String> lore=meta.hasLore() && meta.getLore()!=null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            double sum=crate.rewards.values().stream().mapToDouble(v->v.weight).sum();
            lore.add(color("&7ID: &f"+r.id));
            lore.add(color(String.format(Locale.US,"&7Szansa: &e%.2f%%",r.weight/sum*100.0)));
            lore.add(color("&aLPM: +1 waga &8| &cPPM: -1 waga"));
            lore.add(color("&cShift + PPM: usuń nagrodę"));
            meta.setLore(lore); icon.setItemMeta(meta); inv.setItem(i,icon);
        }
        inv.setItem(45,named(Material.ARROW,"&ePoprzednia strona"));
        inv.setItem(49,named(Material.BOOK,"&eDodaj nagrodę", "&7Trzymaj item i użyj:","&f/case reward add "+crate.id+" <id> <waga>"));
        inv.setItem(53,named(Material.ARROW,"&eNastępna strona"));
        p.openInventory(inv);
    }
    private int keys(UUID uuid, String id) throws SQLException {
        try (PreparedStatement ps = database.prepareStatement("SELECT amount FROM keys WHERE uuid=? AND crate=?")) {
            ps.setString(1, uuid.toString()); ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getInt(1) : 0; }
        }
    }
    private void addKeys(UUID uuid, String id, int amount) throws SQLException {
        try (PreparedStatement ps = database.prepareStatement("INSERT INTO keys(uuid,crate,amount) VALUES(?,?,?) ON CONFLICT(uuid,crate) DO UPDATE SET amount=amount+excluded.amount")) {
            ps.setString(1, uuid.toString()); ps.setString(2, id); ps.setInt(3, amount); ps.executeUpdate();
        }
    }
    private boolean reserveOpening(UUID uuid, Crate crate, Reward reward) throws SQLException {
        boolean original = database.getAutoCommit();
        database.setAutoCommit(false);
        try {
            try (PreparedStatement ps = database.prepareStatement("UPDATE keys SET amount=amount-1 WHERE uuid=? AND crate=? AND amount>0")) {
                ps.setString(1, uuid.toString()); ps.setString(2, crate.id);
                if (ps.executeUpdate() != 1) { database.rollback(); return false; }
            }
            pending(uuid, reward.item.clone());
            logOpen(uuid, crate, reward);
            database.commit();
            return true;
        } catch (SQLException | RuntimeException ex) {
            database.rollback(); throw ex;
        } finally { database.setAutoCommit(original); }
    }
    private boolean takeKey(UUID uuid, String id) throws SQLException {
        try (PreparedStatement ps = database.prepareStatement("UPDATE keys SET amount=amount-1 WHERE uuid=? AND crate=? AND amount>0")) {
            ps.setString(1, uuid.toString()); ps.setString(2, id);
            return ps.executeUpdate() == 1;
        }
    }
    private ItemStack named(Material type, String title, String... lore) {
        ItemStack item = new ItemStack(type);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(color(title));
        if (lore.length > 0) meta.setLore(Arrays.stream(lore).map(this::color).toList());
        item.setItemMeta(meta);
        return item;
    }
    private void show(Player player, Crate crate) {
        CaseHolder holder = new CaseHolder(crate.id);
        Inventory inv = Bukkit.createInventory(holder, 54, net.kyori.adventure.text.Component.text("\uE001").font(net.kyori.adventure.key.Key.key("skycases", "crate")));
        holder.inventory = inv;
        int slot = 0;
        for (Reward reward : crate.rewards.values()) {
            if (slot >= 45) break;
            ItemStack icon = reward.item.clone();
            ItemMeta meta = icon.getItemMeta();
            List<String> lore = meta.hasLore() && meta.getLore() != null ? new ArrayList<>(meta.getLore()) : new ArrayList<>();
            lore.add(color("&7Waga: &e" + reward.weight));
            meta.setLore(lore); icon.setItemMeta(meta);
            inv.setItem(slot++, icon);
        }
        for (int i=45; i<54; i++) inv.setItem(i, named(Material.GRAY_STAINED_GLASS_PANE, " "));
        if (feature(crate, "animated-open"))
            inv.setItem(crate.animatedSlot, named(crate.animatedIcon, "&aOtwórz z animacją", "&7Koszt: 1 klucz"));
        if (feature(crate, "instant-open"))
            inv.setItem(crate.instantSlot, named(crate.instantIcon, "&bOtwórz bez animacji", "&7Koszt: 1 klucz"));
        player.openInventory(inv);
    }
    private Reward roll(Crate crate) {
        double total = crate.rewards.values().stream().mapToDouble(r -> r.weight).sum();
        if (!Double.isFinite(total) || total <= 0) return null;
        double value = ThreadLocalRandom.current().nextDouble(total);
        for (Reward reward : crate.rewards.values()) {
            value -= reward.weight;
            if (value < 0) return reward;
        }
        return crate.rewards.values().stream().reduce((a,b)->b).orElse(null);
    }
    private void deliver(Player player, Reward reward) {
        ItemStack item = reward.item.clone();
        claim(player);
        msg(player, "&aWylosowano: &f" + (item.hasItemMeta() && item.getItemMeta().hasDisplayName() ? item.getItemMeta().getDisplayName() : item.getType().name()));
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1, 1.2f);
    }
    private void open(Player player, Crate crate, boolean animated) {
        if (!crate.enabled) { msg(player, "&cTa skrzynka jest wyłączona."); return; }
        if (!feature(crate, animated ? "animated-open" : "instant-open")) { msg(player, "&cTen sposób otwierania jest wyłączony."); return; }
        if (opening.contains(player.getUniqueId())) { msg(player, "&cPoczekaj na koniec otwierania."); return; }
        if (crate.rewards.isEmpty()) { msg(player, "&cTa skrzynka nie ma nagród."); return; }
        Reward reward = roll(crate);
        if (reward == null) { msg(player, "&cNieprawidłowe szanse nagród."); return; }
        try {
            if (!reserveOpening(player.getUniqueId(), crate, reward)) {
                msg(player, "&cNie masz klucza do tej skrzynki."); return;
            }
        } catch (SQLException e) {
            getLogger().severe("Opening transaction failed: " + e.getMessage());
            msg(player, "&cBłąd zapisu otwarcia. Klucz nie został pobrany."); return;
        }
        opening.add(player.getUniqueId());
        player.closeInventory();
        if (!animated) {
            deliver(player, reward);
            opening.remove(player.getUniqueId());
            return;
        }
        CaseHolder holder = new CaseHolder(crate.id);
        Inventory inv = Bukkit.createInventory(holder, 54, color("&8Losowanie..."));
        holder.inventory = inv;
        player.openInventory(inv);
        int ticks = Math.max(20, Math.min(200, getConfig().getInt("settings.animation-ticks", 60)));
        new BukkitRunnable() {
            int elapsed = 0;
            @Override public void run() {
                if (!player.isOnline()) { opening.remove(player.getUniqueId()); cancel(); return; }
                if (elapsed >= ticks) {
                    cancel(); player.closeInventory(); deliver(player, reward);
                    opening.remove(player.getUniqueId()); return;
                }
                // A 54-slot animation with moving items. Final prize is already secured in SQLite.
                int[] path;
                if (crate.animation.equals("snake")) {
                    path = new int[]{10,11,12,13,14,15,16,25,34,43,42,41,40,39,38,37,28,19};
                } else if (crate.animation.equals("mystery")) {
                    path = new int[]{11,13,15,20,22,24,29,31,33,38,40,42};
                } else {
                    path = new int[]{19,20,21,22,23,24,25};
                }
                inv.clear();
                int offset = elapsed / 5;
                for (int i = 0; i < path.length; i++) {
                    Reward candidate = roll(crate);
                    if (candidate != null) inv.setItem(path[(i + offset) % path.length], candidate.item.clone());
                }
                inv.setItem(49, named(Material.CLOCK, "&eLosowanie...", "&7Nagroda została już wylosowana"));
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.2f);
                elapsed += 5;
            }
        }.runTaskTimer(this, 0L, 5L);
    }
    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK && event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        String id = placed.get(loc(event.getClickedBlock()));
        if (id == null) return;
        event.setCancelled(true);
        if (!event.getPlayer().hasPermission("skycases.use")) return;
        Crate crate = crates.get(id);
        if (crate != null && crate.enabled) show(event.getPlayer(), crate);
    }
    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CaseHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        if (holder.mode.equals("admin")) {
            if (!player.hasPermission("skycases.admin")) return;
            if (event.getSlot() >= 0 && event.getSlot() < FEATURES.size()) {
                String f = FEATURES.get(event.getSlot());
                getConfig().set("settings."+f, !getConfig().getBoolean("settings."+f,true));
                saveConfig(); admin(player);
            } else if (event.getSlot()==31) crateSelector(player);
            else if (event.getSlot()==49) player.closeInventory();
            return;
        }
        if (holder.mode.equals("selector")) {
            if (!player.hasPermission("skycases.admin")) return;
            if (event.getSlot()==49) { admin(player); return; }
            if (event.getSlot() >= 0 && event.getSlot() < 45) {
                List<Crate> list = new ArrayList<>(crates.values());
                if (event.getSlot() < list.size()) editor(player, list.get(event.getSlot()));
            }
            return;
        }
        if (holder.mode.equals("editor")) {
            if (!player.hasPermission("skycases.admin")) return;
            Crate edit = crates.get(holder.id);
            if (edit == null) return;
            if (event.getSlot()==14) { rewardEditor(player,edit,0); return; }
            if (event.getSlot()==16) { edit.animation = edit.animation.equals("snake") ? "roulette" : edit.animation.equals("roulette") ? "mystery" : "snake"; saveCrates(); editor(player,edit); }
            if (event.getSlot()==28) { edit.enabled=!edit.enabled; saveCrates(); editor(player,edit); }
            if (event.getSlot()==49) admin(player);
            return;
        }
        if (holder.mode.equals("rewards")) {
            if (!player.hasPermission("skycases.admin")) return;
            Crate c=crates.get(holder.id); if (c==null) return;
            int page=rewardPages.getOrDefault(player.getUniqueId(),0);
            if (event.getSlot()==45) { rewardEditor(player,c,page-1); return; }
            if (event.getSlot()==53) { rewardEditor(player,c,page+1); return; }
            if (event.getSlot()>=0 && event.getSlot()<45) {
                List<Reward> list=new ArrayList<>(c.rewards.values()); int idx=page*45+event.getSlot();
                if (idx>=list.size()) return;
                Reward r=list.get(idx);
                if (event.isShiftClick() && event.isRightClick()) c.rewards.remove(r.id);
                else if (event.isLeftClick()) r.weight+=1.0;
                else if (event.isRightClick()) r.weight=Math.max(0.1,r.weight-1.0);
                saveCrates(); rewardEditor(player,c,page);
            }
            return;
        }
        if (!holder.mode.equals("preview")) return;
        Crate crate = crates.get(holder.id);
        if (crate == null || event.getView().getTopInventory().getSize() != 54) return;
        if (event.getSlot() == crate.animatedSlot && feature(crate,"animated-open")) open(player, crate, true);
        else if (event.getSlot() == crate.instantSlot && feature(crate,"instant-open")) open(player, crate, false);
    }
    @EventHandler public void onInventoryDrag(InventoryDragEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof CaseHolder)) return;
        for (int slot : event.getRawSlots()) if (slot < event.getView().getTopInventory().getSize()) { event.setCancelled(true); return; }
    }
    @EventHandler public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (placed.containsKey(loc(event.getBlock()))) event.setCancelled(true);
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            msg(sender, "&e/case list, /case preview <id>, /case keys <id>");
            if (sender.hasPermission("skycases.admin")) msg(sender, "&e/case create|delete|rename|set|unset|reward|givekey|reload");
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("admin") && sender instanceof Player p) {
            if (!p.hasPermission("skycases.admin")) { msg(sender,"&cBrak uprawnień."); return true; }
            admin(p); return true;
        }
        if (action.equals("list")) { msg(sender, "&7Skrzynki: &f" + String.join(", ", crates.keySet())); return true; }
        if (action.equals("keys") && args.length >= 2 && sender instanceof Player p) {
            try { msg(sender, "&eKlucze " + args[1] + ": &f" + keys(p.getUniqueId(), args[1])); }
            catch (SQLException e) { msg(sender, "&cBłąd bazy danych."); }
            return true;
        }
        if (action.equals("preview") && args.length >= 2 && sender instanceof Player p) {
            Crate crate = crates.get(args[1]);
            if (crate == null || !crate.enabled) msg(sender, "&cSkrzynka nie istnieje lub jest wyłączona."); else show(p, crate);
            return true;
        }
        if (action.equals("redeem") && args.length>=2 && sender instanceof Player p) {
            if (!getConfig().getBoolean("settings.coupons",true)) { msg(sender,"&cKupony są wyłączone."); return true; }
            String code=args[1].toLowerCase(Locale.ROOT), crate=coupons.get(code), key=p.getUniqueId()+":"+code;
            if (crate==null || redeemed.contains(key) || couponLimits.getOrDefault(code,0)<=0) { msg(sender,"&cKupon nie istnieje, wygasł lub został wykorzystany."); return true; }
            try { addKeys(p.getUniqueId(),crate,1); redeemed.add(key); couponLimits.put(code,couponLimits.get(code)-1); saveExtras(); msg(sender,"&aOtrzymano 1 klucz do "+crate); }
            catch (SQLException e) { msg(sender,"&cBłąd bazy danych."); } return true;
        }
        if (action.equals("daily") && sender instanceof Player p) {
            if (!getConfig().getBoolean("settings.daily",true)) { msg(sender,"&cDaily jest wyłączone."); return true; }
            String id=getConfig().getString("daily-crate", "daily");
            if (!crates.containsKey(id)) { msg(sender,"&cAdministrator nie ustawił skrzynki daily."); return true; }
            long now=System.currentTimeMillis();
            if (now-lastDaily.getOrDefault(p.getUniqueId(),0L)<86400000L) { msg(sender,"&cNagroda dzienna jest jeszcze niedostępna."); return true; }
            try { addKeys(p.getUniqueId(),id,1); lastDaily.put(p.getUniqueId(),now); saveExtras(); msg(sender,"&aOtrzymano klucz dzienny!"); }
            catch (SQLException e) { msg(sender,"&cBłąd bazy danych."); } return true;
        }
        if (action.equals("stats") && sender instanceof Player p) {
            try (PreparedStatement ps=database.prepareStatement("SELECT total FROM stats WHERE uuid=?")) {
                ps.setString(1,p.getUniqueId().toString()); try (ResultSet rs=ps.executeQuery()) { msg(sender,"&eOtwarcia: &f"+(rs.next()?rs.getInt(1):0)); }
            } catch(SQLException e) { msg(sender,"&cBłąd bazy danych."); } return true;
        }
        if (action.equals("top")) {
            try (Statement st=database.createStatement(); ResultSet rs=st.executeQuery("SELECT uuid,total FROM stats ORDER BY total DESC LIMIT 10")) {
                int n=0; while(rs.next()) msg(sender,"&e"+(++n)+". &f"+rs.getString(1)+" &7- "+rs.getInt(2));
            } catch(SQLException e) { msg(sender,"&cBłąd bazy danych."); } return true;
        }
        if (action.equals("claim") && sender instanceof Player p) { claim(p); msg(p,"&aSprawdzono oczekujące nagrody."); return true; }
        if (!sender.hasPermission("skycases.admin")) { msg(sender, "&cBrak uprawnień."); return true; }
        if (action.equals("edit") && args.length>=2 && sender instanceof Player p) {
            Crate c=crates.get(args[1]); if (c==null) msg(sender,"&cNie ma skrzynki."); else editor(p,c); return true;
        }
        if (action.equals("feature") && args.length>=4) {
            Crate c=crates.get(args[1]); String f=args[2]; String v=args[3];
            if (c==null || !FEATURES.contains(f) || !List.of("on","off","inherit").contains(v)) { msg(sender,"&c/case feature <skrzynka> <moduł> on/off/inherit"); return true; }
            if (v.equals("inherit")) c.overrides.remove(f); else c.overrides.put(f,v.equals("on"));
            saveCrates(); msg(sender,"&aZapisano ustawienie."); return true;
        }
        if (action.equals("icon") && args.length>=2 && sender instanceof Player p) {
            Crate c=crates.get(args[1]); if (c==null || p.getInventory().getItemInMainHand().getType().isAir()) { msg(sender,"&cBrak skrzynki lub itemu w ręce."); return true; }
            c.icon=p.getInventory().getItemInMainHand().getType(); saveCrates(); msg(sender,"&aZmieniono ikonę."); return true;
        }
        if (action.equals("clone") && args.length>=3) {
            Crate from=crates.get(args[1]); String id=args[2];
            if (from==null || !validId(id) || crates.containsKey(id)) { msg(sender,"&cNieprawidłowe ID."); return true; }
            Crate copy=new Crate(id,from.name); copy.icon=from.icon; copy.animation=from.animation; copy.enabled=from.enabled; copy.animatedSlot=from.animatedSlot; copy.instantSlot=from.instantSlot; copy.animatedIcon=from.animatedIcon; copy.instantIcon=from.instantIcon;
            copy.overrides.putAll(from.overrides);
            for (Reward r:from.rewards.values()) copy.rewards.put(r.id,new Reward(r.id,r.item.clone(),r.weight));
            crates.put(id,copy); saveCrates(); msg(sender,"&aSkopiowano skrzynkę."); return true;
        }
        if (action.equals("coupon") && args.length>=2) {
            if (args[1].equals("create") && args.length>=5 && crates.containsKey(args[3])) {
                try { int uses=Integer.parseInt(args[4]); if (uses<1) throw new NumberFormatException();
                    coupons.put(args[2].toLowerCase(Locale.ROOT),args[3]); couponLimits.put(args[2].toLowerCase(Locale.ROOT),uses);
                    saveExtras(); msg(sender,"&aUtworzono kupon."); } catch (NumberFormatException e) { msg(sender,"&cNieprawidłowa liczba użyć."); }
                return true;
            }
        }
        if (action.equals("rewards") && args.length>=2 && sender instanceof Player p) {
            Crate c=crates.get(args[1]); if (c==null) msg(sender,"&cBrak skrzynki."); else rewardEditor(p,c,0);
            return true;
        }
        if (action.equals("chance") && args.length>=4) {
            Crate c=crates.get(args[1]); Reward r=c==null?null:c.rewards.get(args[2]);
            if (r==null) { msg(sender,"&cBrak nagrody."); return true; }
            try { double w=Double.parseDouble(args[3]); if (!Double.isFinite(w)||w<=0) throw new NumberFormatException();
                r.weight=w; saveCrates(); msg(sender,"&aZmieniono wagę losowania."); }
            catch (NumberFormatException ex) { msg(sender,"&cWaga musi być dodatnia."); }
            return true;
        }
        if (action.equals("gui") && args.length >= 3) {
            Crate c = crates.get(args[1]);
            if (c == null) { msg(sender, "&cNie ma skrzynki."); return true; }
            String field = args[2].toLowerCase(Locale.ROOT);
            if ((field.equals("animated-slot") || field.equals("instant-slot")) && args.length >= 4) {
                try {
                    int slot = Integer.parseInt(args[3]);
                    if (!validButtonSlot(slot) || (field.equals("animated-slot") ? c.instantSlot == slot : c.animatedSlot == slot))
                        throw new NumberFormatException();
                    if (field.equals("animated-slot")) c.animatedSlot = slot; else c.instantSlot = slot;
                    saveCrates(); msg(sender, "&aZapisano slot przycisku.");
                } catch (NumberFormatException ex) { msg(sender, "&cWybierz wolny slot z zakresu 45-53."); }
                return true;
            }
            if (field.equals("animated-icon") || field.equals("instant-icon")) {
                if (!(sender instanceof Player p) || p.getInventory().getItemInMainHand().getType().isAir()) {
                    msg(sender, "&cTrzymaj przedmiot w ręce."); return true;
                }
                if (field.equals("animated-icon")) c.animatedIcon = p.getInventory().getItemInMainHand().getType();
                else c.instantIcon = p.getInventory().getItemInMainHand().getType();
                saveCrates(); msg(sender, "&aZapisano ikonę przycisku."); return true;
            }
            msg(sender, "&c/case gui <id> animated-slot|instant-slot|animated-icon|instant-icon <wartość>"); return true;
        }
        if (action.equals("animation") && args.length>=3) {
            Crate c=crates.get(args[1]); String mode=args[2].toLowerCase(Locale.ROOT);
            if (c==null || !List.of("snake","roulette","mystery").contains(mode)) { msg(sender,"&c/case animation <id> snake|roulette|mystery"); return true; }
            c.animation=mode; saveCrates(); msg(sender,"&aUstawiono animację."); return true;
        }
        if (action.equals("backup")) {
            try {
                saveCrates(); saveExtras();
                File dir = new File(getDataFolder(), "backups");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create backup directory");
                String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"));
                Files.copy(cratesFile.toPath(), new File(dir, stamp+"-crates.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
                if (extrasFile.exists()) Files.copy(extrasFile.toPath(), new File(dir, stamp+"-extras.yml").toPath(), StandardCopyOption.REPLACE_EXISTING);
                try (Statement st = database.createStatement()) {
                    st.execute("VACUUM INTO '" + new File(dir, stamp+"-keys.db").getAbsolutePath().replace("'", "''") + "'");
                }
                msg(sender, "&aKopia zapisana w plugins/SkyCasesUltimate/backups/");
            } catch (Exception ex) { getLogger().severe("Backup error: "+ex.getMessage()); msg(sender,"&cNie udało się wykonać kopii."); }
            return true;
        }
        if (action.equals("reload")) { reloadConfig(); loadCrates(); loadExtras(); msg(sender, "&aPrzeładowano konfigurację."); return true; }
        if (action.equals("create") && args.length >= 2) {
            String id = args[1].toLowerCase(Locale.ROOT);
            if (!validId(id) || crates.containsKey(id)) { msg(sender, "&cID nieprawidłowe lub zajęte (a-z, 0-9, _ -)."); return true; }
            crates.put(id, new Crate(id, id)); saveCrates(); msg(sender, "&aUtworzono: " + id); return true;
        }
        if (action.equals("delete") && args.length >= 2) {
            if (crates.remove(args[1]) == null) { msg(sender, "&cBrak skrzynki."); return true; }
            placed.values().removeIf(id -> id.equals(args[1])); saveCrates(); msg(sender, "&aUsunięto."); return true;
        }
        if (action.equals("rename") && args.length >= 3) {
            Crate crate = crates.get(args[1]);
            if (crate == null) { msg(sender, "&cBrak skrzynki."); return true; }
            crate.name = String.join(" ", Arrays.copyOfRange(args, 2, args.length)); saveCrates(); msg(sender, "&aZmieniono nazwę."); return true;
        }
        if ((action.equals("set") || action.equals("unset")) && sender instanceof Player p) {
            Block target = p.getTargetBlockExact(6);
            if (target == null) { msg(sender, "&cSpójrz na blok z odległości do 6 kratek."); return true; }
            if (action.equals("unset")) { placed.remove(loc(target)); saveCrates(); msg(sender, "&aUsunięto lokalizację."); return true; }
            if (args.length < 2 || !crates.containsKey(args[1])) { msg(sender, "&cUżycie: /case set <id>"); return true; }
            placed.put(loc(target), args[1]); saveCrates(); msg(sender, "&aPrzypisano skrzynkę do bloku."); return true;
        }
        if (action.equals("reward") && args.length >= 5 && args[1].equalsIgnoreCase("add") && sender instanceof Player p) {
            Crate crate = crates.get(args[2]);
            if (crate == null || !validId(args[3])) { msg(sender, "&cNieprawidłowa skrzynka lub ID nagrody."); return true; }
            ItemStack item = p.getInventory().getItemInMainHand();
            if (item.getType().isAir()) { msg(sender, "&cTrzymaj nagrodę w ręce."); return true; }
            try {
                double weight = Double.parseDouble(args[4]);
                if (!(weight > 0) || !Double.isFinite(weight)) throw new NumberFormatException();
                crate.rewards.put(args[3], new Reward(args[3], item.clone(), weight));
                saveCrates(); msg(sender, "&aDodano nagrodę (item nie został zabrany).");
            } catch (NumberFormatException e) { msg(sender, "&cWaga musi być liczbą dodatnią."); }
            return true;
        }
        if (action.equals("reward") && args.length >= 4 && args[1].equalsIgnoreCase("remove")) {
            Crate crate = crates.get(args[2]);
            if (crate == null || crate.rewards.remove(args[3]) == null) { msg(sender, "&cNie znaleziono nagrody."); return true; }
            saveCrates(); msg(sender, "&aUsunięto nagrodę."); return true;
        }
        if (action.equals("givekey") && args.length >= 4) {
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null || !crates.containsKey(args[2])) { msg(sender, "&cGracz musi być online, a skrzynka istnieć."); return true; }
            try {
                int amount = Integer.parseInt(args[3]);
                if (amount <= 0 || amount > 1000000 || keys(target.getUniqueId(), args[2]) > Integer.MAX_VALUE - amount) throw new NumberFormatException();
                addKeys(target.getUniqueId(), args[2], amount); msg(sender, "&aDodano klucze.");
                msg(target, "&aOtrzymano &e" + amount + " &akluczy do &e" + args[2]);
            } catch (NumberFormatException e) { msg(sender, "&cNieprawidłowa ilość."); }
            catch (SQLException e) { msg(sender, "&cBłąd bazy danych."); }
            return true;
        }
        msg(sender, "&cNieznana komenda. /case help"); return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> actions = new ArrayList<>(List.of("help", "list", "preview", "keys", "daily", "redeem", "stats", "top", "claim"));
            if (sender.hasPermission("skycases.admin")) actions.addAll(List.of("admin", "edit", "create", "delete", "rename", "set", "unset", "reward", "givekey", "reload", "feature", "icon", "clone", "coupon", "rewards", "chance", "animation", "backup", "gui"));
            return actions.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
        }
        if (args.length == 2 && List.of("preview", "keys", "delete", "rename", "set", "edit", "icon", "clone", "feature", "rewards", "chance", "animation", "gui").contains(args[0].toLowerCase(Locale.ROOT)))
            return crates.keySet().stream().filter(s -> s.startsWith(args[1])).toList();
        if (args.length == 2 && args[0].equalsIgnoreCase("reward")) return List.of("add", "remove");
        if (args.length == 3 && args[0].equalsIgnoreCase("reward")) return new ArrayList<>(crates.keySet());
        return Collections.emptyList();
    }
}
