package net.tfminecraft.drinkbuilder.pack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Persists drink submission ids waiting for ItemsAdder reload + applied ack.
 */
public final class PendingReloadQueue {

	private static final String FILE_NAME = "pending-reload.yml";
	private static final String KEY = "submission-ids";

	private final JavaPlugin plugin;
	private final LinkedHashSet<String> ids = new LinkedHashSet<>();
	private final Object lock = new Object();

	public PendingReloadQueue(JavaPlugin plugin) {
		this.plugin = plugin;
	}

	public void load() {
		synchronized (lock) {
			ids.clear();
			File file = file();
			if (!file.exists()) {
				return;
			}
			FileConfiguration config = YamlConfiguration.loadConfiguration(file);
			List<String> list = config.getStringList(KEY);
			for (String id : list) {
				if (id != null && !id.isBlank()) {
					ids.add(id.trim());
				}
			}
		}
	}

	public void enqueue(Collection<String> submissionIds) {
		if (submissionIds == null || submissionIds.isEmpty()) {
			return;
		}
		synchronized (lock) {
			boolean changed = false;
			for (String id : submissionIds) {
				if (id != null && !id.isBlank() && ids.add(id.trim())) {
					changed = true;
				}
			}
			if (changed) {
				saveUnlocked();
			}
		}
	}

	public void clear(Collection<String> submissionIds) {
		if (submissionIds == null || submissionIds.isEmpty()) {
			return;
		}
		synchronized (lock) {
			boolean changed = false;
			for (String id : submissionIds) {
				if (id != null && ids.remove(id.trim())) {
					changed = true;
				}
			}
			if (changed) {
				saveUnlocked();
			}
		}
	}

	public List<String> snapshot() {
		synchronized (lock) {
			return new ArrayList<>(ids);
		}
	}

	public boolean isEmpty() {
		synchronized (lock) {
			return ids.isEmpty();
		}
	}

	public int size() {
		synchronized (lock) {
			return ids.size();
		}
	}

	private File file() {
		return new File(plugin.getDataFolder(), FILE_NAME);
	}

	private void saveUnlocked() {
		FileConfiguration config = new YamlConfiguration();
		config.set(KEY, new ArrayList<>(ids));
		try {
			config.save(file());
		} catch (IOException e) {
			plugin.getLogger().warning(
				"[ia-reload] could not save pending-reload.yml: " + e.getMessage()
			);
		}
	}
}
