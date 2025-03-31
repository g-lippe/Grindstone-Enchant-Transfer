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

    private static final int XP_COST = 5; // Experience level cost

    // Listen for Right Click Block interactions with high priority
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onPlayerRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Player player = event.getEntity();
        Level level = event.getLevel();
        BlockPos pos = event.getPos();


        // --- Initial Checks ---
        // Only run server-side
        if (level.isClientSide()) {
            return;
        }
        // Must be sneaking
        if (!player.isShiftKeyDown()) {
            return;
        }
        // Must be clicking a Grindstone
        if (level.getBlockState(pos).getBlock() != Blocks.GRINDSTONE) {
            //player.displayClientMessage(Component.literal("Bro das not a grindstone"), false);
            return;
        }


        //Get Hand Items
        ItemStack mainHandItem = player.getMainHandItem();
        ItemStack offHandItem = player.getOffhandItem();

        // --- Item and Enchantment Checks ---
        // Main hand: Must have an item and it must be enchanted
        if (mainHandItem.isEmpty() || !mainHandItem.isEnchanted()) {
            return;
        }
        // Offhand: Must be exactly one regular Book
        if (offHandItem.getItem() != Items.BOOK || offHandItem.getCount() != 1) {
            return;
        }

        player.displayClientMessage(Component.literal("Main Hand: " + mainHandItem), false);
        player.displayClientMessage(Component.literal("Off Hand: " + offHandItem), false);

        // ---------------------------------------------------------------------------------------------------

        // 1. Get Enchantments from main Hand Item using DataComponents
        // getOrDefault handles the case where the component might be missing (though you should check beforehand)
        ItemEnchantments currentEnchantments = mainHandItem.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY);

        // Double-check there are enchantments to transfer
        if (currentEnchantments.isEmpty()) {
            System.out.println("No enchantments found on main hand item.");
            // Optionally send a message to the player
            // player.sendSystemMessage(Component.literal("Item has no enchantments to transfer."));
            return; // Stop processing
        }


        // 2. Identify the first enchantment to transfer
        Holder<Enchantment> enchantmentToTransfer = null;
        int levelToTransfer = 0;

        // ItemEnchantments maintains insertion order, so the first entry in the iterator is reliable
        // Use entrySet() which is guaranteed to preserve order.
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : currentEnchantments.entrySet()) {
            enchantmentToTransfer = entry.getKey();
            levelToTransfer = entry.getIntValue();
            break; // We only want the first one
        }

        // Should not happen if isEmpty() check passed, but safety first
        if (enchantmentToTransfer == null) {
            System.err.println("Error: Could not find an enchantment to transfer despite isEmpty() being false.");
            return;
        }

        System.out.println("Transferring Enchantment: " + enchantmentToTransfer.getRegisteredName() + " Level: " + levelToTransfer);


        // 3. Create the new Enchanted Book with the single enchantment
        ItemStack enchantedBook = new ItemStack(Items.ENCHANTED_BOOK, 1); // Create a stack of 1 enchanted book

        // --- Correction Start ---
        // Create a mutable builder starting empty
        ItemEnchantments.Mutable bookEnchantBuilder = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        // Add the single enchantment we identified earlier
        bookEnchantBuilder.set(enchantmentToTransfer, levelToTransfer);
        // Build the final immutable ItemEnchantments object
        ItemEnchantments bookEnchantment = bookEnchantBuilder.toImmutable();
        // --- Correction End ---

        // Apply the built enchantments to the Enchanted Book using the correct component
        enchantedBook.set(DataComponents.STORED_ENCHANTMENTS, bookEnchantment);

        System.out.println("Created Enchanted Book with: " + bookEnchantment);


        // 4. Create the set of remaining enchantments for the original item
        ItemEnchantments.Mutable remainingEnchantmentsMutable = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
        boolean firstSkipped = false;
        for (Object2IntMap.Entry<Holder<Enchantment>> entry : currentEnchantments.entrySet()) {
            // Skip the first enchantment (the one we transferred)
            if (!firstSkipped) {
                firstSkipped = true;
                continue;
            }
            // Add all other enchantments to the mutable builder
            remainingEnchantmentsMutable.set(entry.getKey(), entry.getIntValue());
        }
        // Build the final immutable set for the item
        ItemEnchantments remainingEnchantments = remainingEnchantmentsMutable.toImmutable();

        System.out.println("Remaining Enchantments for Original Item: " + remainingEnchantments);


        // 5. Apply the remaining enchantments back to the main hand item
        mainHandItem.set(DataComponents.ENCHANTMENTS, remainingEnchantments);
        // Optional: Grindstones usually remove repair cost penalty. You might want to do that too.
        mainHandItem.remove(DataComponents.REPAIR_COST);


        // 6. Update the player's inventory
        offHandItem.shrink(1); // Consume one book from the off-hand stack
        player.setItemInHand(InteractionHand.OFF_HAND, enchantedBook); // Put the new enchanted book in the off-hand

        // 7. Add feedback (optional but good practice)
        level.playSound(null, player.blockPosition(), SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.playSound(null, player.blockPosition(), SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, level.random.nextFloat() * 0.1F + 0.9F); // A little magic sound

        System.out.println("Enchantment transfer successful!");




        /*


        // Get Enchant IDs
        for (Holder<Enchantment> enchantmentHolder : enchantments.keySet()) {
            ResourceLocation enchantmentID = enchantmentHolder.unwrapKey()
                    .map(key -> key.location()) // Gets the ResourceLocation
                    .orElse(null); // Fallback in case it's empty

            if (enchantmentID != null) {
                System.out.println("Enchantment ID: " + enchantmentID);
            }
        }


        //Grab one Enchant
        Optional<Object2IntMap.Entry<Holder<Enchantment>>> enchantmentToRemove = enchantments.entrySet().stream().findFirst();

        if (enchantmentToRemove.isPresent()) {
            Object2IntMap.Entry<Holder<Enchantment>> enchantment = enchantmentToRemove.get();

            System.out.println("Enchantment To Remove ID: " + enchantment);

            // Remove it if found
            //enchantmentToRemove.ifPresent(enchantments::remove);



        }



        System.out.println("What actually matters-----------------");


        System.out.println("Keyset:");
        System.out.println(enchants.keySet());

        System.out.println("EntrySet:");
        System.out.println(enchants.entrySet());

        System.out.println("toString:");
        System.out.println(enchants.toString());


        Map.Entry<String, Integer>[] enchantmentsArray = EnchantmentUtil.getEnchantmentsAsArray(mainHandItem.getTagEnchantments());

        System.out.println(enchantmentsArray[0]);


        //Set Enchantments on Book
        EnchantmentHelper.setEnchantments(offHandItem, mainHandItem.getTagEnchantments());



        // Get enchantments from the main-hand item
        ItemEnchantments enchantments = mainHandItem.getTagEnchantments();

        // Create a new Enchanted Book item
        ItemStack enchantedBook = new ItemStack(Items.ENCHANTED_BOOK);

        // Apply the enchantments to the Enchanted Book
        EnchantmentHelper.setEnchantments(enchantedBook, enchantments);

        // Replace the Book in the off-hand with the new Enchanted Book
        offHandItem.setCount(0); // Remove the old book
        player.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, enchantedBook);
        */


        /*
        // Get enchantments from main hand item
        Map<Enchantment, Integer> enchantments = EnchantmentHelper.getEnchantments(mainHandItem);



        // Find the first non-curse enchantment
        Optional<Map.Entry<Enchantment, Integer>> enchantmentToTransfer = enchantments.entrySet()
                .stream()
                .filter(entry -> !entry.getKey().isCurse())
                .findFirst(); // Get the first one found

        // If no non-curse enchantment exists, do nothing
        if (enchantmentToTransfer.isEmpty()) {
            return;
        }

        // --- Experience Check ---
        if (!player.isCreative() && player.experienceLevel < XP_COST) {
            // Optional: Send player a message that they don't have enough XP
            // player.displayClientMessage(Component.literal("Not enough experience!").withStyle(ChatFormatting.RED), true);
            return;
        }

        */


        // --- Perform the Transfer ---
        /*
        Enchantment enchantment = enchantmentToTransfer.get().getKey();
        int levelValue = enchantmentToTransfer.get().getValue();

        // 1. Create the new Enchanted Book
        ItemStack enchantedBook = new ItemStack(Items.ENCHANTED_BOOK);
        EnchantedBookItem.addEnchantment(enchantedBook, new EnchantmentInstance(enchantment, levelValue));

        // 2. Consume the book in the off-hand
        offHandItem.shrink(1);

        // 3. Remove the specific enchantment from the main hand item
        // Create a new map with all enchantments EXCEPT the one transferred
        Map<Enchantment, Integer> remainingEnchantments = EnchantmentHelper.getEnchantments(mainHandItem); // Get a mutable copy
        remainingEnchantments.remove(enchantment); // Remove the transferred one
        EnchantmentHelper.setEnchantments(remainingEnchantments, mainHandItem); // Apply the remaining enchantments back

        // 4. Deduct Experience (only if not in creative)
        if (!player.isCreative() && player instanceof ServerPlayer serverPlayer) { // Need ServerPlayer for XP manipulation
            serverPlayer.giveExperienceLevels(-XP_COST);
            // Optional: Add some experience orbs as if disenchanting?
            // GrindstoneMenu.handleExperience(level, pos, player.experienceLevel); // Example concept, needs refinement
        }

        // 5. Give the enchanted book to the player
        if (!player.getInventory().add(enchantedBook)) {
            player.drop(enchantedBook, false); // Drop if inventory is full
        }

        // 6. Play Sounds
        level.playSound(null, pos, SoundEvents.GRINDSTONE_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        level.playSound(null, pos, SoundEvents.ENCHANTMENT_TABLE_USE, SoundSource.BLOCKS, 1.0F, level.random.nextFloat() * 0.1F + 0.9F);

        // 7. Cancel the original event (IMPORTANT: prevents Grindstone GUI from opening)
        event.setCanceled(true);
        // Signal that the interaction was successful on the server side
        event.setCancellationResult(InteractionResult.SUCCESS);
        */
    }
}