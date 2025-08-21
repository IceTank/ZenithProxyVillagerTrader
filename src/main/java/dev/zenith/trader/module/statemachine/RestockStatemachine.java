package dev.zenith.trader.module.statemachine;

import com.google.common.collect.Lists;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.zenith.cache.data.entity.EntityLiving;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.feature.inventory.actions.InventoryAction;
import com.zenith.feature.inventory.actions.PlaceRecipe;
import com.zenith.feature.inventory.actions.SelectTrade;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.inventory.util.InventoryActionMacros;
import com.zenith.feature.inventory.util.InventoryUtil;
import com.zenith.feature.pathfinder.PathingRequestFuture;
import com.zenith.mc.block.BlockPos;
import com.zenith.mc.item.ItemRegistry;
import com.zenith.util.RequestFuture;
import dev.zenith.trader.ItemUtil;
import dev.zenith.trader.module.IStatemachine;
import dev.zenith.trader.module.VillagerTrader;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.MetadataTypes;
import org.geysermc.mcprotocollib.protocol.data.game.entity.metadata.VillagerData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundMerchantOffersPacket;

import java.util.List;
import java.util.Optional;
import java.util.Comparator;

import static com.zenith.Globals.*;
import static dev.zenith.trader.ItemUtil.*;
import static dev.zenith.trader.VillagerTraderPlugin.PLUGIN_CONFIG;
import static dev.zenith.trader.module.VillagerTrader.PRIORITY;

/*
 * @author IceTank
 * @since 20.08.2025
 */
public class RestockStatemachine implements IStatemachine {
    RestockStates state = RestockStates.START;
    private PathingRequestFuture restockPathingFuture = PathingRequestFuture.rejected;
    private RequestFuture restockWithdrawFuture = RequestFuture.rejected;
    private RequestFuture emeraldBlockCraftFuture = RequestFuture.rejected;
    private PathingRequestFuture librarianPathingFuture = PathingRequestFuture.rejected;
    private RequestFuture bookshelfPurchaseFuture = RequestFuture.rejected;
    private PathingRequestFuture bookshelfPlaceFuture = PathingRequestFuture.rejected;
    private PathingRequestFuture bookshelfBreakFuture = PathingRequestFuture.rejected;
    private ClientboundMerchantOffersPacket librarianOffersPacket = null;
    private BlockPos bookshelfPos = null;
    private int tickCounter = 0;
    private final Cache<Integer, Boolean> checkedLibrariansCache = CacheBuilder.newBuilder().build();
    VillagerTrader owner;

    public RestockStatemachine(VillagerTrader owner) {
        this.owner = owner;
    }

    public void restock() {
        reset();
    }

