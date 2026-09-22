package net.tfminecraft.drinkbuilder.pack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.DrinkBuilder;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.AppliedResult;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.ListResult;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.PendingDrink;

/**
 * Pull approved / pending_pack drinks: write IA + Brewery recipe, reload, ack.
 */
public final class PackPullRunner {

	private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

	private PackPullRunner() {}

	public static boolean isRunning() {
		return RUNNING.get();
	}

	public static final class PullResult {
		public final boolean ok;
		public final boolean busy;
		public final int written;
		public final int failed;
		public final int ackNow;
		public final int queuedIa;
		public final String summary;

		private PullResult(
			boolean ok,
			boolean busy,
			int written,
			int failed,
			int ackNow,
			int queuedIa,
			String summary
		) {
			this.ok = ok;
			this.busy = busy;
			this.written = written;
			this.failed = failed;
			this.ackNow = ackNow;
			this.queuedIa = queuedIa;
			this.summary = summary;
		}

		public static PullResult skippedBusy() {
			return new PullResult(false, true, 0, 0, 0, 0, "pack pull already running");
		}

		public static PullResult of(
			int written,
			int failed,
			int ackNow,
			int queuedIa,
			String summary
		) {
			return new PullResult(true, false, written, failed, ackNow, queuedIa, summary);
		}
	}

	public static boolean run(boolean forceReload, Consumer<PullResult> onDone) {
		DrinkBuilder plugin = JavaPlugin.getPlugin(DrinkBuilder.class);
		if (!RUNNING.compareAndSet(false, true)) {
			plugin.getLogger().info("[pack] pull already running — skipped");
			if (onDone != null) {
				Bukkit.getScheduler().runTask(plugin, () ->
					onDone.accept(PullResult.skippedBusy())
				);
			}
			return false;
		}

		Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
			Logger log = plugin.getLogger();
			int written = 0;
			int failed = 0;
			List<String> ackImmediate = new ArrayList<>();
			List<String> needIa = new ArrayList<>();
			List<String> messages = new ArrayList<>();

			try {
				ListResult list = ProvinceSystemClient.listPendingApply();
				if (!list.ok) {
					messages.add("list failed: " + list.error);
					log.warning("[pack] " + messages.get(0));
					finish(plugin, forceReload, 0, 0, List.of(), List.of(), messages, onDone);
					return;
				}
				if (list.submissions.isEmpty()) {
					messages.add("no pending drinks");
					finish(plugin, forceReload, 0, 0, List.of(), List.of(), messages, onDone);
					return;
				}

				CmdAllocator allocator = plugin.getCmdAllocator();
				for (PendingDrink drink : list.submissions) {
					try {
						boolean wroteIa = applyDrink(plugin, drink, allocator, log);
						written++;
						if (wroteIa) {
							needIa.add(drink.id.trim());
						} else {
							ackImmediate.add(drink.id.trim());
						}
					} catch (Exception e) {
						failed++;
						String msg = drink.id + ": " + e.getMessage();
						messages.add(msg);
						log.warning("[pack] fail " + msg);
					}
				}
			} catch (Exception e) {
				failed++;
				messages.add(e.getMessage());
				log.warning("[pack] pull failed: " + e.getMessage());
			}

			finish(plugin, forceReload, written, failed, ackImmediate, needIa, messages, onDone);
		});
		return true;
	}

	static boolean applyDrink(
		JavaPlugin plugin,
		PendingDrink drink,
		CmdAllocator allocator,
		Logger log
	) throws Exception {
		Integer cmd = drink.existingCmd();
		boolean wroteIa = false;
		if (drink.needsIaWrite()) {
			IaDrinksWriter.WriteResult wr = IaDrinksWriter.write(
				plugin, drink, allocator, log
			);
			cmd = wr.cmd;
			wroteIa = true;
		}
		RecipesYmlMerger.merge(plugin, drink, cmd, log);
		return wroteIa;
	}

	private static void finish(
		DrinkBuilder plugin,
		boolean forceReload,
		int written,
		int failed,
		List<String> ackImmediate,
		List<String> needIa,
		List<String> messages,
		Consumer<PullResult> onDone
	) {
		Bukkit.getScheduler().runTask(plugin, () -> {
			try {
				if (written > 0) {
					boolean brewOk = Bukkit.dispatchCommand(
						Bukkit.getConsoleSender(),
						"brew reload"
					);
					if (!brewOk) {
						plugin.getLogger().warning("[pack] failed to dispatch brew reload");
					} else {
						plugin.getLogger().info("[pack] brew reload dispatched");
					}
				}

				if (!ackImmediate.isEmpty()) {
					final List<String> ackIds = List.copyOf(ackImmediate);
					Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
						AppliedResult result = ProvinceSystemClient.markApplied(ackIds);
						Bukkit.getScheduler().runTask(plugin, () -> {
							if (!result.ok) {
								plugin.getLogger().warning(
									"[pack] immediate applied ack failed: " + result.error
								);
							} else if (result.applied.size() < ackIds.size()) {
								plugin.getLogger().warning(
									"[pack] applied ack partial: requested="
										+ ackIds.size()
										+ " marked="
										+ result.applied.size()
										+ " ids="
										+ ackIds
										+ " applied="
										+ result.applied
								);
							} else {
								plugin.getLogger().info(
									"[pack] applied ack (no IA): " + result.applied.size()
								);
							}
						});
					});
				}

				DeferredDrinkIaReload reload = plugin.getDeferredIaReload();
				if (reload != null && !needIa.isEmpty()) {
					reload.queue().enqueue(needIa);
					reload.requestFlush(forceReload);
				}

				String summary = "written=" + written
					+ " failed=" + failed
					+ " ackNow=" + ackImmediate.size()
					+ " queuedIa=" + needIa.size();
				if (!messages.isEmpty()) {
					summary += " (" + String.join("; ", messages) + ")";
				}
				plugin.getLogger().info("[pack] " + summary);
				if (onDone != null) {
					onDone.accept(PullResult.of(
						written,
						failed,
						ackImmediate.size(),
						needIa.size(),
						summary
					));
				}
			} finally {
				RUNNING.set(false);
			}
		});
	}
}
