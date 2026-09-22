package net.tfminecraft.drinkbuilder.pack;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

import dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent;

/**
 * Bridges ItemsAdder pack-compressed to DeferredDrinkIaReload when IA is present.
 */
public final class ItemsAdderPackListener implements Listener {

	private final DeferredDrinkIaReload reload;

	public ItemsAdderPackListener(DeferredDrinkIaReload reload) {
		this.reload = reload;
	}

	@EventHandler(priority = EventPriority.MONITOR)
	public void onPackCompressed(ItemsAdderPackCompressedEvent event) {
		reload.onPackCompressed();
	}

	public static void registerIfPresent(DeferredDrinkIaReload reload) {
		if (Bukkit.getPluginManager().getPlugin("ItemsAdder") == null) {
			return;
		}
		try {
			Class.forName("dev.lone.itemsadder.api.Events.ItemsAdderPackCompressedEvent");
			Bukkit.getPluginManager().registerEvents(
				new ItemsAdderPackListener(reload),
				reload.plugin()
			);
		} catch (ClassNotFoundException e) {
			// ItemsAdder API not on classpath at runtime
		}
	}
}
