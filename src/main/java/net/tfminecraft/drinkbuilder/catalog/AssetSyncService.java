package net.tfminecraft.drinkbuilder.catalog;

import java.io.File;
import java.nio.file.Files;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.DrinkBuilder;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.SimpleResult;

/**
 * Push potion preview assets (glass bottle + overlay) to ProvinceSystem.
 */
public final class AssetSyncService {

	public static final String OVERLAY = "potion_overlay.png";
	public static final String BOTTLE = "glass_bottle.png";

	private AssetSyncService() {}

	public static void pushAsync(JavaPlugin plugin) {
		if (plugin == null) {
			return;
		}
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			Logger log = plugin.getLogger();
			int ok = 0;
			for (String name : new String[] { OVERLAY, BOTTLE }) {
				SimpleResult result = pushOne(plugin, name);
				if (result.ok) {
					ok++;
				} else {
					log.warning("[assets] sync failed for " + name + ": " + result.error);
				}
			}
			if (ok == 2) {
				log.info("[assets] synced " + OVERLAY + " + " + BOTTLE + " to ProvinceSystem");
			} else if (ok > 0) {
				log.warning("[assets] synced " + ok + "/2 potion assets");
			}
		});
	}

	public static void pushAsyncFromPlugin() {
		DrinkBuilder plugin = DrinkBuilder.plugin;
		if (plugin != null) {
			pushAsync(plugin);
		}
	}

	private static SimpleResult pushOne(JavaPlugin plugin, String fileName) {
		File file = new File(new File(plugin.getDataFolder(), "assets"), fileName);
		if (!file.isFile()) {
			return SimpleResult.fail("missing " + file.getAbsolutePath());
		}
		try {
			byte[] bytes = Files.readAllBytes(file.toPath());
			if (bytes.length == 0) {
				return SimpleResult.fail("empty file");
			}
			if (bytes.length < 8
				|| (bytes[0] & 0xff) != 0x89
				|| bytes[1] != 0x50
				|| bytes[2] != 0x4e
				|| bytes[3] != 0x47) {
				return SimpleResult.fail("not a PNG");
			}
			return ProvinceSystemClient.putDrinkAsset(fileName, bytes);
		} catch (Exception e) {
			return SimpleResult.fail(e.getMessage());
		}
	}
}
