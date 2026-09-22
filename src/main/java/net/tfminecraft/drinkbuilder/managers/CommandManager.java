package net.tfminecraft.drinkbuilder.managers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.DrinkBuilder;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.CatalogPushResult;
import net.tfminecraft.drinkbuilder.catalog.AssetSyncService;
import net.tfminecraft.drinkbuilder.catalog.CatalogSyncService;
import net.tfminecraft.drinkbuilder.pack.DeletableDrinkCache;
import net.tfminecraft.drinkbuilder.pack.DrinkDeleteRunner;
import net.tfminecraft.drinkbuilder.pack.PackPullRunner;
import net.tfminecraft.drinkbuilder.pack.PackReapplyRunner;
import net.tfminecraft.drinkbuilder.utils.Permissions;

public final class CommandManager implements CommandExecutor, TabCompleter {

	public final String cmd1 = "drinkbuilder";

	// Keep the existing legacy text representation, formatting, and exact-string comparisons.
	@SuppressWarnings("deprecation")
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if (!sender.hasPermission(Permissions.ADMIN)) {
			sender.sendMessage(ChatColor.RED + "You do not have permission to use /drinkbuilder.");
			return true;
		}
		if (args.length == 0) {
			sender.sendMessage(ChatColor.AQUA
				+ "Usage: /drinkbuilder reload|catalog sync|pack pull [force]|pack reapply <id>|drink delete <id>");
			return true;
		}

		String sub = args[0].trim().toLowerCase(Locale.ROOT);
		if ("reload".equals(sub)) {
			DrinkBuilder plugin = DrinkBuilder.plugin;
			if (plugin == null) {
				sender.sendMessage(ChatColor.RED + "Plugin not ready.");
				return true;
			}
			plugin.reloadAll();
			sender.sendMessage(ChatColor.GREEN + "DrinkBuilder reloaded ("
				+ Cache.ingredients.size() + " ingredients, "
				+ Cache.categories.size() + " categories, "
				+ Cache.permissionGroups.size() + " permission groups, "
				+ Cache.effectsBlacklist.size() + " blacklisted effects). "
				+ "Next CMD: " + plugin.getCmdAllocator().peekNext());
			CatalogSyncService.pushAsync(plugin);
			AssetSyncService.pushAsync(plugin);
			return true;
		}

		if ("catalog".equals(sub)) {
			if (args.length < 2 || !"sync".equalsIgnoreCase(args[1].trim())) {
				sender.sendMessage(ChatColor.AQUA + "Usage: /drinkbuilder catalog sync");
				return true;
			}
			DrinkBuilder plugin = DrinkBuilder.plugin;
			if (plugin == null) {
				sender.sendMessage(ChatColor.RED + "Plugin not ready.");
				return true;
			}
			sender.sendMessage(ChatColor.YELLOW + "Syncing drink catalog + assets…");
			CatalogSyncService.pushAsync(plugin, result -> {
				AssetSyncService.pushAsync(plugin);
				if (result.ok) {
					sender.sendMessage(ChatColor.GREEN + "Catalog synced: "
						+ result.ingredients + " ingredients. Assets sync started.");
				} else {
					sender.sendMessage(ChatColor.RED + "Catalog sync failed: "
						+ result.error);
				}
			});
			return true;
		}

		if ("pack".equals(sub)) {
			if (args.length < 2) {
				sender.sendMessage(ChatColor.AQUA
					+ "Usage: /drinkbuilder pack pull [force]|pack reapply <id>");
				return true;
			}
			String action = args[1].trim().toLowerCase(Locale.ROOT);
			if ("reapply".equals(action)) {
				if (args.length < 3 || args[2].trim().isEmpty()) {
					sender.sendMessage(ChatColor.AQUA + "Usage: /drinkbuilder pack reapply <id>");
					return true;
				}
				String id = args[2].trim();
				DrinkBuilder plugin = DrinkBuilder.plugin;
				if (plugin == null) {
					sender.sendMessage(ChatColor.RED + "Plugin not ready.");
					return true;
				}
				sender.sendMessage(ChatColor.YELLOW + "Reapplying drink " + id + "…");
				PackReapplyRunner.run(id, result -> sender.sendMessage(
					result.ok ? ChatColor.GREEN + result.message : ChatColor.RED + result.message
				));
				return true;
			}
			if (!"pull".equals(action)) {
				sender.sendMessage(ChatColor.AQUA
					+ "Usage: /drinkbuilder pack pull [force]|pack reapply <id>");
				return true;
			}
			boolean force = args.length >= 3 && "force".equalsIgnoreCase(args[2].trim());
			if (PackPullRunner.isRunning()) {
				sender.sendMessage(ChatColor.YELLOW + "Pack pull already running.");
				return true;
			}
			sender.sendMessage(ChatColor.YELLOW
				+ "Pulling pending drinks" + (force ? " (force IA reload)…" : "…"));
			boolean started = PackPullRunner.run(force, result -> {
				if (result.busy) {
					sender.sendMessage(ChatColor.YELLOW + result.summary);
					return;
				}
				sender.sendMessage(ChatColor.GREEN + "Pack pull done: " + result.summary);
			});
			if (!started) {
				sender.sendMessage(ChatColor.YELLOW + "Pack pull already running.");
			}
			return true;
		}

