package com.valorcraft.vauction.item;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.List;

/** Read-only, bounded view of items and fluids stored inside an ItemStack. */
public final class StoredContents {
    private static final int MAX_ITEM_SLOTS = 4_096;
    private static final int MAX_FLUID_TANKS = 256;

    public record FluidEntry(int tank, FluidStack fluid, int capacity) {
        public FluidEntry {
            fluid = fluid == null ? FluidStack.EMPTY : fluid.copy();
            capacity = Math.max(0, capacity);
        }
    }

    public record Inspection(boolean readable, String error, List<ItemStack> slots,
                             List<FluidEntry> fluids, boolean itemHandler, boolean fluidHandler) {
        public Inspection {
            error = error == null ? "" : error;
            slots = copySlots(slots);
            fluids = fluids == null ? List.of() : List.copyOf(fluids);
        }

        public boolean hasItems() {
            return slots.stream().anyMatch(stack -> stack != null && !stack.isEmpty());
        }

        public boolean hasFluids() {
            return fluids.stream().anyMatch(entry -> !entry.fluid().isEmpty() && entry.fluid().getAmount() > 0);
        }

        public boolean hasContents() {
            return hasItems() || hasFluids();
        }

        public int occupiedItemSlots() {
            return (int) slots.stream().filter(stack -> stack != null && !stack.isEmpty()).count();
        }

        public long itemCount() {
            return slots.stream().filter(stack -> stack != null && !stack.isEmpty())
                    .mapToLong(ItemStack::getCount).sum();
        }
    }

    private StoredContents() {}

    public static Inspection inspect(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ok(List.of(), List.of(), false, false);
        List<ItemStack> nbtSlots;
        try {
            nbtSlots = vanillaNbtSlots(stack);
        } catch (Throwable error) {
            return failed(error);
        }
        try {
            IItemHandler itemHandler = stack.getCapability(ForgeCapabilities.ITEM_HANDLER).resolve().orElse(null);
            List<ItemStack> slots;
            boolean hasItemHandler = itemHandler != null;
            if (itemHandler != null) {
                int size = bounded(itemHandler.getSlots(), MAX_ITEM_SLOTS, "item slots");
                ArrayList<ItemStack> result = new ArrayList<>(size);
                for (int slot = 0; slot < size; slot++) {
                    ItemStack contained = itemHandler.getStackInSlot(slot);
                    result.add(contained == null ? ItemStack.EMPTY : contained.copy());
                }
                slots = result;
            } else {
                slots = nbtSlots;
            }

            IFluidHandlerItem fluidHandler = stack.getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM)
                    .resolve().orElse(null);
            ArrayList<FluidEntry> fluids = new ArrayList<>();
            boolean hasFluidHandler = fluidHandler != null;
            if (fluidHandler != null) {
                int tanks = bounded(fluidHandler.getTanks(), MAX_FLUID_TANKS, "fluid tanks");
                for (int tank = 0; tank < tanks; tank++) {
                    FluidStack fluid = fluidHandler.getFluidInTank(tank);
                    fluids.add(new FluidEntry(tank, fluid, fluidHandler.getTankCapacity(tank)));
                }
            }
            return ok(slots, fluids, hasItemHandler, hasFluidHandler);
        } catch (LinkageError forgeApiUnavailable) {
            // Unit environments and severely stripped servers may not bootstrap
            // Forge's capability registry. Exact NBT remains protected by the codec;
            // vanilla BlockEntityTag.Items can still be inspected safely.
            return ok(nbtSlots, List.of(), false, false);
        } catch (Throwable error) {
            return failed(error);
        }
    }

    /** Exact comparison used after ItemStack encode/decode before custody is accepted. */
    public static boolean same(Inspection before, Inspection after) {
        if (before == null || after == null || !before.readable() || !after.readable()) return false;
        if (before.itemHandler() != after.itemHandler() || before.fluidHandler() != after.fluidHandler()) return false;
        if (before.slots().size() != after.slots().size() || before.fluids().size() != after.fluids().size()) return false;
        for (int i = 0; i < before.slots().size(); i++) {
            if (!serialized(before.slots().get(i)).equals(serialized(after.slots().get(i)))) return false;
        }
        for (int i = 0; i < before.fluids().size(); i++) {
            FluidEntry left = before.fluids().get(i);
            FluidEntry right = after.fluids().get(i);
            if (left.tank() != right.tank() || left.capacity() != right.capacity()
                    || !fluidTag(left.fluid()).equals(fluidTag(right.fluid()))) return false;
        }
        return true;
    }

    private static Inspection ok(List<ItemStack> slots, List<FluidEntry> fluids,
                                 boolean itemHandler, boolean fluidHandler) {
        return new Inspection(true, "", slots, fluids, itemHandler, fluidHandler);
    }

    private static Inspection failed(Throwable error) {
        return new Inspection(false, error.getClass().getSimpleName() + ": "
                + (error.getMessage() == null ? "inspection failed" : error.getMessage()),
                List.of(), List.of(), false, false);
    }

    private static List<ItemStack> vanillaNbtSlots(ItemStack stack) {
        CompoundTag root = stack.getTag();
        if (root == null) return List.of();
        CompoundTag owner = root.contains("BlockEntityTag", Tag.TAG_COMPOUND)
                ? root.getCompound("BlockEntityTag") : root;
        if (!owner.contains("Items", Tag.TAG_LIST)) return List.of();
        ListTag items = owner.getList("Items", Tag.TAG_COMPOUND);
        int size = 0;
        for (int i = 0; i < items.size(); i++) {
            int slot = Byte.toUnsignedInt(items.getCompound(i).getByte("Slot"));
            size = Math.max(size, slot + 1);
        }
        // Vanilla block containers generally use 27 slots; retaining empty positions
        // makes the preview look like the original inventory.
        if (stack.getItem().getDescriptionId().contains("shulker_box")) size = Math.max(size, 27);
        size = bounded(size, MAX_ITEM_SLOTS, "NBT item slots");
        if (size == 0) return List.of();
        ArrayList<ItemStack> result = new ArrayList<>(java.util.Collections.nCopies(size, ItemStack.EMPTY));
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            int slot = Byte.toUnsignedInt(entry.getByte("Slot"));
            if (slot < size) result.set(slot, ItemStack.of(entry));
        }
        return result;
    }

    private static int bounded(int value, int maximum, String name) {
        if (value < 0 || value > maximum) throw new IllegalArgumentException(name + " outside 0.." + maximum);
        return value;
    }

    private static CompoundTag serialized(ItemStack stack) {
        return stack == null || stack.isEmpty() ? new CompoundTag() : stack.save(new CompoundTag());
    }

    private static CompoundTag fluidTag(FluidStack fluid) {
        return fluid == null || fluid.isEmpty() ? new CompoundTag() : fluid.writeToNBT(new CompoundTag());
    }

    private static List<ItemStack> copySlots(List<ItemStack> source) {
        if (source == null || source.isEmpty()) return List.of();
        ArrayList<ItemStack> result = new ArrayList<>(source.size());
        for (ItemStack stack : source) result.add(stack == null ? ItemStack.EMPTY : stack.copy());
        return List.copyOf(result);
    }
}
