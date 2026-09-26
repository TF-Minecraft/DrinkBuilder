package net.tfminecraft.drinkbuilder.pack;

import java.util.List;
import java.util.logging.Logger;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import net.tfminecraft.drinkbuilder.DrinkBuilder;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.DrinkGetResult;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.PendingDrink;
import net.tfminecraft.drinkbuilder.api.ProvinceSystemClient.RevokeResult;

/**
 * Staff drink delete: remove Brewery recipe, revoke on PS, free IA if last ref.
 */
public final class DrinkDeleteRunner {

	private DrinkDeleteRunner() {}

	public static String run(String submissionId) {
		DrinkBuilder plugin = JavaPlugin.getPlugin(DrinkBuilder.class);
		Logger log = plugin.getLogger();
		String id = submissionId == null ? "" : submissionId.trim();
		if (id.isEmpty()) {
			return "Drink id is required.";
		}

		DrinkGetResult fetched = ProvinceSystemClient.getDrink(id);
		if (!fetched.ok || fetched.drink == null) {
			return fetched.error != null ? fetched.error : "Could not load drink.";
		}
		PendingDrink drink = fetched.drink;
		String status = drink.status == null ? "" : drink.status.trim().toLowerCase();
		if (!status.equals("approved")
			&& !status.equals("pending_pack")
			&& !status.equals("applied")) {
			return "Drink " + id + " is not deletable (status=" + drink.status + ").";
		}

		try {
			RecipesYmlMerger.remove(plugin, id, log);
		} catch (Exception e) {
			log.warning("[drink-delete] recipe remove failed: " + e.getMessage());
			return "Could not delete drink " + id + ": recipe cleanup failed: "
				+ e.getMessage() + ". Website record retained.";
		}

		// run() is called by an asynchronous command task; Bukkit commands belong on the server thread.
		Bukkit.getScheduler().runTask(plugin, () -> {
			if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "brew reload")) {
				log.warning("[drink-delete] failed to dispatch brew reload");
			}
		});

		RevokeResult revoked = ProvinceSystemClient.revokeDrink(id);
		if (!revoked.ok) {
			return "Local recipe cleanup done but API revoke failed: "
				+ (revoked.error != null ? revoked.error : "unknown");
		}

		DeletableDrinkCache.invalidate();
		DeferredDrinkIaReload reload = plugin.getDeferredIaReload();
		if (reload != null) {
			// Remove the revoked submission from future queued acknowledgements.
			reload.queue().clear(List.of(id));
		}

		boolean iaChanged = false;
		if (revoked.textureFreed) {
			try {
				synchronized (IaDrinksWriter.class) {
					if (revoked.iaItemId != null && !revoked.iaItemId.isBlank()) {
						iaChanged = IaDrinksRemover.remove(plugin, revoked.iaItemId, log);
					} else if (revoked.cmd != null) {
						throw new IllegalStateException("missing ItemsAdder item id for CMD " + revoked.cmd);
					}
					iaChanged = IaDrinksWriter.cancelPendingWrite(plugin, id, plugin.getCmdAllocator(), log) || iaChanged;
					if (revoked.cmd != null) {
						plugin.getCmdAllocator().free(revoked.cmd);
					}
				}
			} catch (Exception e) {
				log.warning("[drink-delete] IA remove failed; CMD retained: " + e.getMessage());
				// Removal can fail after changing items.yml. Refresh that partial cleanup too.
				if (reload != null) {
					reload.requestRefresh();
				}
				return "Drink " + id + " revoked, but IA cleanup failed: " + e.getMessage()
					+ ". CMD retained; manual cleanup required.";
			}
		}

		if (iaChanged && reload != null) {
			reload.requestRefresh();
		}

		String label = drink.displayName != null && !drink.displayName.isBlank()
			? drink.displayName
			: id;
		String extra = revoked.textureFreed
			? " Texture + CMD freed."
			: (drink.textureId != null && !drink.textureId.isBlank()
				? " Shared texture kept."
				: "");
		return "Deleted drink " + id + " (" + label + ")." + extra;
	}
}