		if ("drink".equals(sub)) {
			if (args.length < 3 || !"delete".equalsIgnoreCase(args[1].trim())) {
				sender.sendMessage(ChatColor.AQUA + "Usage: /drinkbuilder drink delete <id>");
				return true;
			}
			String id = args[2].trim();
			DrinkBuilder plugin = DrinkBuilder.plugin;
			if (plugin == null) {
				sender.sendMessage(ChatColor.RED + "Plugin not ready.");
				return true;
			}
			sender.sendMessage(ChatColor.YELLOW + "Deleting drink " + id + "…");
			Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
				String result = DrinkDeleteRunner.run(id);
				Bukkit.getScheduler().runTask(plugin, () ->
					sender.sendMessage(ChatColor.GREEN + result)
				);
			});
			return true;
		}

		sender.sendMessage(ChatColor.AQUA
			+ "Usage: /drinkbuilder reload|catalog sync|pack pull [force]|pack reapply <id>|drink delete <id>");
		return true;
	}

	@Override
	public List<String> onTabComplete(
		CommandSender sender,
		Command command,
		String alias,
		String[] args
	) {
		if (!(sender instanceof Player) && !sender.hasPermission(Permissions.ADMIN)) {
			return Collections.emptyList();
		}
		if (!sender.hasPermission(Permissions.ADMIN)) {
			return Collections.emptyList();
		}
		if (args.length == 1) {
			String p = args[0].toLowerCase(Locale.ROOT);
			List<String> out = new ArrayList<>();
			if ("reload".startsWith(p)) {
				out.add("reload");
			}
			if ("catalog".startsWith(p)) {
				out.add("catalog");
			}
			if ("pack".startsWith(p)) {
				out.add("pack");
			}
			if ("drink".startsWith(p)) {
				out.add("drink");
			}
			return out;
		}
		if (args.length == 2 && "catalog".equalsIgnoreCase(args[0])) {
			String p = args[1].toLowerCase(Locale.ROOT);
			if ("sync".startsWith(p)) {
				return Collections.singletonList("sync");
			}
		}
		if (args.length == 2 && "pack".equalsIgnoreCase(args[0])) {
			String p = args[1].toLowerCase(Locale.ROOT);
			if ("pull".startsWith(p)) {
				return Collections.singletonList("pull");
			}
			if ("reapply".startsWith(p)) {
				return Collections.singletonList("reapply");
			}
		}
		if (args.length == 2 && "drink".equalsIgnoreCase(args[0])) {
			String p = args[1].toLowerCase(Locale.ROOT);
			if ("delete".startsWith(p)) {
				return Collections.singletonList("delete");
			}
		}
		if (args.length == 3
			&& "pack".equalsIgnoreCase(args[0])
			&& "pull".equalsIgnoreCase(args[1])) {
			String p = args[2].toLowerCase(Locale.ROOT);
			if ("force".startsWith(p)) {
				return Collections.singletonList("force");
			}
		}
		if (args.length == 3
			&& "pack".equalsIgnoreCase(args[0])
			&& "reapply".equalsIgnoreCase(args[1])) {
			String p = args[2].toLowerCase(Locale.ROOT);
			List<String> out = new ArrayList<>();
			for (String id : DeletableDrinkCache.snapshot()) {
				if (id != null && id.toLowerCase(Locale.ROOT).startsWith(p)) {
					out.add(id);
				}
			}
			return out;
		}
		if (args.length == 3
			&& "drink".equalsIgnoreCase(args[0])
			&& "delete".equalsIgnoreCase(args[1])) {
			String p = args[2].toLowerCase(Locale.ROOT);
			List<String> out = new ArrayList<>();
			for (String id : DeletableDrinkCache.snapshot()) {
				if (id != null && id.toLowerCase(Locale.ROOT).startsWith(p)) {
					out.add(id);
				}
			}
			return out;
		}
		return Collections.emptyList();
	}
}
