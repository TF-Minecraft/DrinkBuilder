package net.tfminecraft.drinkbuilder.pack;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.Logger;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.DownloadResult;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.PendingDrink;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.SimpleResult;

/**
 * Write potion PNG + ItemsAdder item entry under the realm drinks namespace.
 */
public final class IaDrinksWriter {

	private IaDrinksWriter() {}

	public static final class WriteResult {
		public final int cmd;
		public final String iaItemId;

		public WriteResult(int cmd, String iaItemId) {
			this.cmd = cmd;
			this.iaItemId = iaItemId;
		}
	}

	public static WriteResult write(
		JavaPlugin plugin,
		PendingDrink drink,
		CmdAllocator allocator,
		Logger log
	) throws IOException {
		if (drink == null || drink.id == null || drink.id.isBlank()) {
			throw new IOException("drink id required");
		}
		String sid = drink.id.trim();
		String textureId = drink.textureId == null ? "" : drink.textureId.trim();
		if (textureId.isEmpty()) {
			throw new IOException("texture_id required for IA write");
		}

		DownloadResult dl = ProvinceSystemClient.downloadSubmissionFile(sid, "texture.png");
		if (!dl.ok) {
			throw new IOException("download texture.png: " + dl.error);
		}

		String ns = DrinksNamespace.current();
		IaDrinksScaffold.ensure(plugin);
		File root = resolveDrinksRoot(plugin);
		File texDir = new File(root, "resourcepack/" + ns + "/textures/item");
		if (!texDir.exists() && !texDir.mkdirs()) {
			throw new IOException("could not create textures dir: " + texDir);
		}
		File png = new File(texDir, sid + ".png");
		Files.write(png.toPath(), dl.data);

		int cmd = allocator.allocate();
		String iaItemId = ns + ":" + sid;

		File itemsYml = new File(root, "configs/items.yml");
		FileConfiguration yaml = itemsYml.exists()
			? YamlConfiguration.loadConfiguration(itemsYml)
			: new YamlConfiguration();
		if (!yaml.isConfigurationSection("info")) {
			yaml.set("info.namespace", ns);
		}
		ConfigurationSection items = yaml.getConfigurationSection("items");
		if (items == null) {
			items = yaml.createSection("items");
		}
		String display = drink.displayName == null || drink.displayName.isBlank()
			? sid
			: drink.displayName.trim();
		ConfigurationSection item = items.createSection(sid);
		DrinkTextureItem.apply(item, display, "item/" + sid);
		yaml.save(itemsYml);
		DrinkTextureItem.pinPotionCmd(cmdCache(root).toPath(), iaItemId, cmd);

		SimpleResult assigned = ProvinceSystemClient.assignTextureCmd(textureId, cmd, iaItemId);
		if (!assigned.ok) {
			throw new IOException("assign CMD on PS: " + assigned.error);
		}

		if (log != null) {
			log.info("[ia] wrote " + iaItemId + " cmd=" + cmd + " png=" + png.getName());
		}
		return new WriteResult(cmd, iaItemId);
	}

	/** Resolve ItemsAdder contents folder for the current realm drinks namespace. */
	public static File resolveDrinksRoot(JavaPlugin plugin) {
		File configured = resolvePath(plugin, Cache.itemsAdderTfmcDrinks);
		String ns = DrinksNamespace.current();
		if (DrinksNamespace.MAIN.equals(ns)) {
			return configured;
		}
		File parent = configured.getParentFile();
		if (parent == null) {
			return new File(ns);
		}
		return new File(parent, ns);
	}

	/** ItemsAdder custom-model-data cache next to the contents folder. */
	static File cmdCache(File drinksRoot) {
		File contents = drinksRoot == null ? null : drinksRoot.getParentFile();
		File itemsAdder = contents == null ? null : contents.getParentFile();
		if (itemsAdder == null) {
			return new File("plugins/ItemsAdder/storage/items_ids_cache.yml");
		}
		return new File(itemsAdder, "storage/items_ids_cache.yml");
	}

	static File resolvePath(JavaPlugin plugin, String configured) {
		if (configured == null || configured.isBlank()) {
			configured = "plugins/ItemsAdder/contents/tfmc_drinks";
		}
		File asIs = new File(configured.trim());
		if (asIs.isAbsolute()) {
			return asIs;
		}
		File serverRoot = plugin.getDataFolder().getParentFile();
		if (serverRoot != null) {
			serverRoot = serverRoot.getParentFile();
		}
		if (serverRoot == null) {
			return asIs;
		}
		return new File(serverRoot, configured.trim());
	}
}
