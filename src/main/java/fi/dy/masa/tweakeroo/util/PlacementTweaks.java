package fi.dy.masa.tweakeroo.util;

import javax.annotation.Nullable;
import fi.dy.masa.tweakeroo.config.Callbacks;
import fi.dy.masa.tweakeroo.config.ConfigsGeneric;
import fi.dy.masa.tweakeroo.config.FeatureToggle;
import fi.dy.masa.tweakeroo.config.Hotkeys;
import fi.dy.masa.tweakeroo.config.interfaces.IConfigOptionListEntry;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.item.ItemStack;
import net.minecraft.network.play.client.CPacketPlayer;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumFacing.AxisDirection;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;

public class PlacementTweaks
{
    private static BlockPos posFirst = null;
    private static BlockPos posLast = null;
    private static HitPart hitPartFirst = null;
    private static Vec3d hitVecFirst = null;
    private static EnumFacing sideFirst = null;
    private static EnumFacing sideRotatedFirst = null;
    private static float playerYawFirst;
    private static ItemStack stackFirst = ItemStack.EMPTY;
    private static ItemStack[] stackBeforeUse = new ItemStack[] { ItemStack.EMPTY, ItemStack.EMPTY };
    private static FastMode fastMode = FastMode.PLANE;
    private static boolean isEmulatedClick;
    private static boolean firstWasRotation;
    private static boolean firstWasOffset;

    public static void onTick(Minecraft mc)
    {
        if (mc.currentScreen == null)
        {
            if (mc.gameSettings.keyBindUseItem.isKeyDown())
            {
                onUsingTick();
            }

            if (mc.gameSettings.keyBindAttack.isKeyDown())
            {
                onAttackTick(mc);
            }
        }
        else
        {
            stackBeforeUse[0] = ItemStack.EMPTY;
            stackBeforeUse[1] = ItemStack.EMPTY;
        }

        if (mc.gameSettings.keyBindUseItem.isKeyDown() == false)
        {
            clearClickedBlockInfo();

            // Clear the cached stack when releasing both keys, so that the restock doesn't happen when
            // using another another item or an empty hand.
            if (mc.gameSettings.keyBindAttack.isKeyDown() == false)
            {
                stackBeforeUse[0] = ItemStack.EMPTY;
                stackBeforeUse[1] = ItemStack.EMPTY;
            }
        }
    }

    public static boolean onProcessRightClickPre(EntityPlayer player, EnumHand hand)
    {
        InventoryUtils.trySwapCurrentToolIfNearlyBroken();

        ItemStack stackOriginal = player.getHeldItem(hand);

        if (isEmulatedClick == false &&
            FeatureToggle.TWEAK_HAND_RESTOCK.getBooleanValue() &&
            stackOriginal.isEmpty() == false)
        {
            //System.out.printf("onProcessRightClickPre storing stack: %s\n", stackOriginal);
            stackBeforeUse[hand.ordinal()] = stackOriginal.copy();
        }

        return InventoryUtils.canUnstackingItemNotFitInInventory(stackOriginal, player);
    }

    public static void onProcessRightClickPost(EntityPlayer player, EnumHand hand)
    {
        if (FeatureToggle.TWEAK_HAND_RESTOCK.getBooleanValue())
        {
            //System.out.printf("onProcessRightClickPost -> tryRestockHand with: %s, current: %s\n", stackBeforeUse[hand.ordinal()], player.getHeldItem(hand));
            tryRestockHand(player, hand, stackBeforeUse[hand.ordinal()]);
        }
    }

    public static void onLeftClickMousePre()
    {
        onProcessRightClickPre(Minecraft.getMinecraft().player, EnumHand.MAIN_HAND);
    }

    public static void onLeftClickMousePost()
    {
        onProcessRightClickPost(Minecraft.getMinecraft().player, EnumHand.MAIN_HAND);
    }

