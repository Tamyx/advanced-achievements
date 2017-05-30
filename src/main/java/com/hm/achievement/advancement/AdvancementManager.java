package com.hm.achievement.advancement;

import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.UnsafeValues;
import org.bukkit.advancement.Advancement;
import org.bukkit.inventory.ItemStack;

import com.hm.achievement.AdvancedAchievements;
import com.hm.achievement.advancement.AchievementAdvancement.AchievementAdvancementBuilder;
import com.hm.achievement.category.MultipleAchievements;
import com.hm.achievement.category.NormalAchievements;
import com.hm.mcshared.particle.ReflectionUtils.PackageType;

/**
 * Class in charge of registering achievements as advancements for servers running on Minecraft 1.12+.
 * 
 * @author Pyves
 */
@SuppressWarnings("deprecation")
public class AdvancementManager {

	private static final String ADVANCED_ACHIEVEMENTS_PARENT = "advanced_achievements_parent";
	// Pattern to produce keys for advancements.
	private static final Pattern REGEX_PATTERN_KEYS = Pattern.compile("[^A-Za-z0-9|_]");
	// Pattern to delete colours as the advancement interface does not support them.
	private static final Pattern REGEX_PATTERN_COLOURS = Pattern.compile("&([a-f]|[0-9]){1}");

	// Strings related to Reflection.
	private static final String PACKAGE_INVENTORY = "inventory";
	private static final String CLASS_CRAFT_ITEM_STACK = "CraftItemStack";
	private static final String CLASS_ITEM = "Item";
	private static final String CLASS_ITEM_STACK = "ItemStack";
	private static final String CLASS_REGISTRY_MATERIALS = "RegistryMaterials";
	private static final String CLASS_MINECRAFT_KEY = "MinecraftKey";
	private static final String FIELD_REGISTRY = "REGISTRY";
	private static final String METHOD_AS_NMS_COPY = "asNMSCopy";
	private static final String METHOD_GET_ITEM = "getItem";
	private static final String METHOD_GET_KEY = "getKey";
	private static final String METHOD_B = "b";

	private final AdvancedAchievements plugin;
	private final UnsafeValues unsafeValues;
	private final boolean configRegisterAdvancementDescriptions;

	private int generatedAdvancements;

	public AdvancementManager(AdvancedAchievements plugin, boolean configRegisterAdvancementDescriptions) {
		this.plugin = plugin;
		unsafeValues = Bukkit.getUnsafe();
		this.configRegisterAdvancementDescriptions = configRegisterAdvancementDescriptions;
		generatedAdvancements = 0;
	}

	public static String getKey(String achName) {
		return REGEX_PATTERN_KEYS.matcher(achName).replaceAll("").toLowerCase();
	}

	/**
	 * Registers all achievements as advancements.
	 */
	public void registerAdvancements() {
		cleanupOldAchievementAdvancements();
		registerParentAdvancement();
		registerOtherAdvancements();
	}

	/**
	 * Removes all advancements previously generated by the plugin.
	 */
	private void cleanupOldAchievementAdvancements() {
		int achievementsCleaned = 0;
		Iterator<Advancement> advancements = Bukkit.getServer().advancementIterator();
		while (advancements.hasNext()) {
			NamespacedKey namespacedKey = advancements.next().getKey();
			if ("advancedachievements".equals(namespacedKey.getNamespace())
					&& !ADVANCED_ACHIEVEMENTS_PARENT.equals(namespacedKey.getKey())) {
				++achievementsCleaned;
				unsafeValues.removeAdvancement(namespacedKey);
			}
		}
		Bukkit.getServer().reloadData();
		plugin.getLogger().info("Cleaned " + achievementsCleaned + " old advancements.");
	}

	/**
	 * Registers an "Advanced Achievements" advancement, which will be used as the parent of all advancements generated
	 * by Advanced Achievements.
	 */
	private void registerParentAdvancement() {
		AchievementAdvancementBuilder achievementAdvancementBuilder = new AchievementAdvancementBuilder()
				.iconItem("minecraft:" + getInternalName(new ItemStack(Material.BOOK, 1, (short) 0)))
				.iconData(Integer.toString(0)).title("Advanced Achievements").description("").parent(null);
		NamespacedKey namespacedKey = new NamespacedKey(plugin, ADVANCED_ACHIEVEMENTS_PARENT);
		if (Bukkit.getServer().getAdvancement(namespacedKey) == null) {
			unsafeValues.loadAdvancement(namespacedKey, achievementAdvancementBuilder.buildGoal().toJson());
		}
	}

	/**
	 * Registers all non parent advancements.
	 */
	private void registerOtherAdvancements() {
		if (!plugin.getDisabledCategorySet().contains("Commands")) {
			String parentKey = ADVANCED_ACHIEVEMENTS_PARENT;
			for (String ach : plugin.getPluginConfig().getConfigurationSection("Commands").getKeys(false)) {
				parentKey = registerAdvancement("Commands", "Commands." + ach, parentKey, true);
			}
		}

		for (NormalAchievements category : NormalAchievements.values()) {
			registerCategoryAdvancements(category.toString(), "");
		}

		for (MultipleAchievements category : MultipleAchievements.values()) {
			for (String section : plugin.getPluginConfig().getConfigurationSection(category.toString())
					.getKeys(false)) {
				registerCategoryAdvancements(category.toString(), "." + section);
			}
		}
		Bukkit.getServer().reloadData();
		plugin.getLogger().info("Generated " + generatedAdvancements + " new advancements.");
	}

