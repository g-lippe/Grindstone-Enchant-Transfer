package grindstone_enchanttransfer;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.*;
import java.util.Map;

class EnchantmentUtil {
    public static Map.Entry<String, Integer>[] getEnchantmentsAsArray(ItemEnchantments enchants) {
        return enchants.entrySet().toArray(new Map.Entry[0]);
    }
}

public class GrindstoneInteractionHandler {

    private static final int XP_COST = 3; // Experience level cost
    private static final int COOLDOWN_TICKS = 25; // 1.5 seconds cooldown
    private static final boolean ALLOW_CURSE_TRANSFER = false;

    // Store the last tick a player successfully interacted
    private static final Map<UUID, Long> lastInteractionTickMap = new HashMap<>();

    // Listen for Right Click Block interactions with high priority
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlayerRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        InteractionHand hand = event.getHand();

        // 1 - Initial checks - Player must be Sneaking and Right-Clicking a Grindstone Block
        if (level.isClientSide() || !player.isShiftKeyDown() || level.getBlockState(pos).getBlock() != Blocks.GRINDSTONE || hand != InteractionHand.MAIN_HAND) { return; }

        long currentTick = level.getGameTime();
        UUID playerUUID = player.getUUID();
        long lastInteractionTick = lastInteractionTickMap.getOrDefault(playerUUID, 0L);
        if (currentTick < lastInteractionTick + COOLDOWN_TICKS) { return; }

        // Get main and offhand items
        ItemStack mainHandItem = player.getMainHandItem();
        ItemStack offHandItem = player.getOffhandItem();

        // Check if main hand item is enchanted, if offhand has at least one book, and if player can afford the XP cost
        if (mainHandItem.isEmpty() || !mainHandItem.has(DataComponents.ENCHANTMENTS) || mainHandItem.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty()) { return; }
        if (offHandItem.getItem() != Items.BOOK) { return; }
        if (!player.isCreative() && player.experienceLevel < XP_COST) { return; }


        // 2 - Enchantment Processing
        ItemEnchantments currentEnchantments = mainHandItem.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        Holder<Enchantment> enchantmentToTransfer = null;
        int levelToTransfer = 0;

        // Loop to find suitable enchantment using Tags (Ignore curses and get only enchants)
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : currentEnchantments.entrySet()) {
            Holder<Enchantment> currentEnchantmentHolder = entry.getKey();

            // Check if the enchantment Holder is tagged as a curse
            if (currentEnchantmentHolder.is(EnchantmentTags.CURSE) && !ALLOW_CURSE_TRANSFER) {
                continue; // Skip this curse enchantment
            }

            // Found a suitable enchantment (either not a curse, or curses are allowed)
            enchantmentToTransfer = currentEnchantmentHolder;
            levelToTransfer = entry.getIntValue();
            break; // Stop searching, we found one to transfer
        }

        // Post-Loop Check (Did we get any enchantments?)
        if (enchantmentToTransfer == null) {
            lastInteractionTickMap.put(playerUUID, currentTick);
            return;
        }

        //System.out.println("Transferring Enchantment: " + enchantmentToTransfer.getRegisteredName() + " Level: " + levelToTransfer);

        // 3 - Create an Enchanted Book for the Enchantment
        ItemStack enchantedBook = new ItemStack(Items.ENCHANTED_BOOK, 1);

        // Create an enchantments list with the selected enchantment, apply it to the book
        ItemEnchantments.Mutable bookEnchantBuilder = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        bookEnchantBuilder.set(enchantmentToTransfer, levelToTransfer);
        enchantedBook.set(DataComponents.STORED_ENCHANTMENTS, bookEnchantBuilder.toImmutable());

        // Create a new enchantments list, go through the original enchantments, and add all except the selected one
        ItemEnchantments.Mutable remainingEnchantmentsMutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        boolean skippedTransferred = false;
        Holder<Enchantment> transferredKey = enchantmentToTransfer;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : currentEnchantments.entrySet()) {
            if (!skippedTransferred && entry.getKey().equals(transferredKey)) {
                skippedTransferred = true;
                continue;
            }
            remainingEnchantmentsMutable.set(entry.getKey(), entry.getIntValue());
        }
        ItemEnchantments remainingEnchantments = remainingEnchantmentsMutable.toImmutable();

        // 4 - Final Result
        // Apply the updated enchantment list to the main hand item, thus removing the selected one from it
        mainHandItem.set(DataComponents.ENCHANTMENTS, remainingEnchantments);

        // Reset the repair cost of the item (as usually done by the grindstone)
        mainHandItem.remove(DataComponents.REPAIR_COST);

        // Consume XP cost, take away a normal book and add the Enchanted one
        if (!player.isCreative()) { player.giveExperienceLevels(-XP_COST); }
        offHandItem.shrink(1);
        if (!player.getInventory().add(enchantedBook)) {
            player.drop(enchantedBook, false);
            //player.displayClientMessage(Component.literal("Inventory full! Enchanted book dropped.").withStyle(net.minecraft.ChatFormatting.YELLOW), true);
        }

        // Audio feedback
        level.playSound(null, player.blockPosition(), SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, level.random.nextFloat() * 0.1F + 0.9F);

        // System.out.println("Enchantment transfer successful!");
        // player.displayClientMessage(Component.literal("Enchantment transferred!"), true);

        // Reset last interaction for the cooldown
        lastInteractionTickMap.put(playerUUID, currentTick);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }
}