    public static void setFastPlacementMode(FastMode mode)
    {
        fastMode = mode;
        ConfigsGeneric.FAST_PLACEMENT_MODE.setOptionListValue(mode);

        Minecraft mc = Minecraft.getMinecraft();
        String str = TextFormatting.GREEN + mode.name() + TextFormatting.RESET;
        Callbacks.printMessage(mc, "tweakeroo.message.set_fast_placement_mode_to", str);
    }

    public static void setFastPlacementModeFromConfigs()
    {
        fastMode = (FastMode) ConfigsGeneric.FAST_PLACEMENT_MODE.getOptionListValue();
    }

    public static FastMode getFastPlacementMode()
    {
        return fastMode;
    }

    private static void onAttackTick(Minecraft mc)
    {
        if (FeatureToggle.TWEAK_FAST_LEFT_CLICK.getBooleanValue())
        {
            final int count = ConfigsGeneric.FAST_LEFT_CLICK_COUNT.getIntegerValue();

            for (int i = 0; i < count; ++i)
            {
                InventoryUtils.trySwapCurrentToolIfNearlyBroken();
                isEmulatedClick = true;
                ((IMinecraftAccessor) mc).leftClickMouseAccessor();
                isEmulatedClick = false;
            }
        }
        else
        {
            InventoryUtils.trySwapCurrentToolIfNearlyBroken();
        }
    }

    private static void onUsingTick()
    {
        Minecraft mc = Minecraft.getMinecraft();

        if (posFirst != null && mc.player != null && FeatureToggle.TWEAK_FAST_BLOCK_PLACEMENT.getBooleanValue())
        {
            EntityPlayerSP player = mc.player;
            World world = player.getEntityWorld();
            double reach = mc.playerController.getBlockReachDistance();
            int failSafe = 10;

            while (failSafe-- > 0)
            {
                RayTraceResult trace = mc.objectMouseOver;

                if (trace != null && trace.typeOfHit == RayTraceResult.Type.BLOCK)
                {
                    EnumFacing side = trace.sideHit;
                    BlockPos pos = trace.getBlockPos();
                    BlockPos posNew = getPlacementPositionForTargetedPosition(pos, side, world);
                    EnumHand hand = getHandWithItem(stackFirst, player);

                    if (hand != null &&
                        posNew.equals(posLast) == false &&
                        canPlaceBlockIntoPosition(posNew, world) &&
                        (
                            (fastMode == FastMode.PLANE  && isNewPositionValidForPlaneMode(posNew)) ||
                            (fastMode == FastMode.FACE   && isNewPositionValidForFaceMode(posNew, side)) ||
                            (fastMode == FastMode.COLUMN && isNewPositionValidForColumnMode(posNew))
                        )
                    )
                    {
                        /*
                        IBlockState state = world.getBlockState(pos);
                        float x = (float) (trace.hitVec.x - pos.getX());
                        float y = (float) (trace.hitVec.y - pos.getY());
                        float z = (float) (trace.hitVec.z - pos.getZ());

                        if (state.getBlock().onBlockActivated(world, posNew, state, player, hand, side, x, y, z))
                        {
                            return;
                        }
                        */

                        Vec3d hitVec = hitVecFirst.addVector(posNew.getX(), posNew.getY(), posNew.getZ());
                        EnumActionResult result = tryPlaceBlock(mc.playerController, player, mc.world,
                                posNew, sideFirst, sideRotatedFirst, playerYawFirst, hitVec, hand, hitPartFirst, false);

                        if (result == EnumActionResult.SUCCESS)
                        {
                            posLast = posNew;
                            mc.objectMouseOver = player.rayTrace(reach, mc.getRenderPartialTicks());
                        }
                        else
                        {
                            break;
                        }
                    }
                    else
                    {
                        break;
                    }
                }
            }

            // Reset the timer to prevent the regular process method from re-firing
            ((IMinecraftAccessor) mc).setRightClickDelayTimer(4);
        }
        else if (FeatureToggle.TWEAK_FAST_RIGHT_CLICK.getBooleanValue() && mc.gameSettings.keyBindUseItem.isKeyDown())
        {
            final int count = ConfigsGeneric.FAST_RIGHT_CLICK_COUNT.getIntegerValue();

            for (int i = 0; i < count; ++i)
            {
                isEmulatedClick = true;
                ((IMinecraftAccessor) mc).rightClickMouseAccessor();
                isEmulatedClick = false;
            }
        }
    }

