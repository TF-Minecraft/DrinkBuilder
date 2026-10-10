package net.tfminecraft.drinkbuilder.pack;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bukkit.configuration.InvalidConfigurationException;
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

	public static synchronized boolean hasPendingWrite(JavaPlugin plugin, String submissionId) {
		return plugin != null && submissionId != null
			&& submissionId.trim().matches("[A-Za-z0-9_-]+")
			&& Files.exists(journalPath(plugin, submissionId.trim()));
	}

	/** Cancel a reservation only after the remote API confirms its texture was freed. */
	public static synchronized boolean cancelPendingWrite(
		JavaPlugin plugin, String submissionId, CmdAllocator allocator, Logger log
	) throws IOException {
		if (submissionId == null || !submissionId.trim().matches("[A-Za-z0-9_-]+")) {
			throw new IOException("safe drink id required (letters, digits, underscore or hyphen)");
		}
		String sid = submissionId.trim();
		Path journal = journalPath(plugin, sid);
		if (!Files.exists(journal)) {
			return false;
		}
		YamlConfiguration pending = loadYaml(journal);
		String iaItemId = DrinksNamespace.current() + ":" + sid;
		if (!pending.isInt("cmd") || !iaItemId.equals(pending.getString("ia-item-id"))
			|| pending.getString("texture-id", "").isBlank()) {
			throw new IOException("invalid pending IA write for " + sid);
		}
		IaDrinksRemover.remove(plugin, iaItemId, log);
		deleteJournal(journal);
		allocator.free(pending.getInt("cmd"));
		return true;
	}

	public static synchronized WriteResult write(
		JavaPlugin plugin,
		PendingDrink drink,
		CmdAllocator allocator,
		Logger log
	) throws IOException {
		if (drink == null || drink.id == null || !drink.id.trim().matches("[A-Za-z0-9_-]+")) {
			throw new IOException("safe drink id required (letters, digits, underscore or hyphen)");
		}
		String sid = drink.id.trim();
		String textureId = drink.textureId == null ? "" : drink.textureId.trim();
		if (textureId.isEmpty()) {
			throw new IOException("texture_id required for IA write");
		}
		String ns = DrinksNamespace.current();
		String iaItemId = ns + ":" + sid;
		Path journal = journalPath(plugin, sid);
		Integer cmd = null;
		if (Files.exists(journal)) {
			YamlConfiguration pending = loadYaml(journal);
			if (!pending.isInt("cmd") || !textureId.equals(pending.getString("texture-id"))
				|| !iaItemId.equals(pending.getString("ia-item-id"))) {
				throw new IOException("pending IA write does not match drink " + sid);
			}
			cmd = pending.getInt("cmd");
			if (drink.existingCmd() != null && !cmd.equals(drink.existingCmd())) {
				throw new IOException("pending IA CMD conflicts with remote texture for " + sid);
			}
		}

		DownloadResult dl = ProvinceSystemClient.downloadSubmissionFile(sid, "texture.png");
		if (!dl.ok || dl.data == null || dl.data.length == 0) {
			throw new IOException("download texture.png: " + dl.error);
		}
		File root = resolveDrinksRoot(plugin);
		Path png = new File(root, "resourcepack/" + ns + "/textures/item/" + sid + ".png").toPath();
		Path itemsYml = new File(root, "configs/items.yml").toPath();
		Path cache = cmdCache(root).toPath();
		Map<Path, byte[]> snapshots = new LinkedHashMap<>();
		for (Path path : new Path[]{png, itemsYml, cache}) {
			snapshots.put(path, Files.exists(path) ? Files.readAllBytes(path) : null);
		}

		boolean allocated = false;
		if (cmd == null) {
			cmd = drink.existingCmd();
			if (cmd == null) {
				cmd = allocator.allocate();
				allocated = true;
			}
			try {
				saveJournal(journal, cmd, iaItemId, textureId);
			} catch (IOException | RuntimeException e) {
				if (allocated && !Files.exists(journal)) {
					allocator.free(cmd);
				}
				throw e;
			}
		}

		boolean assignmentAttempted = false;
		try {
			try {
				Files.createDirectories(png.getParent());
			} catch (IOException e) {
				throw new IOException("could not create textures dir: " + png.getParent(), e);
			}
			Files.createDirectories(itemsYml.getParent());
			Files.write(png, dl.data);
			FileConfiguration yaml = Files.exists(itemsYml) ? loadYaml(itemsYml) : new YamlConfiguration();
			if (!yaml.isConfigurationSection("info")) {
				yaml.set("info.namespace", ns);
			}
			ConfigurationSection items = yaml.getConfigurationSection("items");
			if (items == null) {
				items = yaml.createSection("items");
			}
			String display = drink.displayName == null || drink.displayName.isBlank()
				? sid : drink.displayName.trim();
			DrinkTextureItem.apply(items.createSection(sid), display, "item/" + sid);
			yaml.save(itemsYml.toFile());
			DrinkTextureItem.pinPotionCmd(cache, iaItemId, cmd);

			// The remote server may accept this assignment even if its response is lost.
			assignmentAttempted = true;
			SimpleResult assigned = ProvinceSystemClient.assignTextureCmd(textureId, cmd, iaItemId);
			if (!assigned.ok) {
				throw new IOException("assign CMD on PS: " + assigned.error);
			}
		} catch (IOException | RuntimeException e) {
			boolean restored = true;
			for (var entry : snapshots.entrySet()) {
				try {
					restoreSnapshot(entry.getKey(), entry.getValue());
				} catch (IOException | RuntimeException rollbackError) {
					restored = false;
					e.addSuppressed(rollbackError);
				}
			}
			if (allocated && !assignmentAttempted && restored) {
				try {
					deleteJournal(journal);
					allocator.free(cmd);
				} catch (IOException | RuntimeException cleanupError) {
					e.addSuppressed(cleanupError);
				}
			}
			throw e;
		}

		// Cleanup failure must never undo a successful remote assignment.
		try {
			deleteJournal(journal);
		} catch (IOException | RuntimeException e) {
			if (log != null) {
				log.warning("[ia] published " + iaItemId + " but could not remove pending write: " + e.getMessage());
			}
		}
		DrinkBottleModels.syncQuietly(plugin, log);
		if (log != null) {
			log.info("[ia] wrote " + iaItemId + " cmd=" + cmd + " png=" + png.getFileName());
		}
		return new WriteResult(cmd, iaItemId);
	}

	private static Path journalPath(JavaPlugin plugin, String sid) {
		return plugin.getDataFolder().toPath().resolve("pending-writes").resolve(sid + ".yml");
	}

	static YamlConfiguration loadYaml(Path path) throws IOException {
		YamlConfiguration yaml = new YamlConfiguration();
		try {
			yaml.load(path.toFile());
		} catch (InvalidConfigurationException e) {
			throw new IOException("invalid YAML: " + path, e);
		}
		return yaml;
	}

	private static void saveJournal(Path path, int cmd, String iaItemId, String textureId) throws IOException {
		Files.createDirectories(path.getParent());
		Path temp = Files.createTempFile(path.getParent(), "pending-", ".tmp");
		try {
			YamlConfiguration yaml = new YamlConfiguration();
			yaml.set("cmd", cmd);
			yaml.set("ia-item-id", iaItemId);
			yaml.set("texture-id", textureId);
			yaml.save(temp.toFile());
			try (var channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
				channel.force(true);
			}
			Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE);
			try (var directory = FileChannel.open(path.getParent(), StandardOpenOption.READ)) {
				directory.force(true);
			} catch (IOException ignored) {
				// Directory flushing is not supported on every filesystem/platform.
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	static void restoreSnapshot(Path path, byte[] data) throws IOException {
		if (data == null) {
			Files.deleteIfExists(path);
		} else {
			Files.write(path, data);
		}
	}

	static void deleteJournal(Path path) throws IOException {
		Files.deleteIfExists(path);
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
