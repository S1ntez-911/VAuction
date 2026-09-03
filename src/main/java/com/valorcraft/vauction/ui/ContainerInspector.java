package com.valorcraft.vauction.ui;

import com.valorcraft.vauction.config.AuctionConfig;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

public final class ContainerInspector {
    private ContainerInspector() {}

    public static List<ItemStack> entries(ItemStack container) {
        List<ItemStack> result = new ArrayList<>();
        IItemHandler items = container.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        if (items != null) {
            for (int slot = 0; slot < items.getSlots(); slot++) {
                ItemStack content = items.getStackInSlot(slot);
                if (!content.isEmpty()) result.add(content.copy());
            }
        }
        IFluidHandler fluids = container.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null);
        if (fluids != null) {
            for (int tank = 0; tank < fluids.getTanks(); tank++) {
                var fluid = fluids.getFluidInTank(tank);
                if (fluid.isEmpty()) continue;
                ItemStack icon = new ItemStack(MenuSupport.configured(AuctionConfig.FLUID_PREVIEW_ITEM, Items.BUCKET));
                icon.setHoverName(Component.literal(fluid.getDisplayName().getString()));
                icon.getOrCreateTag().putString("VAuctionFluidInfo",
                        fluid.getAmount() + " / " + fluids.getTankCapacity(tank) + " mB");
                result.add(icon);
            }
        }
        return result;
    }

    public static boolean hasContents(ItemStack stack) {
        return !entries(stack).isEmpty();
    }
}