    public static EnumActionResult onProcessRightClickBlock(
            PlayerControllerMP controller,
            EntityPlayerSP player,
            WorldClient world,
            BlockPos posIn,
            EnumFacing sideIn,
            Vec3d hitVec,
            EnumHand hand)
    {
        boolean fastPlacement = FeatureToggle.TWEAK_FAST_BLOCK_PLACEMENT.getBooleanValue();
        ItemStack stackPre = player.getHeldItem(hand).copy();
        EnumFacing playerFacingH = player.getHorizontalFacing();
        HitPart hitPart = getHitPart(sideIn, playerFacingH, posIn, hitVec);
        EnumFacing sideRotated = getRotatedFacing(sideIn, playerFacingH, hitPart);

        //System.out.printf("onProcessRightClickBlock() pos: %s, side: %s, part: %s, hitVec: %s\n", posIn, sideIn, hitPart, hitVec);
        EnumActionResult result = tryPlaceBlock(controller, player, world, posIn, sideIn, sideRotated, player.rotationYaw, hitVec, hand, hitPart, true);

        // Store the initial click data for the fast placement mode
        if (posFirst == null && result == EnumActionResult.SUCCESS && fastPlacement)
        {
            boolean flexible = FeatureToggle.TWEAK_FLEXIBLE_BLOCK_PLACEMENT.getBooleanValue();
            boolean rotation = Hotkeys.FLEXIBLE_BLOCK_PLACEMENT_ROTATION.getKeybind().isKeybindHeld(false);
            boolean offset = Hotkeys.FLEXIBLE_BLOCK_PLACEMENT_OFFSET.getKeybind().isKeybindHeld(false);
            firstWasRotation = flexible && rotation;
            firstWasOffset = flexible && offset;
            posFirst = getPlacementPositionForTargetedPosition(posIn, sideIn, world);
            posLast = posFirst;
            hitPartFirst = hitPart;
            hitVecFirst = hitVec.subtract(posFirst.getX(), posFirst.getY(), posFirst.getZ());
            sideFirst = sideIn;
            sideRotatedFirst = sideRotated;
            playerYawFirst = player.rotationYaw;
            stackFirst = stackPre;
        }

        return result;
    }

