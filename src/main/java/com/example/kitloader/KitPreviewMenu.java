package com.example.kitloader;

import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * Read-only chest menu shown by /kit preview. Displays the kit's 41 items
 * (main 0-35, armor 36-39, offhand 40) in a vanilla chest screen; the client
 * renders the screen, so no custom rendering code is needed.
 *
 * The menu is deliberately locked down so a preview can never modify a kit:
 * every slot is backed by a container whose mutators do nothing, shift-click
 * is disabled via quickMoveStack, dragging is blocked via canDragTo, and the
 * slots refuse placement and pickup. The player can look but cannot take,
 * place, rearrange or otherwise change the displayed items.
 *
 * clicked() is intentionally NOT overridden here: its signature differs
 * between supported Minecraft versions (ClickType vs ContainerInput), and
 * the read-only container already neutralizes every default click path.
 */
public class KitPreviewMenu extends AbstractContainerMenu {
    private static final int ROWS = 5; // 9x5 = 45 slots, enough for the 41 kit slots
    private static final int SIZE = ROWS * 9;

    public KitPreviewMenu(int containerId, Inventory playerInventory, List<ItemStack> items) {
        super(MenuType.GENERIC_9x5, containerId);
        ItemStack[] stacks = new ItemStack[SIZE];
        for (int i = 0; i < SIZE; i++) {
            stacks[i] = i < items.size() ? items.get(i) : ItemStack.EMPTY;
        }
        Container preview = new ReadOnlyContainer(stacks);
        for (int row = 0; row < ROWS; row++) {
            for (int col = 0; col < 9; col++) {
                int index = col + row * 9;
                this.addSlot(new PreviewSlot(preview, index, 8 + col * 18, 18 + row * 18));
            }
        }
        // The client builds its own ChestMenu from the GENERIC_9x5 type, which
        // always contains the player's 36 slots below the chest. Mirror those
        // slots (bound to the real inventory) so every slot id sent by the
        // client maps to a server slot. quickMoveStack blocks shift-click
        // everywhere, so preview items can never move into the player's
        // inventory (or vice versa).
        int playerSlotsTop = 103 + (ROWS - 4) * 18;
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, playerSlotsTop + row * 18));
            }
        }
        int hotbarTop = 161 + (ROWS - 4) * 18;
        for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col, 8 + col * 18, hotbarTop));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY; // no shift-click movement in a preview
    }

    @Override
    public boolean stillValid(Player player) {
        return true; // the preview stays open; Esc/E closes it
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return false; // no dragging preview items around
    }

    /** A slot that refuses placement and pickup. */
    private static final class PreviewSlot extends Slot {
        PreviewSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }
    }

    /** A container whose every mutator is a no-op: the preview is strictly read-only. */
    private static final class ReadOnlyContainer extends SimpleContainer {
        ReadOnlyContainer(ItemStack... items) {
            super(items);
        }

        @Override
        public ItemStack removeItem(int index, int count) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeItemType(Item item, int count) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack addItem(ItemStack stack) {
            return stack; // nothing can be added
        }

        @Override
        public List<ItemStack> removeAllItems() {
            return List.of();
        }

        @Override
        public void setItem(int index, ItemStack stack) {
        }

        @Override
        public void clearContent() {
        }
    }
}
