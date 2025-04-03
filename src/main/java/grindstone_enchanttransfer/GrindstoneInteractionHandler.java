package grindstone_enchanttransfer;
// GrindstoneEnchantTransfer

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer; // Use ServerPlayer for experience
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance; // For adding to book
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.EventPriority;
import net.minecraft.tags.EnchantmentTags;

import java.util.Iterator;
import java.util.Set;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.enchantment.Enchantment;

import java.util.*;

import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;

import java.util.Map;

class EnchantmentUtil {
    public static Map.Entry<String, Integer>[] getEnchantmentsAsArray(ItemEnchantments enchants) {
        return enchants.entrySet().toArray(new Map.Entry[0]);
    }
}

public class GrindstoneInteractionHandler {

    private static final int XP_COST = 4; // Experience level cost
    private static final int COOLDOWN_TICKS = 25; // 1.5 seconds cooldown
    private static final boolean ALLOW_CURSE_TRANSFER = false;

    // ****** ADDITION 1: Cooldown Implementation ******
    // Store the last tick a player successfully interacted
    private static final Map<UUID, Long> lastInteractionTickMap = new HashMap<>();
    // Cooldown duration in game ticks (20 ticks = 1 second)
    // ****** END ADDITION 1 ******

    // Listen for Right Click Block interactions with high priority
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlayerRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        InteractionHand hand = event.getHand();

        // ... (Initial checks)
        if (level.isClientSide() || !player.isShiftKeyDown() || level.getBlockState(pos).getBlock() != Blocks.GRINDSTONE || hand != InteractionHand.MAIN_HAND) { return; }
        long currentTick = level.getGameTime();
        UUID playerUUID = player.getUUID();
        long lastInteractionTick = lastInteractionTickMap.getOrDefault(playerUUID, 0L);
        if (currentTick < lastInteractionTick + COOLDOWN_TICKS) { return; }
        ItemStack mainHandItem = player.getMainHandItem();
        ItemStack offHandItem = player.getOffhandItem();
        if (mainHandItem.isEmpty() || !mainHandItem.has(DataComponents.ENCHANTMENTS) || mainHandItem.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY).isEmpty()) { return; }
        if (offHandItem.getItem() != Items.BOOK) { return; }
        if (!player.isCreative() && player.experienceLevel < XP_COST) { player.displayClientMessage(Component.translatable("container.repair.insufficient_xp_level", XP_COST), true); return; }
        // ... (End checks) ...


        // --- Enchantment Processing ---
        ItemEnchantments currentEnchantments = mainHandItem.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);
        Holder<Enchantment> enchantmentToTransfer = null;
        int levelToTransfer = 0;

        // 2: Loop to find suitable enchantment using Tags
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : currentEnchantments.entrySet()) {
            Holder<Enchantment> currentEnchantmentHolder = entry.getKey();

            // Check if the enchantment Holder is tagged as a curse
            // Use the Holder's 'is' method with the correct EnchantmentTag
            if (currentEnchantmentHolder.is(EnchantmentTags.CURSE) && !ALLOW_CURSE_TRANSFER) {
                System.out.println("Skipping curse (tag check): " + currentEnchantmentHolder.getRegisteredName()); // Optional debug
                continue; // Skip this curse enchantment
            }

            // Found a suitable enchantment (either not a curse, or curses are allowed)
            enchantmentToTransfer = currentEnchantmentHolder;
            levelToTransfer = entry.getIntValue();
            break; // Stop searching, we found one to transfer
        }

        // --- Post-Loop Check (Modification 3 remains the same) ---
        if (enchantmentToTransfer == null) {
            System.out.println("No non-curse enchantment found to transfer.");
            if (!ALLOW_CURSE_TRANSFER) {
                player.displayClientMessage(Component.literal("No enchantments found to transfer (excluding curses).").withStyle(net.minecraft.ChatFormatting.YELLOW), true);
            } else {
                player.displayClientMessage(Component.literal("No enchantments found to transfer.").withStyle(net.minecraft.ChatFormatting.YELLOW), true);
            }
            lastInteractionTickMap.put(playerUUID, currentTick);
            return;
        }

        System.out.println("Transferring Enchantment: " + enchantmentToTransfer.getRegisteredName() + " Level: " + levelToTransfer);

        // ... (Create Book, Remaining Enchantments, Apply Changes, Feedback, Cooldown update, Event cancel remains the same) ...

        ItemStack enchantedBook = new ItemStack(Items.ENCHANTED_BOOK, 1);
        ItemEnchantments.Mutable bookEnchantBuilder = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        bookEnchantBuilder.set(enchantmentToTransfer, levelToTransfer);
        enchantedBook.set(DataComponents.STORED_ENCHANTMENTS, bookEnchantBuilder.toImmutable());

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

        mainHandItem.set(DataComponents.ENCHANTMENTS, remainingEnchantments);
        mainHandItem.remove(DataComponents.REPAIR_COST);
        if (!player.isCreative()) { player.giveExperienceLevels(-XP_COST); }
        offHandItem.shrink(1);
        if (!player.getInventory().add(enchantedBook)) {
            player.drop(enchantedBook, false);
            player.displayClientMessage(Component.literal("Inventory full! Enchanted book dropped.").withStyle(net.minecraft.ChatFormatting.YELLOW), true);
        }

        level.playSound(null, player.blockPosition(), SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, level.random.nextFloat() * 0.1F + 0.9F);

        // System.out.println("Enchantment transfer successful!");
        // player.displayClientMessage(Component.literal("Enchantment transferred!"), true);

        lastInteractionTickMap.put(playerUUID, currentTick);
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);

    }
}