    private static EnumActionResult tryPlaceBlock(
            PlayerControllerMP controller,
            EntityPlayerSP player,
            WorldClient world,
            BlockPos posIn,
            EnumFacing sideIn,
            EnumFacing sideRotatedIn,
            float playerYaw,
            Vec3d hitVec,
            EnumHand hand,
            HitPart hitPart,
            boolean isFirstClick)
    {
        if (FeatureToggle.TWEAK_FLEXIBLE_BLOCK_PLACEMENT.getBooleanValue())
        {
            EnumFacing side = sideIn;
            boolean handle = false;
            boolean rememberFlexible = FeatureToggle.REMEMBER_FLEXIBLE.getBooleanValue();
            boolean rotationHeld = Hotkeys.FLEXIBLE_BLOCK_PLACEMENT_ROTATION.getKeybind().isKeybindHeld(false);
            boolean offsetHeld = Hotkeys.FLEXIBLE_BLOCK_PLACEMENT_OFFSET.getKeybind().isKeybindHeld(false);
            boolean rotation = rotationHeld || (rememberFlexible && firstWasRotation);
            boolean offset = offsetHeld || (rememberFlexible && firstWasOffset);
            BlockPos posNew = isFirstClick && (rotation || offset) ? getPlacementPositionForTargetedPosition(posIn, sideIn, world) : posIn;

            // Place the block facing/against the adjacent block (= just rotated from normal)
            if (rotation)
            {
                side = sideRotatedIn;
                handle = true;
            }
            else
            {
                // Don't rotate the player facing in handleFlexibleBlockPlacement()
                hitPart = null;
            }

            // Place the block into the diagonal position
            if (offset)
            {
                posNew = posNew.offset(sideRotatedIn.getOpposite());
                handle = true;
            }

            if (handle)
            {
                if (canPlaceBlockIntoPosition(posNew, world))
                {
                    //System.out.printf("tryPlaceBlock() pos: %s, side: %s, part: %s, hitVec: %s\n", posNew, side, hitPart, hitVec);
                    return handleFlexibleBlockPlacement(controller, player, world, posNew, side, playerYaw, hitVec, hand, hitPart);
                }
                else
                {
                    return EnumActionResult.FAIL;
                }
            }
        }
        else if (isFirstClick == false && FeatureToggle.FAST_PLACEMENT_REMEMBER_ALWAYS.getBooleanValue())
        {
            return handleFlexibleBlockPlacement(controller, player, world, posIn, sideIn, playerYaw, hitVec, hand, null);
        }

        return processRightClickBlockWrapper(controller, player, world, posIn, sideIn, hitVec, hand);
    }

    private static void tryRestockHand(EntityPlayer player, EnumHand hand, ItemStack stackOriginal)
    {
        ItemStack stackCurrent = player.getHeldItem(hand);

        if (stackOriginal.isEmpty() == false &&
            (stackCurrent.isEmpty() || InventoryUtils.areStacksEqualIgnoreDurability(stackOriginal, stackCurrent) == false))
        {
            InventoryUtils.restockNewStackToHand(player, hand, stackOriginal);
        }
    }

    private static EnumActionResult processRightClickBlockWrapper(
            PlayerControllerMP controller,
            EntityPlayerSP player,
            WorldClient world,
            BlockPos pos,
            EnumFacing side,
            Vec3d hitVec,
            EnumHand hand)
    {
        // We need to grab the stack here if the cached stack is still empty,
        // because this code runs before the cached stack gets set on the first click/use.
        ItemStack stackOriginal = stackFirst.isEmpty() == false ? stackFirst : player.getHeldItem(hand).copy();
        BlockPos posPlacement = getPlacementPositionForTargetedPosition(pos, side, world);
        IBlockState stateBefore = world.getBlockState(posPlacement);
        IBlockState state = world.getBlockState(pos);

        if (state.getBlock().isReplaceable(world, pos) == false && state.getMaterial().isReplaceable())
        {
            // If the block itself says it's not replaceable, but the material is (fluids),
            // then we need to offset the position back, otherwise the check in ItemBlock
            // will offset the position by one forward from the desired position.
            // FIXME This will break if the block behind the desired position is replaceable though... >_>
            pos = pos.offset(side.getOpposite());
        }

        // Carpet mod accurate block placement protocol support, for Carpet v18_04_24 or later
        if (FeatureToggle.CARPET_FLEXIBLE_BLOCK_PLACEMENT.getBooleanValue())
        {
            int x = side.getIndex() + 2 + pos.getX();

            if (FeatureToggle.TWEAK_AFTER_CLICKER.getBooleanValue())
            {
                x += ConfigsGeneric.AFTER_CLICKER_CLICK_COUNT.getIntegerValue() * 10;
            }

            hitVec = new Vec3d(x, hitVec.y, hitVec.z);
        }

        if (FeatureToggle.TWEAK_PICK_BEFORE_PLACE.getBooleanValue())
        {
            InventoryUtils.switchToPickedBlock();
        }

        InventoryUtils.trySwapCurrentToolIfNearlyBroken();

        //System.out.printf("processRightClickBlockWrapper() pos: %s, side: %s, hitVec: %s\n", pos, side, hitVec);
        EnumActionResult result;

        if (InventoryUtils.canUnstackingItemNotFitInInventory(stackOriginal, player))
        {
            result = EnumActionResult.PASS;
        }
        else
        {
            result = controller.processRightClickBlock(player, world, pos, side, hitVec, hand);
        }

        if (FeatureToggle.TWEAK_HAND_RESTOCK.getBooleanValue())
        {
            // This restock needs to happen even with the pick-before-place tweak active,
            // otherwise the fast placement mode's checks (getHandWithItem()) will fail...
            tryRestockHand(player, hand, stackOriginal);
        }

        if (FeatureToggle.TWEAK_AFTER_CLICKER.getBooleanValue() &&
            FeatureToggle.CARPET_FLEXIBLE_BLOCK_PLACEMENT.getBooleanValue() == false &&
            world.getBlockState(posPlacement) != stateBefore)
        {
            final int count = MathHelper.clamp(ConfigsGeneric.AFTER_CLICKER_CLICK_COUNT.getIntegerValue(), 0, 32);

            for (int i = 0; i < count; i++)
            {
                //System.out.printf("processRightClickBlockWrapper() after-clicker - i: %d, pos: %s, side: %s, hitVec: %s\n", i, pos, side, hitVec);
                controller.processRightClickBlock(player, world, posPlacement, side, hitVec, hand);
            }
        }

        return result;
    }

