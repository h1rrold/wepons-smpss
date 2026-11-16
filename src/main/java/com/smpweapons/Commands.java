package com.smpweapons;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.server.ServerCommandEvent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** /trust /untrust (everyone) and every admin command. Also hooks "/give heavy hitter" and "/remove heavy hitter". */
public final class Commands implements TabExecutor, Listener {
    private static final Pattern GIVE_HH = Pattern.compile("^/?give\\s+heavy[ _]?hitter(?:\\s+(\\S+))?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern REMOVE_HH = Pattern.compile("^/?remove\\s+heavy[ _]?hitter\\s*$", Pattern.CASE_INSENSITIVE);

    private final WeaponsPlugin plugin;

    public Commands(WeaponsPlugin plugin) {
        this.plugin = plugin;
    }

    private OfflinePlayer resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer op = Bukkit.getOfflinePlayer(name);
        return (op.hasPlayedBefore() || op.isOnline()) ? op : null;
    }

    private boolean admin(CommandSender s) {
        if (s.hasPermission("weapons.admin")) return true;
        plugin.msg(s, "&cYou don't have permission to do that.");
        return false;
    }

    // ===================================================================== dispatch

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        switch (cmd.getName().toLowerCase(Locale.ROOT)) {
            case "trust":
                return trust(sender, args);
            case "untrust":
                return untrust(sender, args);
            case "start":
                return startEnd(sender, args, true);
            case "end":
                return startEnd(sender, args, false);
            case "reroll":
                return reroll(sender, args);
            case "upgrade":
                return upgrade(sender, args);
            case "weapon":
                return weapon(sender, args);
            case "heavyhitter":
                return heavy(sender, args);
            default:
                return false;
        }
    }

    // ===================================================================== trust (everyone)

    private boolean trust(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            plugin.msg(sender, "&cOnly players can use /trust.");
            return true;
        }
        Player p = (Player) sender;
        PlayerData pd = plugin.data.get(p.getUniqueId());
        if (args.length == 0) {
            plugin.msg(p, "&7Usage: &e/trust <player>&7, &e/untrust <player>&7, &e/trust list");
            return true;
        }
        if (args[0].equalsIgnoreCase("list")) {
            if (pd.trusted.isEmpty()) {
                plugin.msg(p, "&7You don't trust anyone yet. Use &e/trust <player>&7.");
                return true;
            }
            List<String> names = new ArrayList<>();
            for (UUID u : pd.trusted) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                String n = op.getName() != null ? op.getName() : u.toString().substring(0, 8);
                names.add((op.isOnline() ? "&a" : "&7") + n);
            }
            plugin.msg(p, "&7Trusted (&e" + names.size() + "&7): " + String.join("&8, ", names));
            return true;
        }
        OfflinePlayer t = resolve(args[0]);
        if (t == null) {
            plugin.msg(p, "&cI don't know a player called &e" + args[0] + "&c.");
            return true;
        }
        if (t.getUniqueId().equals(p.getUniqueId())) {
            plugin.msg(p, "&cYou already trust yourself.");
            return true;
        }
        if (!pd.trusted.add(t.getUniqueId())) {
            plugin.msg(p, "&e" + t.getName() + "&7 is already trusted.");
            return true;
        }
        plugin.data.save();
        plugin.msg(p, "&aYou now trust &e" + t.getName() + "&a \u2014 your abilities won't hurt them.");
        if (t.isOnline()) {
            plugin.msg(t.getPlayer(), "&e" + p.getName() + "&7 now trusts you.");
        }
        return true;
    }

    private boolean untrust(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            plugin.msg(sender, "&cOnly players can use /untrust.");
            return true;
        }
        Player p = (Player) sender;
        if (args.length == 0) {
            plugin.msg(p, "&7Usage: &e/untrust <player>");
            return true;
        }
        OfflinePlayer t = resolve(args[0]);
        if (t == null) {
            plugin.msg(p, "&cI don't know a player called &e" + args[0] + "&c.");
            return true;
        }
        PlayerData pd = plugin.data.get(p.getUniqueId());
        if (pd.trusted.remove(t.getUniqueId())) {
            plugin.data.save();
            plugin.msg(p, "&7You no longer trust &e" + t.getName() + "&7.");
        } else {
            plugin.msg(p, "&e" + t.getName() + "&7 wasn't on your trust list.");
        }
        return true;
    }

    // ===================================================================== /start smp  /end smp

    private boolean startEnd(CommandSender sender, String[] args, boolean start) {
        if (!admin(sender)) return true;
        if (args.length == 0 || !args[0].equalsIgnoreCase("smp")) {
            plugin.msg(sender, "&7Usage: &e/" + (start ? "start" : "end") + " smp");
            return true;
        }
        if (start == plugin.data.smpActive) {
            plugin.msg(sender, start ? "&eThe SMP is already running." : "&eThe SMP isn't running.");
            return true;
        }
        plugin.data.smpActive = start;
        plugin.data.save();
        if (start) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                plugin.weapons.ensureWeapon(p);
                p.showTitle(Title.title(Component.text("THE SMP HAS BEGUN", NamedTextColor.GOLD),
                        plugin.color("&7Right-click: ability 1 \u00b7 Sneak + right-click: ability 2 (Tier II)"),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3500), Duration.ofMillis(800))));
                Fx.sound(p.getLocation(), "upgrade", "entity.ender_dragon.growl", 1.5f, 1f);
            }
            Bukkit.broadcast(plugin.color("&8[&6Weapons&8] &6The SMP has started! &7Good luck."));
        } else {
            for (Player p : Bukkit.getOnlinePlayers()) {
                p.showTitle(Title.title(Component.text("THE SMP HAS ENDED", NamedTextColor.RED),
                        plugin.color("&7Abilities are now disabled"),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(3000), Duration.ofMillis(800))));
            }
            Bukkit.broadcast(plugin.color("&8[&6Weapons&8] &cThe SMP has ended. &7Abilities are disabled."));
        }
        return true;
    }

    // ===================================================================== /reroll  /upgrade

    private Player targetOrSelf(CommandSender sender, String[] args, int idx) {
        if (args.length > idx) {
            Player t = Bukkit.getPlayerExact(args[idx]);
            if (t == null) plugin.msg(sender, "&cThat player isn't online.");
            return t;
        }
        if (sender instanceof Player) return (Player) sender;
        plugin.msg(sender, "&cSpecify a player.");
        return null;
    }

    private boolean reroll(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        Player t = targetOrSelf(sender, args, 0);
        if (t == null) return true;
        Weapon nw = plugin.weapons.reroll(t);
        plugin.msg(sender, "&7Rerolled &e" + t.getName() + "&7 into the &e" + nw.display + "&7.");
        if (!t.equals(sender)) plugin.msg(t, "&dYour weapon was rerolled: &e" + nw.display);
        return true;
    }

    private boolean upgrade(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        Player t = targetOrSelf(sender, args, 0);
        if (t == null) return true;
        if (plugin.weapons.hasWeaponItem(t) || plugin.data.get(t.getUniqueId()).weapon != null) {
            plugin.weapons.setTier(t, 2, true);
            plugin.msg(sender, "&7Upgraded &e" + t.getName() + "&7 to &6Tier II&7.");
        } else {
            plugin.msg(sender, "&e" + t.getName() + "&7 has no weapon yet.");
        }
        return true;
    }

    // ===================================================================== /weapon (/wepon)

    private boolean weapon(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (args.length == 0) {
            plugin.msg(sender, "&7/weapon give <player> <weapon> &8| &7remove <player> &8| &7tier <player> <1|2> &8| &7list &8| &7info [player]");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "give": {
                if (args.length < 3) {
                    plugin.msg(sender, "&7Usage: &e/weapon give <player> <weapon>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    plugin.msg(sender, "&cThat player isn't online.");
                    return true;
                }
                Weapon w = Weapon.fromId(String.join("_", java.util.Arrays.copyOfRange(args, 2, args.length)));
                if (w == null) {
                    plugin.msg(sender, "&cUnknown weapon. Try &e/weapon list&c.");
                    return true;
                }
                if (w == Weapon.HEAVY) {
                    plugin.weapons.giveHeavyHitter(t);
                } else {
                    plugin.weapons.giveWeapon(t, w, true);
                }
                plugin.msg(sender, "&7Gave &e" + w.display + "&7 to &e" + t.getName() + "&7.");
                return true;
            }
            case "remove": {
                if (args.length < 2) {
                    plugin.msg(sender, "&7Usage: &e/weapon remove <player>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    plugin.msg(sender, "&cThat player isn't online.");
                    return true;
                }
                plugin.weapons.removeWeapons(t, false, true);
                plugin.data.get(t.getUniqueId()).weapon = null;
                plugin.data.save();
                plugin.msg(sender, "&7Removed &e" + t.getName() + "&7's weapon (they roll a new one next time they join, or use /reroll).");
                return true;
            }
            case "tier": {
                if (args.length < 3) {
                    plugin.msg(sender, "&7Usage: &e/weapon tier <player> <1|2>");
                    return true;
                }
                Player t = Bukkit.getPlayerExact(args[1]);
                if (t == null) {
                    plugin.msg(sender, "&cThat player isn't online.");
                    return true;
                }
                int tier = args[2].equals("2") ? 2 : 1;
                plugin.weapons.setTier(t, tier, tier == 2);
                plugin.msg(sender, "&7Set &e" + t.getName() + "&7 to Tier &e" + tier + "&7.");
                return true;
            }
            case "dragon": {
                if (!(sender instanceof Player)) {
                    plugin.msg(sender, "&cRun this in game.");
                    return true;
                }
                plugin.abilities.testDragon((Player) sender);
                plugin.msg(sender, "&7Mini dragon test flight. If it flies backwards, set &emini-dragon-yaw-offset: 180&7 in the config.");
                return true;
            }
            case "list": {
                StringBuilder sb = new StringBuilder("&7Weapons: ");
                for (Weapon w : Weapon.values()) sb.append("&e").append(w.id).append("&8, ");
                plugin.msg(sender, sb.substring(0, sb.length() - 2));
                return true;
            }
            case "info": {
                Player t = targetOrSelf(sender, args, 1);
                if (t == null) return true;
                PlayerData pd = plugin.data.get(t.getUniqueId());
                plugin.msg(sender, "&e" + t.getName() + "&7: " + (pd.weapon == null ? "no weapon" : pd.weapon.display)
                        + " &8| &7Tier &e" + pd.tier + " &8| &7kills &e" + pd.kills + " &8| &7trusts &e" + pd.trusted.size()
                        + (t.getUniqueId().equals(plugin.data.heavyHolder) ? " &8| &6Heavy Hitter holder" : ""));
                return true;
            }
            default:
                plugin.msg(sender, "&7/weapon give <player> <weapon> | remove | tier | list | info");
                return true;
        }
    }

    // ===================================================================== heavy hitter

    private boolean heavy(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give":
                giveHeavy(sender, args.length > 1 ? args[1] : null);
                return true;
            case "remove":
                removeHeavy(sender);
                return true;
            case "where": {
                UUID h = plugin.data.heavyHolder;
                if (h == null) plugin.msg(sender, "&7Nobody holds the Heavy Hitter.");
                else {
                    OfflinePlayer op = Bukkit.getOfflinePlayer(h);
                    plugin.msg(sender, "&7The Heavy Hitter belongs to &e" + op.getName() + (op.isOnline() ? " &a(online)" : " &c(offline)"));
                }
                return true;
            }
            default:
                plugin.msg(sender, "&7/heavyhitter give [player] | remove | where");
                return true;
        }
    }

    private void giveHeavy(CommandSender sender, String targetName) {
        Player t;
        if (targetName != null) {
            t = Bukkit.getPlayerExact(targetName);
            if (t == null) {
                plugin.msg(sender, "&cThat player isn't online.");
                return;
            }
        } else if (sender instanceof Player) {
            t = (Player) sender;
        } else {
            plugin.msg(sender, "&cSpecify a player.");
            return;
        }
        plugin.weapons.giveHeavyHitter(t);
        plugin.msg(sender, "&7The &6Heavy Hitter&7 now belongs to &e" + t.getName() + "&7.");
        Bukkit.broadcast(plugin.color("&8[&6Weapons&8] &e" + t.getName() + "&7 now wields the &6Heavy Hitter&7."));
    }

    private void removeHeavy(CommandSender sender) {
        plugin.weapons.removeHeavyHitter();
        plugin.msg(sender, "&7The Heavy Hitter has been removed from the world.");
    }

    // ===================================================================== "/give heavy hitter" hooks

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerCommand(PlayerCommandPreprocessEvent e) {
        String msg = e.getMessage().trim();
        Matcher g = GIVE_HH.matcher(msg);
        if (g.matches()) {
            if (!e.getPlayer().hasPermission("weapons.admin")) return;
            e.setCancelled(true);
            giveHeavy(e.getPlayer(), g.group(1));
            return;
        }
        if (REMOVE_HH.matcher(msg).matches()) {
            if (!e.getPlayer().hasPermission("weapons.admin")) return;
            e.setCancelled(true);
            removeHeavy(e.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsoleCommand(ServerCommandEvent e) {
        String msg = e.getCommand().trim();
        Matcher g = GIVE_HH.matcher(msg);
        if (g.matches()) {
            e.setCancelled(true);
            giveHeavy(e.getSender(), g.group(1));
            return;
        }
        if (REMOVE_HH.matcher(msg).matches()) {
            e.setCancelled(true);
            removeHeavy(e.getSender());
        }
    }

    // ===================================================================== tab complete

    private static List<String> filter(List<String> options, String prefix) {
        List<String> out = new ArrayList<>();
        for (String o : options) if (o.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) out.add(o);
        return out;
    }

    private static List<String> online() {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) names.add(p.getName());
        return names;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] args) {
        String name = cmd.getName().toLowerCase(Locale.ROOT);
        List<String> empty = new ArrayList<>();
        switch (name) {
            case "trust": {
                if (args.length != 1) return empty;
                List<String> o = online();
                o.add(0, "list");
                return filter(o, args[0]);
            }
            case "untrust":
                return args.length == 1 ? filter(online(), args[0]) : empty;
            case "start":
            case "end":
                return args.length == 1 ? filter(List.of("smp"), args[0]) : empty;
            case "reroll":
            case "upgrade":
                return args.length == 1 ? filter(online(), args[0]) : empty;
            case "heavyhitter":
                if (args.length == 1) return filter(List.of("give", "remove", "where"), args[0]);
                if (args.length == 2 && args[0].equalsIgnoreCase("give")) return filter(online(), args[1]);
                return empty;
            case "weapon": {
                if (args.length == 1) return filter(List.of("give", "remove", "tier", "list", "info", "dragon"), args[0]);
                if (args.length == 2) return filter(online(), args[1]);
                if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
                    List<String> ids = new ArrayList<>();
                    for (Weapon w : Weapon.values()) ids.add(w.id);
                    return filter(ids, args[2]);
                }
                if (args.length == 3 && args[0].equalsIgnoreCase("tier")) return filter(List.of("1", "2"), args[2]);
                return empty;
            }
            default:
                return empty;
        }
    }
}