    @Override
    public void reset() {
        this.state = RestockStates.START;
        this.restockPathingFuture = PathingRequestFuture.rejected;
        this.restockWithdrawFuture = RequestFuture.rejected;
        this.emeraldBlockCraftFuture = RequestFuture.rejected;
        this.librarianPathingFuture = PathingRequestFuture.rejected;
        this.bookshelfPurchaseFuture = RequestFuture.rejected;
        this.bookshelfPlaceFuture = PathingRequestFuture.rejected;
        this.bookshelfBreakFuture = PathingRequestFuture.rejected;
        this.librarianOffersPacket = null;
        this.bookshelfPos = null;
        this.tickCounter = 0;
        this.checkedLibrariansCache.invalidateAll();
    }
    @Override
    public void onTick() {
        switch (state) {
            case START -> {
                if (needsBooks()) {
                    if (canBuyBookshelvesFromLibrarian()) {
                        switchState(RestockStates.BUY_BOOKSHELF_FROM_LIBRARIAN);
                    } else {
                        switchState(RestockStates.GO_TO_CHEST_BOOKS);
                    }
                } else if (needsCashMoney()) {
                    switchState(RestockStates.GO_TO_CHEST_EMERALDS);
                } else {
                    owner.info("Restocking completed, we have enough to trade");
                    switchState(RestockStates.SUCCESS);
                }
            }
            case GO_TO_CHEST_EMERALDS -> {
                int emeraldCount = ItemUtil.countItem(ItemRegistry.EMERALD.id());
                int emeraldBlockCount = ItemUtil.countItem(ItemRegistry.EMERALD_BLOCK.id());

                if (emeraldCount + (emeraldBlockCount * 9) < PLUGIN_CONFIG.restockEmeraldCountThreshold) {
                    var restockChest = PLUGIN_CONFIG.restockChest;
                    restockPathingFuture = BARITONE.rightClickBlock(restockChest.x(), restockChest.y(), restockChest.z());
                    restockPathingFuture.addExecutedListener(f -> owner.waitForInteractTimer.reset());
                    switchState(RestockStates.PATHING_TO_CHEST_EMERALD);
                } else if (emeraldBlockCount > 0) {
                    switchState(RestockStates.CRAFT_EMERALD_BLOCKS);
                } else {
                    switchState(RestockStates.START);
                }
            }
            case GO_TO_CHEST_BOOKS -> {
                if (needsBooks()) {
                    var restockChest = PLUGIN_CONFIG.restockChestBooks;
                    restockPathingFuture = BARITONE.rightClickBlock(restockChest.x(), restockChest.y(), restockChest.z());
                    restockPathingFuture.addExecutedListener(f -> owner.waitForInteractTimer.reset());
                    switchState(RestockStates.PATHING_TO_CHEST_BOOKS);
                } else {
                    switchState(RestockStates.START);
                }
            }

            case PATHING_TO_CHEST_EMERALD -> {
                if (restockPathingFuture.isCompleted()) {
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() != 0) {
                        var actions = Lists.newArrayList(
                                InventoryActionMacros.withdraw(
                                        openContainer.getContainerId(),
                                        i -> i.getId() == ItemRegistry.EMERALD.id() || i.getId() == ItemRegistry.EMERALD_BLOCK.id(),
                                        PLUGIN_CONFIG.restockStacks));
                        actions.add(new CloseContainer(openContainer.getContainerId()));
                        restockWithdrawFuture = INVENTORY.submit(InventoryActionRequest.builder()
                                .owner(this)
                                .actions(actions)
                                .priority(PRIORITY)
                                .build());
                        switchState(RestockStates.WITHDRAWING_FROM_CHEST_EMERALD);
                    } else {
                        if (owner.waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            switchState(RestockStates.GO_TO_CHEST_EMERALDS);
                        }
                    }
                }
            }
            case PATHING_TO_CHEST_BOOKS -> {
                if (restockPathingFuture.isCompleted()) {
                    var openContainer = CACHE.getPlayerCache().getInventoryCache().getOpenContainer();
                    if (openContainer.getContainerId() != 0) {
                        var actions = Lists.newArrayList(
                                InventoryActionMacros.withdraw(
                                        openContainer.getContainerId(),
                                        i -> i.getId() == ItemRegistry.BOOK.id(),
                                        PLUGIN_CONFIG.restockStacks));
                        actions.add(new CloseContainer(openContainer.getContainerId()));
                        restockWithdrawFuture = INVENTORY.submit(InventoryActionRequest.builder()
                                .owner(this)
                                .actions(actions)
                                .priority(PRIORITY)
                                .build());
                        switchState(RestockStates.WITHDRAWING_FROM_CHEST_BOOKS);
                    } else {
                        if (owner.waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            switchState(RestockStates.GO_TO_CHEST_BOOKS);
                        }
                    }
                }
            }
            case WITHDRAWING_FROM_CHEST_EMERALD -> {
                if (restockWithdrawFuture.isCompleted()) {
                    int emeraldCount = countItem(ItemRegistry.EMERALD.id());
                    int emeraldBlockCount = countItem(ItemRegistry.EMERALD_BLOCK.id());
                    if (emeraldCount + (emeraldBlockCount * 9) < PLUGIN_CONFIG.restockEmeraldCountThreshold) {
                        owner.warn("We have fewer than {} emeralds after restocking, trying to continue trading anyway", PLUGIN_CONFIG.restockEmeraldCountThreshold);
                    }
                    if (emeraldBlockCount > 0) {
                        switchState(RestockStates.CRAFT_EMERALD_BLOCKS);
                    } else {
                        switchState(RestockStates.START);
                    }
                }
            }
            case WITHDRAWING_FROM_CHEST_BOOKS -> {
                if (restockWithdrawFuture.isCompleted()) {
                    int bookCount = countItem(ItemRegistry.BOOK.id());
                    if (bookCount < PLUGIN_CONFIG.restockBooksCountThreshold) { // Uses emerald count threshold because im lazy
                        owner.warn("We have fewer than {} books after restocking, trying to continue trading anyway", PLUGIN_CONFIG.restockBooksCountThreshold);
                    }
                    switchState(RestockStates.START);
                }
            }
            case BUY_BOOKSHELF_FROM_LIBRARIAN -> {
                var librarian = findLibrarianWithBookshelfTrade();
                if (librarian.isEmpty()) {
                    if (!hasUncheckedLibrarians()) {
                        owner.warn("All librarians have been checked, falling back to chest");
                    } else {
                        owner.warn("No more librarians with available bookshelf trades found, falling back to chest");
                    }
                    switchState(RestockStates.GO_TO_CHEST_BOOKS);
                    return;
                }
                librarianOffersPacket = null;
                librarianPathingFuture = BARITONE.rightClickEntity(librarian.get());
                librarianPathingFuture.addExecutedListener(f -> owner.waitForInteractTimer.reset());
                checkedLibrariansCache.put(librarian.get().getEntityId(), true);
                switchState(RestockStates.AWAIT_BOOKSHELF_PURCHASE);
            }
            case AWAIT_BOOKSHELF_PURCHASE -> {
                if (librarianPathingFuture.isCompleted()) {
                    if (librarianOffersPacket == null) {
                        if (owner.waitForInteractTimer.tick(PLUGIN_CONFIG.waitForInteractTimeoutTicks)) {
                            owner.warn("Failed to get librarian offers, trying next librarian");
                            switchState(RestockStates.BUY_BOOKSHELF_FROM_LIBRARIAN);
                        }
                        return;
                    }
                    
                    VillagerTrade[] trades = librarianOffersPacket.getTrades();
                    List<InventoryAction> actions = Lists.newArrayList();
                    boolean foundBookshelfTrade = false;
                    
                    for (int i = 0; i < trades.length; i++) {
                        var trade = trades[i];
                        if (trade.isTradeDisabled()) continue;
                        if (trade.getOutput() == null) continue;
                        if (trade.getOutput().getId() != ItemRegistry.BOOKSHELF.id()) continue;
                        if (trade.getFirstInput().getId() != ItemRegistry.EMERALD.id()) continue;
                        if (trade.getSecondInput() != null) continue;
                        if (trade.getNumUses() >= trade.getMaxUses()) continue; // Check if out of stock
                        
                        int cost = trade.getFirstInput().getAmount();
                        if (cost > PLUGIN_CONFIG.maxSpendPerTrade) continue;
                        if (ItemUtil.countItem(ItemRegistry.EMERALD.id()) < cost) continue;
                        
                        actions.add(new SelectTrade(librarianOffersPacket.getContainerId(), i));
                        actions.add(new ShiftClick(librarianOffersPacket.getContainerId(), 2, ShiftClickItemAction.LEFT_CLICK));
                        foundBookshelfTrade = true;
                        break;
                    }
                    
                    actions.add(new CloseContainer(librarianOffersPacket.getContainerId()));
                    
                    if (!foundBookshelfTrade) {
                        owner.warn("No suitable bookshelf trades found with this librarian, trying next librarian");
                        bookshelfPurchaseFuture = INVENTORY.submit(InventoryActionRequest.builder()
                            .owner(this)
                            .priority(PRIORITY)
                            .actions(List.of(new CloseContainer(librarianOffersPacket.getContainerId())))
                            .build());
                        switchState(RestockStates.BUY_BOOKSHELF_FROM_LIBRARIAN);
                        return;
                    }
                    
                    bookshelfPurchaseFuture = INVENTORY.submit(InventoryActionRequest.builder()
                        .owner(this)
                        .priority(PRIORITY)
                        .actions(actions)
                        .build());
                    switchState(RestockStates.PLACE_BOOKSHELF);
                }
            }
            case PLACE_BOOKSHELF -> {
                if (bookshelfPurchaseFuture.isCompleted()) {
                    int bookshelfCount = ItemUtil.countItem(ItemRegistry.BOOKSHELF.id());
                    if (bookshelfCount == 0) {
                        owner.warn("No bookshelves acquired, trying to continue with what we have");
                        switchState(RestockStates.START);
                        return;
                    }
                    
                    // Find a location to place the bookshelf
                    bookshelfPos = findNearbyEmptyBlock();
                    if (bookshelfPos == null) {
                        owner.warn("Cannot find suitable location to place bookshelf, trying to continue");
                        switchState(RestockStates.START);
                        return;
                    }
                    
                    // Place the bookshelf by right-clicking on the block below
                    bookshelfPlaceFuture = BARITONE.rightClickBlock(bookshelfPos.x(), bookshelfPos.y() - 1, bookshelfPos.z());
                    switchState(RestockStates.BREAK_BOOKSHELF);
                }
            }
            case BREAK_BOOKSHELF -> {
                if (bookshelfPlaceFuture.isCompleted()) {
                    tickCounter++;
                    // Wait 10 ticks (500ms) before breaking to ensure placement is complete
                    if (tickCounter >= 10) {
                        tickCounter = 0;
                        
                        // Break the bookshelf - this will properly mine it
                        if (bookshelfPos != null) {
                            bookshelfBreakFuture = BARITONE.breakBlock(bookshelfPos.x(), bookshelfPos.y(), bookshelfPos.z(), true);
                            switchState(RestockStates.AWAIT_BOOKSHELF_BREAKING);
                        } else {
                            switchState(RestockStates.START);
                        }
                    }
                }
            }
            case AWAIT_BOOKSHELF_BREAKING -> {
                if (bookshelfBreakFuture.isCompleted()) {
                    tickCounter++;
                    // Wait 20 ticks (1 second) to pick up books
                    if (tickCounter >= 20) {
                        tickCounter = 0;
                        
                        int bookCount = ItemUtil.countItem(ItemRegistry.BOOK.id());
                        if (bookCount < PLUGIN_CONFIG.restockEmeraldCountThreshold) {
                            int bookshelfCount = ItemUtil.countItem(ItemRegistry.BOOKSHELF.id());
                            if (bookshelfCount > 0) {
                                switchState(RestockStates.PLACE_BOOKSHELF);
                            } else {
                                owner.warn("We have fewer than {} books after breaking bookshelves, trying to continue trading anyway", PLUGIN_CONFIG.restockEmeraldCountThreshold);
                                switchState(RestockStates.START);
                            }
                        } else {
                            switchState(RestockStates.START);
                        }
                    }
                }
            }
            case CRAFT_EMERALD_BLOCKS -> {
                int emeraldBlockCount = countItem(ItemRegistry.EMERALD_BLOCK.id());
                if (emeraldBlockCount == 0) {
                    switchState(RestockStates.START);
                    return;
                }
                int emptySlots = countInvEmptySlots();
                if (emptySlots < 4) {
                    switchState(RestockStates.START);
                    return;
                }
                int emeraldBlockSlot = InventoryUtil.searchPlayerInventory(i -> i.getId() == ItemRegistry.EMERALD_BLOCK.id());
                if (emeraldBlockSlot == -1) {
                    switchState(RestockStates.START);
                    return;
                }
                List<InventoryAction> actions = Lists.newArrayList();
                actions.add(new PlaceRecipe(0, "minecraft:emerald", true));
                actions.add(new ShiftClick(0, ShiftClickItemAction.LEFT_CLICK));
                actions.add(new CloseContainer(0));
                emeraldBlockCraftFuture = INVENTORY.submit(InventoryActionRequest.builder()
                        .owner(this)
                        .actions(actions)
                        .priority(PRIORITY)
                        .build());
                switchState(RestockStates.AWAIT_CRAFT_EMERALD_BLOCKS);
            }
            case AWAIT_CRAFT_EMERALD_BLOCKS -> {
                if (emeraldBlockCraftFuture.isCompleted()) {
                    int emeraldBlockCount = countItem(ItemRegistry.EMERALD_BLOCK.id());
                    if (emeraldBlockCount > 0) {
                        switchState(RestockStates.CRAFT_EMERALD_BLOCKS);
                    } else {
                        switchState(RestockStates.START);
                    }
                }
            }
        }
    }

    @Override
    public boolean isSuccessful() {
        return this.state == RestockStates.SUCCESS;
    }

    @Override
    public boolean isError() {
        return this.state == RestockStates.ERROR;
    }

    @Override
    public boolean isRunning() {
        return this.state != RestockStates.SUCCESS && this.state != RestockStates.ERROR;
    }

    private void switchState(RestockStates newState) {
        this.state = newState;
        // Additional logic for state transition can be added here
    }

    public boolean needsRestock() {
        return needsCashMoney() || needsBooks();
    }

    public void setLibrarianOffersPacket(ClientboundMerchantOffersPacket packet) {
        this.librarianOffersPacket = packet;
    }

    private boolean needsCashMoney() {
        int emeraldCount = ItemUtil.countItem(ItemRegistry.EMERALD.id());
        int emeraldBlockCount = ItemUtil.countItem(ItemRegistry.EMERALD_BLOCK.id());
        return emeraldCount + (emeraldBlockCount * 9) < PLUGIN_CONFIG.restockEmeraldCountThreshold;
    }

    private boolean needsBooks() {
        if (!desiresBookTrades()) {
            return false;
        }
        int bookCount = ItemUtil.countItem(ItemRegistry.BOOK.id());
        return bookCount < PLUGIN_CONFIG.restockEmeraldCountThreshold;
    }

    private boolean desiresBookTrades() {
        return getBuyItemIds(owner).contains(ItemRegistry.ENCHANTED_BOOK.id());
    }

    private boolean canBuyBookshelvesFromLibrarian() {
        return PLUGIN_CONFIG.buyBookshelvesFromLibrarians && 
               findLibrarianWithBookshelfTrade().isPresent() && 
               ItemUtil.countItem(ItemRegistry.EMERALD.id()) >= 9;
    }

    private Optional<EntityLiving> findLibrarianWithBookshelfTrade() {
        return CACHE.getEntityCache().getEntities().values().stream()
            .filter(e -> e.getEntityType() == EntityType.VILLAGER)
            .map(e -> (EntityLiving) e)
            .filter(this::isLibrarian)
            .filter(e -> !checkedLibrariansCache.asMap().containsKey(e.getEntityId()))
            .min(Comparator.comparingDouble(e -> e.distanceSqTo(CACHE.getPlayerCache().getThePlayer())));
    }

    private boolean hasUncheckedLibrarians() {
        return CACHE.getEntityCache().getEntities().values().stream()
            .filter(e -> e.getEntityType() == EntityType.VILLAGER)
            .map(e -> (EntityLiving) e)
            .filter(this::isLibrarian)
            .anyMatch(e -> !checkedLibrariansCache.asMap().containsKey(e.getEntityId()));
    }

    private boolean isLibrarian(EntityLiving villager) {
        var data = villager.getMetadataValue(18, MetadataTypes.VILLAGER_DATA, VillagerData.class);
        if (data == null) {
            return false;
        }
        return data.getProfession() == VillagerTrader.VillagerProfession.LIBRARIAN.ordinal();
    }

    private BlockPos findNearbyEmptyBlock() {
        var playerPos = CACHE.getPlayerCache().getThePlayer().position();
        int baseX = (int) Math.floor(playerPos.getX());
        int baseY = (int) Math.floor(playerPos.getY());
        int baseZ = (int) Math.floor(playerPos.getZ());
        
        return new BlockPos(baseX + 1, baseY, baseZ);
    }


    enum RestockStates {
        START,
        GO_TO_CHEST_EMERALDS,
        PATHING_TO_CHEST_EMERALD,
        WITHDRAWING_FROM_CHEST_EMERALD,
        GO_TO_CHEST_BOOKS,
        PATHING_TO_CHEST_BOOKS,
        WITHDRAWING_FROM_CHEST_BOOKS,
        BUY_BOOKSHELF_FROM_LIBRARIAN,
        AWAIT_BOOKSHELF_PURCHASE,
        PLACE_BOOKSHELF,
        BREAK_BOOKSHELF,
        AWAIT_BOOKSHELF_BREAKING,
        CRAFT_EMERALD_BLOCKS,
        AWAIT_CRAFT_EMERALD_BLOCKS,
        SUCCESS,
        ERROR
    }
}