	/**
	 * Registers all advancements for a given category or subcategory.
	 * 
	 * @param categoryName
	 * @param subcategory
	 */
	private void registerCategoryAdvancements(String categoryName, String subcategory) {
		if (plugin.getDisabledCategorySet().contains(categoryName)) {
			// Ignore this type.
			return;
		}

		Set<String> thresholds = plugin.getPluginConfig().getConfigurationSection(categoryName + subcategory)
				.getKeys(false);
		// Use priority queue so that advancements will be registered as a branch with increasing threshold values.
		Queue<Integer> orderedThresholds = new PriorityQueue<>(thresholds.size());
		for (String threshold : thresholds) {
			orderedThresholds.add(Integer.valueOf(threshold));
		}

		String parentKey = ADVANCED_ACHIEVEMENTS_PARENT;
		while (!orderedThresholds.isEmpty()) {
			parentKey = registerAdvancement(categoryName, categoryName + subcategory + "." + orderedThresholds.poll(),
					parentKey, orderedThresholds.isEmpty());
		}
	}

	/**
	 * Registers an individual advancement.
	 * 
	 * @param categoryName
	 * @param configAchievement
	 * @param parentKey
	 * @param lastAchievement
	 * @return the key of the registered achievement
	 */
	private String registerAdvancement(String categoryName, String configAchievement, String parentKey,
			boolean lastAchievement) {
		String achName = plugin.getPluginConfig().getString(configAchievement + ".Name", "");
		String achDisplayName = plugin.getPluginConfig().getString(configAchievement + ".DisplayName", "");
		if (StringUtils.isEmpty(achDisplayName)) {
			achDisplayName = achName;
		}
		// Strip colours as the advancements interface does not support them.
		achDisplayName = REGEX_PATTERN_COLOURS.matcher(achDisplayName).replaceAll("");

		String achKey = getKey(achName);
		NamespacedKey namespacedKey = new NamespacedKey(plugin, achKey);
		int metadata = plugin.getPluginGui().getInt(categoryName + ".Metadata", 0);
		String description = "";
		if (configRegisterAdvancementDescriptions) {
			// Give priority to the goal to stick with Vanilla naming of advancements.
			description = plugin.getPluginConfig().getString(configAchievement + ".Goal", "");
			if (!StringUtils.isNotBlank(description)) {
				description = plugin.getPluginConfig().getString(configAchievement + ".Message", "");
			}
			description = REGEX_PATTERN_COLOURS.matcher(description).replaceAll("");
		}

		AchievementAdvancementBuilder achievementAdvancementBuilder = new AchievementAdvancementBuilder()
				.iconItem("minecraft:" + getInternalName(new ItemStack(getMaterial(categoryName), 1, (short) metadata)))
				.iconData(Integer.toString(metadata)).title(achDisplayName).description(description).parent(parentKey);
		if (lastAchievement) {
			unsafeValues.loadAdvancement(namespacedKey, achievementAdvancementBuilder.buildChallenge().toJson());
		} else {
			unsafeValues.loadAdvancement(namespacedKey, achievementAdvancementBuilder.buildTask().toJson());
		}
		++generatedAdvancements;
		return achKey;
	}

	/**
	 * Retrieves the Material enum corresponding to a name specified in gui.yml.
	 * 
	 * @param categoryName
	 * @return
	 */
	private Material getMaterial(String categoryName) {
		Material material = Material
				.getMaterial(plugin.getPluginGui().getString(categoryName + ".Item", "bedrock").toUpperCase());
		if (material == null) {
			material = Material.BOOK;
			plugin.getLogger().warning("GUI material for category " + categoryName
					+ " was not found. Have you spelt the name correctly or is it available for your Minecraft version?");
		}
		return material;
	}

	/**
	 * Gets the internal item used by Vanilla Minecraft. These are the only names supported by advancements. Material
	 * and internal names can differ quite significantly (for instance: book_and_quill vs. writable_book).
	 * 
	 * @param item
	 * @return the internal Minecraft name
	 */
	private String getInternalName(ItemStack item) {
		try {
			Object nmsItemStack = PackageType.CRAFTBUKKIT.getClass(PACKAGE_INVENTORY + "." + CLASS_CRAFT_ITEM_STACK)
					.getMethod(METHOD_AS_NMS_COPY, ItemStack.class).invoke(null, item);
			Object nmsItem = PackageType.MINECRAFT_SERVER.getClass(CLASS_ITEM_STACK).getMethod(METHOD_GET_ITEM)
					.invoke(nmsItemStack);
			Object registry = PackageType.MINECRAFT_SERVER.getClass(CLASS_ITEM).getField(FIELD_REGISTRY).get(null);
			Object minecraftKey = PackageType.MINECRAFT_SERVER.getClass(CLASS_REGISTRY_MATERIALS)
					.getMethod(METHOD_B, Object.class).invoke(registry, nmsItem);
			return (String) PackageType.MINECRAFT_SERVER.getClass(CLASS_MINECRAFT_KEY).getMethod(METHOD_GET_KEY)
					.invoke(minecraftKey);
		} catch (Exception e) {
			plugin.getLogger().warning("Error getting internal item name for advancements. Registering as book.");
			return "book";
		}
	}
}
