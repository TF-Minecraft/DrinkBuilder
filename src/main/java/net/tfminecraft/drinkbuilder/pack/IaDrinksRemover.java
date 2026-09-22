package net.tfminecraft.drinkbuilder.pack;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Remove a realm drinks ItemsAdder item + PNG using {@code ia_item_id}.
 */
public final class IaDrinksRemover {

	private IaDrinksRemover() {}

	/**
	 * @param iaItemId e.g. {@code tfmc_drinks:player_drink_slug}
	 * @return true if an items.yml entry or PNG was removed
	 */
	public static boolean remove(JavaPlugin plugin, String iaItemId, Logger log)
		throws IOException {
		String raw = iaItemId == null ? "" : iaItemId.trim();
		if (raw.isEmpty()) {
			return false;
		}
		String stem = raw;
		String expectedNs = DrinksNamespace.current();
		int colon = raw.indexOf(':');
		if (colon >= 0) {
			String ns = raw.substring(0, colon).trim().toLowerCase(Locale.ROOT);
			stem = raw.substring(colon + 1).trim();
			if (!expectedNs.equals(ns)) {
				if (log != null) {
					log.warning("[ia] refusing to delete non-" + expectedNs + " item: " + raw);
				}
				return false;
			}
		}
		if (stem.isEmpty() || stem.contains("..") || stem.contains("/") || stem.contains("\\")) {
			throw new IOException("invalid ia_item_id stem: " + raw);
		}

		File root = IaDrinksWriter.resolveDrinksRoot(plugin);
		boolean changed = false;

		File itemsYml = new File(root, "configs/items.yml");
		if (itemsYml.isFile()) {
			FileConfiguration yaml = YamlConfiguration.loadConfiguration(itemsYml);
			ConfigurationSection items = yaml.getConfigurationSection("items");
			if (items != null && items.isConfigurationSection(stem)) {
				items.set(stem, null);
				yaml.save(itemsYml);
				changed = true;
				if (log != null) {
					log.info("[ia] removed items.yml entry " + stem);
				}
			}
		}

		File png = new File(
			root,
			"resourcepack/" + expectedNs + "/textures/item/" + stem + ".png"
		);
		if (png.isFile()) {
			if (!png.delete()) {
				throw new IOException("could not delete " + png.getAbsolutePath());
			}
			changed = true;
			if (log != null) {
				log.info("[ia] deleted texture " + png.getName());
			}
		}
		return changed;
	}
}
