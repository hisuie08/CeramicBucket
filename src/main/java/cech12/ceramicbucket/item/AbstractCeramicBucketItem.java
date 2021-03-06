package cech12.ceramicbucket.item;

import cech12.ceramicbucket.util.CeramicBucketUtils;
import net.minecraft.advancements.CriteriaTriggers;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CauldronBlock;
import net.minecraft.block.IBucketPickupHandler;
import net.minecraft.block.ILiquidContainer;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.fluid.FlowingFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.BucketItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.stats.Stats;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.BlockRayTraceResult;
import net.minecraft.util.math.RayTraceContext;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.FluidAttributes;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.FluidUtil;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.fluids.capability.templates.FluidHandlerItemStack;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.function.Supplier;

public abstract class AbstractCeramicBucketItem extends BucketItem {

    public AbstractCeramicBucketItem(Supplier<? extends Fluid> supplier, Properties builder) {
        super(supplier, builder);
    }

    @Nonnull
    abstract FluidHandlerItemStack getNewFluidHandlerInstance(@Nonnull ItemStack stack);

    @Override
    public ICapabilityProvider initCapabilities(@Nonnull ItemStack stack, @Nullable CompoundNBT nbt) {
        return new ICapabilityProvider() {
            @Nonnull
            @Override
            public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
                return cap == CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY ?
                        (LazyOptional<T>) LazyOptional.of(() -> getNewFluidHandlerInstance(stack))
                        : LazyOptional.empty();
            }
        };
    }

    @Override
    @Nonnull
    public ActionResult<ItemStack> onItemRightClick(@Nonnull World worldIn, PlayerEntity playerIn, @Nonnull Hand handIn) {
        ItemStack itemstack = playerIn.getHeldItem(handIn);
        RayTraceResult raytraceresult = rayTrace(worldIn, playerIn, this.getFluid(itemstack) == Fluids.EMPTY ? RayTraceContext.FluidMode.SOURCE_ONLY : RayTraceContext.FluidMode.NONE);
        ActionResult<ItemStack> ret = net.minecraftforge.event.ForgeEventFactory.onBucketUse(playerIn, worldIn, itemstack, raytraceresult);
        if (ret != null) return ret;
        if (raytraceresult.getType() == RayTraceResult.Type.MISS) {
            return new ActionResult<>(ActionResultType.PASS, itemstack);
        } else if (raytraceresult.getType() != RayTraceResult.Type.BLOCK) {
            return new ActionResult<>(ActionResultType.PASS, itemstack);
        } else {
            BlockRayTraceResult blockraytraceresult = (BlockRayTraceResult) raytraceresult;
            BlockPos blockpos = blockraytraceresult.getPos();
            if (worldIn.isBlockModifiable(playerIn, blockpos) && playerIn.canPlayerEdit(blockpos, blockraytraceresult.getFace(), itemstack)) {
                if (this.getFluid(itemstack) == Fluids.EMPTY) {
                    BlockState blockstate1 = worldIn.getBlockState(blockpos);
                    if (blockstate1.getBlock() instanceof IBucketPickupHandler) {
                        Fluid fluid = ((IBucketPickupHandler) blockstate1.getBlock()).pickupFluid(worldIn, blockpos, blockstate1);
                        if (fluid != Fluids.EMPTY) {
                            playerIn.addStat(Stats.ITEM_USED.get(this));

                            SoundEvent soundevent = this.getFluid(itemstack).getAttributes().getFillSound();
                            if (soundevent == null) soundevent = fluid.isIn(FluidTags.LAVA) ? SoundEvents.ITEM_BUCKET_FILL_LAVA : SoundEvents.ITEM_BUCKET_FILL;
                            playerIn.playSound(soundevent, 1.0F, 1.0F);
                            ItemStack itemstack1 = this.fillBucket(itemstack, playerIn, fluid);
                            if (!worldIn.isRemote) {
                                CriteriaTriggers.FILLED_BUCKET.trigger((ServerPlayerEntity) playerIn, new ItemStack(fluid.getFilledBucket()));
                            }

                            return new ActionResult<>(ActionResultType.SUCCESS, itemstack1);
                        }
                    }

                }
                BlockState blockstate = worldIn.getBlockState(blockpos);

                //support cauldron interaction
                if (blockstate.getBlock() == Blocks.CAULDRON) {
                    Fluid fluid = this.getFluid(itemstack);
                    CauldronBlock cauldron = (CauldronBlock) blockstate.getBlock();
                    int level = blockstate.get(CauldronBlock.LEVEL);
                    if (fluid.isIn(FluidTags.WATER) && !(itemstack.getItem() instanceof CeramicFishBucketItem)) {
                        if (level < 3) {
                            itemstack = this.emptyBucket(itemstack, playerIn);
                            if (!worldIn.isRemote) {
                                playerIn.addStat(Stats.FILL_CAULDRON);
                                cauldron.setWaterLevel(worldIn, blockpos, blockstate, 3);
                                this.playEmptySound(playerIn, worldIn, blockpos);
                            }
                        }
                        return new ActionResult<>(ActionResultType.SUCCESS, itemstack);
                    } else if (fluid == Fluids.EMPTY) {
                        if (level == 3) {
                            itemstack = this.fillBucket(itemstack, playerIn, Fluids.WATER);
                            if (!worldIn.isRemote) {
                                playerIn.addStat(Stats.USE_CAULDRON);
                                cauldron.setWaterLevel(worldIn, blockpos, blockstate, 0);
                                worldIn.playSound(null, blockpos, SoundEvents.ITEM_BUCKET_FILL, SoundCategory.BLOCKS, 1.0F, 1.0F);
                            }
                        }
                        return new ActionResult<>(ActionResultType.SUCCESS, itemstack);
                    }
                }

                BlockPos blockpos1 = canBlockContainFluid(worldIn, blockpos, blockstate, itemstack) ? blockpos : blockraytraceresult.getPos().offset(blockraytraceresult.getFace());
                if (this.tryPlaceContainedLiquid(playerIn, worldIn, blockpos1, blockraytraceresult, itemstack)) {
                    this.onLiquidPlaced(worldIn, itemstack, blockpos1);
                    if (playerIn instanceof ServerPlayerEntity) {
                        CriteriaTriggers.PLACED_BLOCK.trigger((ServerPlayerEntity) playerIn, blockpos1, itemstack);
                    }

                    playerIn.addStat(Stats.ITEM_USED.get(this));
                    return new ActionResult<>(ActionResultType.SUCCESS, this.emptyBucket(itemstack, playerIn));
                } else {
                    return new ActionResult<>(ActionResultType.FAIL, itemstack);
                }
            } else {
                return new ActionResult<>(ActionResultType.FAIL, itemstack);
            }
        }
    }

    private ItemStack fillBucket(ItemStack stack, PlayerEntity player, Fluid fluid) {
        if (player == null || !player.abilities.isCreativeMode) {
            if (stack.getCount() > 1) {
                stack.shrink(1);
                ItemStack newStack = CeramicBucketUtils.getFilledCeramicBucket(fluid);
                if (player != null && !player.inventory.addItemStackToInventory(newStack)) {
                    player.dropItem(newStack, false);
                }
                //old stack must be returned
            } else {
                return fill(stack, new FluidStack(fluid, FluidAttributes.BUCKET_VOLUME));
            }
        }
        return stack;
    }

    @Override
    @Nonnull
    public ItemStack emptyBucket(@Nonnull ItemStack stack, PlayerEntity player) {
        if (player == null || !player.abilities.isCreativeMode) {
            return drain(stack, FluidAttributes.BUCKET_VOLUME);
        }
        return stack;
    }

    @Deprecated
    @Override
    public boolean tryPlaceContainedLiquid(@Nullable PlayerEntity player, @Nonnull World worldIn, @Nonnull BlockPos posIn, @Nullable BlockRayTraceResult raytrace) {
        return false;
    }

    public boolean tryPlaceContainedLiquid(@Nullable PlayerEntity player, World worldIn, BlockPos posIn, @Nullable BlockRayTraceResult raytrace, ItemStack stack) {
        Fluid fluid = this.getFluid(stack);
        FluidAttributes fluidAttributes = fluid.getAttributes();
        if (!(fluid instanceof FlowingFluid)) {
            return false;
        } else if (!fluidAttributes.canBePlacedInWorld(worldIn, posIn, fluid.getDefaultState())) {
            return false;
        } else {
            BlockState blockstate = worldIn.getBlockState(posIn);
            Material material = blockstate.getMaterial();
            boolean flag = !material.isSolid();
            boolean flag1 = material.isReplaceable();
            boolean canContainFluid = canBlockContainFluid(worldIn, posIn, blockstate, stack);
            if (worldIn.isAirBlock(posIn) || flag || flag1 || canContainFluid) {
                IFluidHandlerItem fluidHandler = FluidUtil.getFluidHandler(stack).orElse(null);
                FluidStack fluidStack = fluidHandler != null ? fluidHandler.drain(FluidAttributes.BUCKET_VOLUME, IFluidHandler.FluidAction.EXECUTE) : null;
                //worldIn.dimension.doesWaterVaporize()
                if (fluidStack != null && worldIn.func_230315_m_().func_236040_e_() && this.getFluid(stack).isIn(FluidTags.WATER)) {
                    fluidAttributes.vaporize(player, worldIn, posIn, fluidStack);
                } else if (canContainFluid) {
                    if (((ILiquidContainer) blockstate.getBlock()).receiveFluid(worldIn, posIn, blockstate, ((FlowingFluid) fluid).getStillFluidState(false))) {
                        this.playEmptySound(player, worldIn, posIn);
                    }
                } else {
                    if (!worldIn.isRemote && (flag || flag1) && !material.isLiquid()) {
                        worldIn.destroyBlock(posIn, true);
                    }

                    this.playEmptySound(player, worldIn, posIn);
                    worldIn.setBlockState(posIn, fluid.getDefaultState().getBlockState(), 11);
                }

                return true;
            } else {
                return raytrace != null && this.tryPlaceContainedLiquid(player, worldIn, raytrace.getPos().offset(raytrace.getFace()), null, stack);
            }
        }
    }

    @Deprecated
    @Override
    @Nonnull
    public Fluid getFluid() {
        return Fluids.EMPTY;
    }

    public Fluid getFluid(ItemStack stack) {
        final LazyOptional<IFluidHandlerItem> cap = stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY);
        if (cap.isPresent()) {
            final FluidHandlerItemStack fluidHandler = (FluidHandlerItemStack) cap.orElseThrow(NullPointerException::new);
            return fluidHandler.getFluid().getFluid();
        }
        return Fluids.EMPTY;
    }

    public ItemStack fill(ItemStack stack, FluidStack fluidStack) {
        final LazyOptional<IFluidHandlerItem> cap = stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY);
        if (cap.isPresent()) {
            final FluidHandlerItemStack fluidHandler = (FluidHandlerItemStack) cap.orElseThrow(NullPointerException::new);
            fluidHandler.fill(fluidStack, IFluidHandler.FluidAction.EXECUTE);
            return fluidHandler.getContainer();
        }
        return stack;
    }

    public ItemStack drain(ItemStack stack, int drainAmount) {
        final LazyOptional<IFluidHandlerItem> cap = stack.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY);
        if (cap.isPresent()) {
            final FluidHandlerItemStack fluidHandler = (FluidHandlerItemStack) cap.orElseThrow(NullPointerException::new);
            fluidHandler.drain(drainAmount, IFluidHandler.FluidAction.EXECUTE);
            return fluidHandler.getContainer();
        }
        return stack;
    }

    private boolean canBlockContainFluid(World worldIn, BlockPos posIn, BlockState blockstate, ItemStack itemStack) {
        return blockstate.getBlock() instanceof ILiquidContainer && ((ILiquidContainer)blockstate.getBlock()).canContainFluid(worldIn, posIn, blockstate, this.getFluid(itemStack));
    }
}
