/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.movement;

import com.google.common.collect.Streams;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.render.RenderUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.FallingBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Scaffold extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Selected blocks.")
        .build()
    );

    private final Setting<ListMode> blocksFilter = sgGeneral.add(new EnumSetting.Builder<ListMode>()
        .name("blocks-filter")
        .description("How to use the block list setting")
        .defaultValue(ListMode.Blacklist)
        .build()
    );

    private final Setting<Boolean> fastTower = sgGeneral.add(new BoolSetting.Builder()
        .name("fast-tower")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> whileMoving = sgGeneral.add(new BoolSetting.Builder()
        .name("while-moving")
        .defaultValue(false)
        .visible(fastTower::get)
        .build()
    );

    private final Setting<Boolean> onlyOnClick = sgGeneral.add(new BoolSetting.Builder()
        .name("only-on-click")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> renderSwing = sgGeneral.add(new BoolSetting.Builder()
        .name("swing")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoSwitch = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-switch")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> airPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("air-place")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> aheadDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("ahead-distance")
        .defaultValue(0)
        .min(0)
        .sliderMax(1)
        .visible(() -> !airPlace.get())
        .build()
    );

    private final Setting<Double> placeRange = sgGeneral.add(new DoubleSetting.Builder()
        .name("closest-block-range")
        .defaultValue(4)
        .min(0)
        .sliderMax(8)
        .visible(() -> !airPlace.get())
        .build()
    );

    private final Setting<Double> radius = sgGeneral.add(new DoubleSetting.Builder()
        .name("radius")
        .defaultValue(0)
        .min(0)
        .max(6)
        .visible(airPlace::get)
        .build()
    );

    private final Setting<Integer> blocksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("blocks-per-tick")
        .defaultValue(3)
        .min(1)
        .visible(airPlace::get)
        .build()
    );

    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .defaultValue(ShapeMode.Both)
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(197, 137, 232, 10))
        .visible(render::get)
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(197, 137, 232))
        .visible(render::get)
        .build()
    );

    private final BlockPos.Mutable bp = new BlockPos.Mutable();

    private final List<BlockPos> queue = new ArrayList<>();

    public Scaffold() {
        super(Categories.Movement, "scaffold", "Automatically places blocks under you.");
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (onlyOnClick.get() && !mc.options.useKey.isPressed()) return;
        if (mc.player == null || mc.world == null) return;

        Vec3d vec = mc.player.getEntityPos().add(mc.player.getVelocity()).add(0, -0.75, 0);

        if (airPlace.get()) {
            bp.set(vec.getX(), vec.getY(), vec.getZ());
        } else {
            Vec3d pos = mc.player.getEntityPos();

            if (aheadDistance.get() != 0 && !towering()) {
                Vec3d dir = Vec3d.fromPolar(0, mc.player.getYaw()).multiply(aheadDistance.get(), 0, aheadDistance.get());

                if (mc.options.forwardKey.isPressed()) pos = pos.add(dir.x, 0, dir.z);
                if (mc.options.backKey.isPressed()) pos = pos.add(-dir.x, 0, -dir.z);
                if (mc.options.leftKey.isPressed()) pos = pos.add(dir.z, 0, -dir.x);
                if (mc.options.rightKey.isPressed()) pos = pos.add(-dir.z, 0, dir.x);
            }

            bp.set(pos.x, vec.y, pos.z);
        }

        BlockPos targetBlock = bp.toImmutable();

        if (airPlace.get()) {
            List<BlockPos> blocks = new ArrayList<>();

            for (int x = (int)(bp.getX() - radius.get()); x <= bp.getX() + radius.get(); x++) {
                for (int z = (int)(bp.getZ() - radius.get()); z <= bp.getZ() + radius.get(); z++) {
                    BlockPos blockPos = BlockPos.ofFloored(x, bp.getY(), z);
                    blocks.add(blockPos);
                }
            }

            blocks.sort(Comparator.comparingDouble(PlayerUtils::squaredDistanceTo));

            int counter = 0;
            for (BlockPos block : blocks) {
                if (place(block)) counter++;
                if (counter >= blocksPerTick.get()) break;
            }

        } else {
            queue.add(bp.toImmutable());
        }

        handleTower();
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;

        for (BlockPos pos : queue) {
            place(pos);
        }
        queue.clear();
    }

    private void handleTower() {
        if (!fastTower.get()) return;
        if (!mc.options.jumpKey.isPressed()) return;
        if (mc.options.sneakKey.isPressed()) return;

        FindItemResult result = InvUtils.findInHotbar(itemStack -> validItem(itemStack, bp));
        if (!result.found()) return;

        Vec3d velocity = mc.player.getVelocity();
        Box box = mc.player.getBoundingBox();

        if (Streams.stream(mc.world.getBlockCollisions(mc.player, box.offset(0, 1, 0))).toList().isEmpty()) {
            if (whileMoving.get() || !PlayerUtils.isMoving()) {
                mc.player.setVelocity(velocity.x, 0.5, velocity.z);
            }
        } else {
            mc.player.setVelocity(velocity.x, Math.ceil(mc.player.getY()) - mc.player.getY(), velocity.z);
            mc.player.setOnGround(true);
        }
    }

    public boolean scaffolding() {
        return isActive() && (!onlyOnClick.get() || mc.options.useKey.isPressed());
    }

    public boolean towering() {
        return scaffolding() && fastTower.get() && mc.options.jumpKey.isPressed();
    }

    private boolean validItem(ItemStack stack, BlockPos pos) {
        if (!(stack.getItem() instanceof BlockItem)) return false;

        Block block = ((BlockItem) stack.getItem()).getBlock();

        if (blocksFilter.get() == ListMode.Blacklist && blocks.get().contains(block)) return false;
        if (blocksFilter.get() == ListMode.Whitelist && !blocks.get().contains(block)) return false;

        if (!(block instanceof FallingBlock)) return true;
        return !FallingBlock.canFallThrough(mc.world.getBlockState(pos));
    }

    private boolean place(BlockPos pos) {
        FindItemResult item = InvUtils.findInHotbar(stack -> validItem(stack, pos));
        if (!item.found()) return false;

        if (BlockUtils.place(pos, item, rotate.get(), 50, renderSwing.get(), true)) {
            if (render.get()) {
                RenderUtils.renderTickingBlock(
                    pos,
                    sideColor.get(),
                    lineColor.get(),
                    shapeMode.get(),
                    0, 8,
                    true, false
                );
            }
            return true;
        }
        return false;
    }

    public enum ListMode {
        Whitelist,
        Blacklist
    }
}