    private static EnumActionResult handleFlexibleBlockPlacement(
            PlayerControllerMP controller,
            EntityPlayerSP player,
            WorldClient world,
            BlockPos pos,
            EnumFacing side,
            float playerYaw,
            Vec3d hitVec,
            EnumHand hand,
            @Nullable HitPart hitPart)
    {
        EnumFacing facing = EnumFacing.getHorizontal(MathHelper.floor((playerYaw * 4.0F / 360.0F) + 0.5D) & 3);
        float yawOrig = player.rotationYaw;

        if (hitPart == HitPart.CENTER)
        {
            facing = facing.getOpposite();
        }
        else if (hitPart == HitPart.LEFT)
        {
            facing = facing.rotateYCCW();
        }
        else if (hitPart == HitPart.RIGHT)
        {
            facing = facing.rotateY();
        }

        player.rotationYaw = facing.getHorizontalAngle();
        player.connection.sendPacket(new CPacketPlayer.Rotation(player.rotationYaw, player.rotationPitch, player.onGround));

        //System.out.printf("handleFlexibleBlockPlacement() pos: %s, side: %s, facing orig: %s facing new: %s\n", pos, side, facingOrig, facing);
        EnumActionResult result = processRightClickBlockWrapper(controller, player, world, pos, side, hitVec, hand);

        player.rotationYaw = yawOrig;
        player.connection.sendPacket(new CPacketPlayer.Rotation(player.rotationYaw, player.rotationPitch, player.onGround));

        return result;
    }

    private static void clearClickedBlockInfo()
    {
        posFirst = null;
        hitPartFirst = null;
        hitVecFirst = null;
        sideFirst = null;
        sideRotatedFirst = null;
        stackFirst = ItemStack.EMPTY;
        firstWasRotation = false;
        firstWasOffset = false;
    }

    private static EnumFacing getRotatedFacing(EnumFacing originalSide, EnumFacing playerFacingH, HitPart hitPart)
    {
        if (originalSide.getAxis().isVertical())
        {
            switch (hitPart)
            {
                case LEFT:      return playerFacingH.rotateY();
                case RIGHT:     return playerFacingH.rotateYCCW();
                case BOTTOM:    return originalSide == EnumFacing.UP   ? playerFacingH : playerFacingH.getOpposite();
                case TOP:       return originalSide == EnumFacing.DOWN ? playerFacingH : playerFacingH.getOpposite();
                case CENTER:    return originalSide.getOpposite();
                default:        return originalSide;
            }
        }
        else
        {
            switch (hitPart)
            {
                case LEFT:      return originalSide.rotateYCCW();
                case RIGHT:     return originalSide.rotateY();
                case BOTTOM:    return EnumFacing.UP;
                case TOP:       return EnumFacing.DOWN;
                case CENTER:    return originalSide.getOpposite();
                default:        return originalSide;
            }
        }
    }

