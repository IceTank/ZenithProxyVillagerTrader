package dev.zenith.trader;

import com.zenith.mc.enchantment.EnchantmentData;
import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemRegistry;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class EnchantmentUtil {
    public static final HashMap<String, Integer> MAX_LEVEL_MAP = new HashMap<>();
    static {
        for(int i = 0; i < EnchantmentRegistry.REGISTRY.size(); i++) {
            EnchantmentData d = EnchantmentRegistry.REGISTRY.get(i);
            assert d != null;
            MAX_LEVEL_MAP.put(d.name(),d.maxLevel());
        }
    }

    public static int getEnchantmentLevel(ItemStack item, EnchantmentData enchantmentData) {
        return Optional.ofNullable(item)
                .map(ItemStack::getDataComponents)
                .map(dataComponents -> dataComponents.get(DataComponentTypes.ENCHANTMENTS))
                .map(ItemEnchantments::getEnchantments)
                .map(enchantments -> enchantments.get(enchantmentData.id()))
                .orElse(0);
    }


    public static Map<Integer, Integer> getAllEnchantments(ItemStack itemStack) {
        return Optional.ofNullable(itemStack.getDataComponents())
                .map(dataComponents -> dataComponents.get(DataComponentTypes.STORED_ENCHANTMENTS))
                .map(ItemEnchantments::getEnchantments)
                .orElse(new Int2IntArrayMap());
    }


    public static boolean isEnchantedBook(ItemStack itemStack) {
        return itemStack != null && itemStack.getId() == ItemRegistry.ENCHANTED_BOOK.id();
    }

    public static boolean isMaxLevel(String name, int level) {
        return MAX_LEVEL_MAP.get(name) == level;
    }

    public static Optional<Integer> getMaxLevel(String name) {
        return Optional.ofNullable(MAX_LEVEL_MAP.get(name));
    }

    public static Map<String, Integer> getEnchantmentMap(ItemStack itemStack) {
        return Optional.ofNullable(itemStack.getDataComponents())
                .map(components -> components.get(DataComponentTypes.STORED_ENCHANTMENTS))
                .map(ItemEnchantments::getEnchantments)
                .map(enchantments -> enchantments.int2IntEntrySet().stream()
                        .collect(Collectors.toMap(
                                entry -> Optional.ofNullable(EnchantmentRegistry.REGISTRY.get(entry.getIntKey()))
                                        .map(EnchantmentData::name)
                                        .orElse("unknown_" + entry.getIntKey()),
                                Int2IntMap.Entry::getIntValue,
                                (existing, replacement) -> replacement // 处理重复key的情况
                        )))
                .orElse(new HashMap<>());
    }

    // 使用Stream API的版本
    public static String mapToJsonStringStream(Map<String, Integer> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }

        return map.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}"));
    }
}
