package net.tfminecraft.drinkbuilder.pack;

import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.drinkbuilder.Cache;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.AppliedResult;

/**
 * Defers ItemsAdder refresh until empty server (or force), then acks applied.
 * Uses ItemsAdderPackCompressedEvent when ItemsAdder is present; otherwise
 * acks after the iazip delay.
 */
public final class DeferredDrinkIaReload implements Listener {

	private final JavaPlugin plugin;
	private final PendingReloadQueue queue;
	private volatile boolean inFlight;
	private BukkitTask delayedZipTask;
	private BukkitTask fallbackAckTask;
	private final boolean itemsAdderPresent;

	public DeferredDrinkIaReload(JavaPlugin plugin, PendingReloadQueue queue) {
		this.plugin = plugin;
		this.queue = queue;
		this.itemsAdderPresent = Bukkit.getPluginManager().getPlugin("ItemsAdder") != null;
	}

	public JavaPlugin plugin() {
		return plugin;
	}

	public PendingReloadQueue queue() {
		return queue;
	}

	public void requestFlush(boolean force) {
		if (inFlight) {
			return;
		}
		Bukkit.getScheduler().runTask(plugin, () -> beginFlush(force));
	}

	private void beginFlush(boolean force) {
		if (inFlight) {
			return;
		}
		if (queue.isEmpty()) {
			return;
		}
		if (!force && !Bukkit.getOnlinePlayers().isEmpty()) {
			plugin.getLogger().info("[ia-reload] " + queue.size()
				+ " drink(s) pending IA refresh: waiting for empty server");
			return;
		}

		inFlight = true;
		Logger log = plugin.getLogger();
		int delaySec = Math.max(0, Cache.iaReloadDelaySeconds);
		log.info("[ia-reload] running iareload then iazip in " + delaySec
			+ "s for " + queue.size() + " drink(s) (force=" + force + ")");

		boolean reloadOk = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "iareload");
		if (!reloadOk) {
			log.warning("[ia-reload] failed to dispatch iareload: continuing to iazip");
		}

		if (delayedZipTask != null) {
			delayedZipTask.cancel();
			delayedZipTask = null;
		}
		if (fallbackAckTask != null) {
			fallbackAckTask.cancel();
			fallbackAckTask = null;
		}

		long delayTicks = delaySec * 20L;
		delayedZipTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
			delayedZipTask = null;
			boolean ok = Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "iazip");
			if (!ok) {
				inFlight = false;
				log.severe("[ia-reload] failed to dispatch iazip: will retry later");
				return;
			}
			if (!itemsAdderPresent) {
				// No pack-compressed event — ack shortly after iazip.
				fallbackAckTask = Bukkit.getScheduler().runTaskLater(plugin, () -> {
					fallbackAckTask = null;
					ackQueued("iazip-fallback");
				}, 40L);
			}
		}, delayTicks);
	}

	private void ackQueued(String reason) {
		if (!inFlight && !"iazip-fallback".equals(reason)) {
			return;
		}
		inFlight = false;
		List<String> ids = queue.snapshot();
		if (ids.isEmpty()) {
			return;
		}
		Logger log = plugin.getLogger();
		log.info("[ia-reload] acking " + ids.size() + " drink(s) (" + reason + ")");
		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			AppliedResult result = ProvinceSystemClient.markApplied(ids);
			Bukkit.getScheduler().runTask(plugin, () -> {
				if (!result.ok) {
					log.warning("[ia-reload] applied ack failed: " + result.error);
					return;
				}
				queue.clear(result.applied);
				log.info("[ia-reload] applied ack ok: " + result.applied.size()
					+ " id(s); remaining=" + queue.size());
			});
		});
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPlayerQuit(PlayerQuitEvent event) {
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!Bukkit.getOnlinePlayers().isEmpty()) {
				return;
			}
			plugin.getLogger().info("[pack] server empty: running drink pack pull");
			PackPullRunner.run(false, null);
		});
	}

	/**
	 * Called via reflection / optional listener when ItemsAdder is on the classpath.
	 */
	public void onPackCompressed() {
		if (!inFlight) {
			return;
		}
		ackQueued("pack-compressed");
	}
}
