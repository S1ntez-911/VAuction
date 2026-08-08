package com.valorcraft.vauction.item;

import com.valorcraft.vauction.config.AuctionSettings;
import com.valorcraft.vauction.config.ItemPolicyMode;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.Set;

/**
 * Политика приёма предметов на аукцион. Независима от команд и GUI —
 * в будущем её вызовет ListingService.
 * <p>
 * Проверки: стек не пустой; count &gt; 0; count не больше maxStackSize;
 * blacklist/whitelist (по registry-id и по item-темам); запрет контейнеров
 * с содержимым.
 */
public final class ItemPolicy {

    private static final Logger LOGGER = LogManager.getLogger("VAuction");
    private static final Set<String> WARNED_TAGS = new HashSet<>();

    public enum Failure {
        EMPTY_ITEM,
        COUNT_ZERO,
        COUNT_OVERSTACK,
        ITEM_BLOCKED,
        TAG_BLOCKED,
        ITEM_NOT_ALLOWED,
        CONTAINER_WITH_CONTENTS,
        CUSTOM_NBT_BLOCKED
    }

    public record PolicyResult(boolean allowed, Failure failure, String detail) {

        public static PolicyResult ok() {
            return new PolicyResult(true, null, null);
        }

        public static PolicyResult fail(Failure failure, String detail) {
            return new PolicyResult(false, failure, detail);
        }
    }

    private ItemPolicy() {}

    /** Проверка ItemStack перед принятием. Стек не модифицируется. */
    public static PolicyResult check(ItemStack stack, AuctionSettings settings) {
        if (stack == null || stack.isEmpty()) {
            return PolicyResult.fail(Failure.EMPTY_ITEM, "empty item");
        }
        if (stack.getCount() <= 0) {
            return PolicyResult.fail(Failure.COUNT_ZERO, "count <= 0");
        }
        if (stack.getCount() > stack.getMaxStackSize()) {
            return PolicyResult.fail(Failure.COUNT_OVERSTACK,
                    "count " + stack.getCount() + " > maxStackSize " + stack.getMaxStackSize());
        }

        String registryId = registryIdOf(stack);
        ItemPolicyMode mode = settings.itemPolicyMode();

        if (mode == ItemPolicyMode.BLACKLIST) {
            for (String blocked : settings.blockedItems()) {
                if (blocked.equalsIgnoreCase(registryId)) {
                    return PolicyResult.fail(Failure.ITEM_BLOCKED, "blocked id: " + registryId);
                }
            }
            for (String blockedTag : settings.blockedTags()) {
                if (matchesTag(stack, blockedTag)) {
                    return PolicyResult.fail(Failure.TAG_BLOCKED, "blocked tag: " + blockedTag);
                }
            }
        } else if (mode == ItemPolicyMode.WHITELIST) {
            boolean matched = false;
            for (String allowed : settings.whitelistedItems()) {
                if (allowed.equalsIgnoreCase(registryId)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                for (String allowedTag : settings.whitelistedTags()) {
                    if (matchesTag(stack, allowedTag)) {
                        matched = true;
                        break;
                    }
                }
            }
            if (!matched) {
                return PolicyResult.fail(Failure.ITEM_NOT_ALLOWED, "not whitelisted: " + registryId);
            }
        }

        if (!settings.allowContainersWithContents()
                && ContainerContentDetector.containsItemContents(stack)) {
            return PolicyResult.fail(Failure.CONTAINER_WITH_CONTENTS,
                    "item contains other items (allowContainersWithContents=false)");
        }
        if (settings.blockCustomNbt() && hasCustomNbt(stack, settings)) {
            return PolicyResult.fail(Failure.CUSTOM_NBT_BLOCKED,
                    "item carries custom NBT (blockCustomNbt=true)");
        }
        return PolicyResult.ok();
    }

    /**
     * NBT-политика: любой нестандартный NBT кроме повреждений ({@code Damage})
     * считается «начинкой» и запрещён; книги зачарований — отдельный выключатель.
     */
    private static boolean hasCustomNbt(ItemStack stack, AuctionSettings settings) {
        if (!stack.hasTag() || stack.getTag() == null) {
            return false;
        }
        CompoundTag tag = stack.getTag();
        if (tag.contains("BlockEntityTag")) {
            return true;
        }
        if (stack.is(Items.ENCHANTED_BOOK)) {
            return !settings.allowEnchantedBooks();
        }
        CompoundTag copy = tag.copy();
        copy.remove("Damage");
        return copy.size() > 0;
    }

    private static String registryIdOf(ItemStack stack) {
        ResourceLocation key = ForgeRegistries.ITEMS.getKey(stack.getItem());
        return key != null ? key.toString() : stack.getItem().getDescriptionId();
    }

    private static boolean matchesTag(ItemStack stack, String tagName) {
        ResourceLocation id = ResourceLocation.tryParse(tagName.trim());
        if (id == null) {
            if (WARNED_TAGS.add(tagName)) {
                LOGGER.warn("ItemPolicy: некорректный идентификатор тега в конфиге: '{}'", tagName);
            }
            return false;
        }
        return stack.is(TagKey.create(Registries.ITEM, id));
    }
}