    public static HitPart getHitPart(EnumFacing originalSide, EnumFacing playerFacingH, BlockPos pos, Vec3d hitVec)
    {
        double x = hitVec.x - pos.getX();
        double y = hitVec.y - pos.getY();
        double z = hitVec.z - pos.getZ();
        double posH = 0;
        double posV = 0;

        switch (originalSide)
        {
            case DOWN:
            case UP:
                switch (playerFacingH)
                {
                    case NORTH:
                        posH = x;
                        posV = 1.0d - z;
                        break;
                    case SOUTH:
                        posH = 1.0d - x;
                        posV = z;
                        break;
                    case WEST:
                        posH = 1.0d - z;
                        posV = 1.0d - x;
                        break;
                    case EAST:
                        posH = z;
                        posV = x;
                        break;
                    default:
                }

                if (originalSide == EnumFacing.DOWN)
                {
                    posV = 1.0d - posV;
                }

                break;
            case NORTH:
            case SOUTH:
                posH = originalSide.getAxisDirection() == AxisDirection.POSITIVE ? x : 1.0d - x;
                posV = y;
                break;
            case WEST:
            case EAST:
                posH = originalSide.getAxisDirection() == AxisDirection.NEGATIVE ? z : 1.0d - z;
                posV = y;
                break;
        }

        double offH = Math.abs(posH - 0.5d);
        double offV = Math.abs(posV - 0.5d);

        if (offH > 0.25d || offV > 0.25d)
        {
            if (offH > offV)
            {
                return posH < 0.5d ? HitPart.LEFT : HitPart.RIGHT;
            }
            else
            {
                return posV < 0.5d ? HitPart.BOTTOM : HitPart.TOP;
            }
        }
        else
        {
            return HitPart.CENTER;
        }
    }

    private static BlockPos getPlacementPositionForTargetedPosition(BlockPos pos, EnumFacing side, World world)
    {
        if (canPlaceBlockIntoPosition(pos, world))
        {
            return pos;
        }

        return pos.offset(side);
    }

    private static boolean canPlaceBlockIntoPosition(BlockPos pos, World world)
    {
        IBlockState state = world.getBlockState(pos);
        return state.getBlock().isReplaceable(world, pos) || state.getMaterial().isReplaceable();
    }

    private static boolean isNewPositionValidForPlaneMode(BlockPos posNew)
    {
        EnumFacing.Axis axis = sideFirst.getAxis();

        // Cursor moved to adjacent position
        if ((axis == EnumFacing.Axis.X && posNew.getX() == posFirst.getX()) ||
            (axis == EnumFacing.Axis.Z && posNew.getZ() == posFirst.getZ()) ||
            (axis == EnumFacing.Axis.Y && posNew.getY() == posFirst.getY()))
        {
            return true;
        }

        return false;
    }

    private static boolean isNewPositionValidForFaceMode(BlockPos posNew, EnumFacing side)
    {
        return side == sideFirst;
    }

    private static boolean isNewPositionValidForColumnMode(BlockPos posNew)
    {
        EnumFacing.Axis axis = sideFirst.getAxis();

        if ((axis == EnumFacing.Axis.X && posNew.getY() == posFirst.getY() && posNew.getZ() == posFirst.getZ()) ||
            (axis == EnumFacing.Axis.Y && posNew.getX() == posFirst.getX() && posNew.getZ() == posFirst.getZ()) ||
            (axis == EnumFacing.Axis.Z && posNew.getX() == posFirst.getX() && posNew.getY() == posFirst.getY()))
        {
            return true;
        }

        return false;
    }

    /*
    @Nullable
    private static EnumFacing getPlayerMovementDirection(EntityPlayerSP player)
    {
        double dx = player.posX - playerPosLast.x;
        double dy = player.posY - playerPosLast.y;
        double dz = player.posZ - playerPosLast.z;
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);

        if (Math.max(Math.max(ax, az), ay) < 0.001)
        {
            return null;
        }

        if (ax > az)
        {
            if (ax > ay)
            {
                return dx > 0 ? EnumFacing.EAST : EnumFacing.WEST;
            }
            else
            {
                return dy > 0 ? EnumFacing.UP : EnumFacing.DOWN;
            }
        }
        else
        {
            if (az > ay)
            {
                return dz > 0 ? EnumFacing.SOUTH : EnumFacing.NORTH;
            }
            else
            {
                return dy > 0 ? EnumFacing.UP : EnumFacing.DOWN;
            }
        }
    }
    */

    @Nullable
    private static EnumHand getHandWithItem(ItemStack stack, EntityPlayerSP player)
    {
        if (InventoryUtils.areStacksEqualIgnoreDurability(player.getHeldItemMainhand(), stackFirst))
        {
            return EnumHand.MAIN_HAND;
        }

        if (InventoryUtils.areStacksEqualIgnoreDurability(player.getHeldItemOffhand(), stackFirst))
        {
            return EnumHand.OFF_HAND;
        }

        return null;
    }

    public static boolean shouldSkipSlotSync(int slotNumber, ItemStack newStack)
    {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer player = mc.player;
        Container container = player != null ? player.openContainer : null;

        if (ConfigsGeneric.SLOT_SYNC_WORKAROUND.getBooleanValue() &&
            FeatureToggle.TWEAK_PICK_BEFORE_PLACE.getBooleanValue() == false &&
            container != null && container == player.inventoryContainer &&
            (slotNumber == 45 || (slotNumber - 36) == player.inventory.currentItem))
        {
            boolean featRight = FeatureToggle.TWEAK_FAST_RIGHT_CLICK.getBooleanValue();
            boolean featLeft = FeatureToggle.TWEAK_FAST_LEFT_CLICK.getBooleanValue();

            if ((featRight && mc.gameSettings.keyBindUseItem.isKeyDown()) ||
                (featLeft && mc.gameSettings.keyBindAttack.isKeyDown()))
            {
                return true;
            }
        }

        return false;
    }

    public enum HitPart
    {
        CENTER,
        LEFT,
        RIGHT,
        BOTTOM,
        TOP;
    }

    public enum FastMode implements IConfigOptionListEntry
    {
        PLANE   ("Plane"),
        FACE    ("Face"),
        COLUMN  ("Column");

        private final String displayName;

        private FastMode(String displayName)
        {
            this.displayName = displayName;
        }

        @Override
        public String getStringValue()
        {
            return this.name().toLowerCase();
        }

        @Override
        public String getDisplayName()
        {
            return this.displayName;
        }

        @Override
        public int getOrdinalValue()
        {
            return this.ordinal();
        }

        @Override
        public IConfigOptionListEntry cycle(boolean forward)
        {
            int id = this.ordinal();

            if (forward)
            {
                if (++id >= values().length)
                {
                    id = 0;
                }
            }
            else
            {
                if (--id < 0)
                {
                    id = values().length - 1;
                }
            }

            return values()[id % values().length];
        }

        @Override
        public FastMode fromString(String name)
        {
            return fromStringStatic(name);
        }

        public static FastMode fromStringStatic(String name)
        {
            for (FastMode al : FastMode.values())
            {
                if (al.name().equalsIgnoreCase(name))
                {
                    return al;
                }
            }

            return FastMode.FACE;
        }
    }
}
