package com.lordmau5.ffs.tile;

import buildcraft.api.transport.IPipeConnection;
import buildcraft.api.transport.IPipeTile;
import com.lordmau5.ffs.FancyFluidStorage;
import com.lordmau5.ffs.util.ExtendedBlock;
import com.lordmau5.ffs.util.GenericUtil;
import com.lordmau5.ffs.util.Position3D;
import cpw.mods.fml.common.Optional;
import dan200.computercraft.api.lua.ILuaContext;
import dan200.computercraft.api.lua.LuaException;
import dan200.computercraft.api.peripheral.IComputerAccess;
import dan200.computercraft.api.peripheral.IPeripheral;
import li.cil.oc.api.machine.Arguments;
import li.cil.oc.api.machine.Context;
import li.cil.oc.api.network.ManagedPeripheral;
import li.cil.oc.api.network.SimpleComponent;
import net.minecraft.init.Blocks;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S35PacketUpdateTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.AxisAlignedBB;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Created by Dustin on 28.06.2015.
 */
@Optional.InterfaceList(value = {
        @Optional.Interface(iface = "buildcraft.api.transport.IPipeConnection", modid = "BuildCraftAPI|core"),

        @Optional.Interface(iface = "dan200.computercraft.api.peripheral.IPeripheral", modid = "ComputerCraft"),

        @Optional.Interface(iface = "li.cil.oc.api.network.SimpleComponent", modid = "OpenComputers"),
        @Optional.Interface(iface = "li.cil.oc.api.network.ManagedPeripheral", modid = "OpenComputers")
})
public class TileEntityValve extends TileEntity implements IFluidTank, IFluidHandler,
        IPipeConnection, // BuildCraft
        IPeripheral, // ComputerCraft
        SimpleComponent, ManagedPeripheral // OpenComputers
{

    private final int maxSize = 9;
    private final int mbPerVirtualTank = 16000;

    public boolean isValid;
    private boolean isMaster;
    private boolean initiated;

    public int tankHeight = 0;
    public int valveHeightPosition = 0;
    private boolean autoOutput;

    private ForgeDirection inside = ForgeDirection.UNKNOWN;

    private TileEntityValve master;
    private List<TileEntityTankFrame> tankFrames;
    private List<TileEntityValve> otherValves;

    private Map<Position3D, ExtendedBlock>[] maps;

    /**
     * Length of the inside
     *
     * 0 = Down
     * 1 = Up
     * 2 = North
     * 3 = South
     * 4 = West
     * 5 = East
     */
    private int[] length = new int[6];
    public Position3D bottomDiagFrame, topDiagFrame;

    // TANK LOGIC
    private FluidStack fluidStack;
    private int fluidCapacity;
    // ---------------

    public TileEntityValve() {
        tankFrames = new ArrayList<>();
        otherValves = new ArrayList<>();
    }

    @Override
    public void validate() {
        super.validate();
        initiated = true;
    }

    @Override
    public void updateEntity() {
        if(worldObj.isRemote)
            return;

        if(initiated) {
            initiated = false;

            if(isMaster())
                buildTank(inside);

            return;
        }

        if(!isValid())
            return;

        if(getFluid() == null)
            return;

        if(getAutoOutput()) { // Auto outputs at 50mB/t (1B/s) if enabled
            if (getFluidAmount() != 0) {
                float height = (float) getFluidAmount() / (float) getCapacity() * (float) getTankHeight();
                if (height > (valveHeightPosition - 0.5f)) { // Valves can output until the liquid is at their halfway point.
                    ForgeDirection out = inside.getOpposite();
                    TileEntity tile = worldObj.getTileEntity(xCoord + out.offsetX, yCoord + out.offsetY, zCoord + out.offsetZ);
                    if(tile != null) {
                        int maxAmount = 0;
                        if(tile instanceof TileEntityValve)
                            maxAmount = 1000; // When two tanks are connected by valves, allow faster output
                        else if(tile instanceof IFluidHandler)
                            maxAmount = 50;

                        if(maxAmount != 0) {
                            IFluidHandler handler = (IFluidHandler) tile;
                            FluidStack fillStack = getFluid().copy();
                            fillStack.amount = Math.min(getFluidAmount(), maxAmount);
                            if (handler.fill(inside, fillStack, false) > 0) {
                                drain(handler.fill(inside, fillStack, true), true);
                            }
                        }
                    }
                }
            }
        }

        if(getFluid() != null && getFluid().getFluid() == FluidRegistry.WATER) {
            if(worldObj.isRaining()) {
                int rate = (int) Math.floor(worldObj.rainingStrength * 5 * worldObj.getBiomeGenForCoords(xCoord, zCoord).rainfall);
                if (yCoord == worldObj.getPrecipitationHeight(xCoord, zCoord) - 1) {
                    FluidStack waterStack = getFluid().copy();
                    waterStack.amount = rate * 10;
                    fill(waterStack, true);
                }
            }
        }
    }

    public int getTankHeight() {
        return isMaster() ? tankHeight : getMaster().tankHeight;
    }

    private void setInside(ForgeDirection inside) {
        this.inside = inside;
    }

    public ForgeDirection getInside() {
        return this.inside;
    }

    public void buildTank(ForgeDirection inside) {
        if (worldObj.isRemote)
            return;

        fluidCapacity = 0;
        tankFrames.clear();
        otherValves.clear();

        if(this.inside == ForgeDirection.UNKNOWN)
            setInside(inside);

        if(!calculateInside())
            return;

        if(!setupTank())
            return;

        updateBlockAndNeighbors();
    }

    private boolean calculateInside() {
        int xIn = xCoord + inside.offsetX;
        int yIn = yCoord + inside.offsetY;
        int zIn = zCoord + inside.offsetZ;

        for(ForgeDirection dr : ForgeDirection.VALID_DIRECTIONS) {
            for(int i=0; i<maxSize; i++) {
                if (!worldObj.isAirBlock(xIn + dr.offsetX * i, yIn + dr.offsetY * i, zIn + dr.offsetZ * i)) {
                    length[dr.ordinal()] = i - 1;
                    break;
                }
            }
        }
        return length[0] != -1;
    }

    private void setSlaveValveInside(Map<Position3D, ExtendedBlock> airBlocks, TileEntityValve slave) {
        List<Position3D> possibleAirBlocks = new ArrayList<>();
        for(ForgeDirection dr : ForgeDirection.VALID_DIRECTIONS) {
            if(worldObj.isAirBlock(slave.xCoord + dr.offsetX, slave.yCoord + dr.offsetY, slave.zCoord + dr.offsetZ))
                possibleAirBlocks.add(new Position3D(slave.xCoord + dr.offsetX, slave.yCoord + dr.offsetY, slave.zCoord + dr.offsetZ));
        }

        Position3D insideAir = null;
        for(Position3D pos : possibleAirBlocks) {
            if (airBlocks.containsKey(pos)) {
                insideAir = pos;
                break;
            }
        }

        if(insideAir == null)
            return;

        Position3D dist = insideAir.getDistance(new Position3D(slave.xCoord, slave.yCoord, slave.zCoord));
        for(ForgeDirection dr : ForgeDirection.VALID_DIRECTIONS) {
            if(dist.equals(new Position3D(dr.offsetX, dr.offsetY, dr.offsetZ))) {
                slave.setInside(dr);
                break;
            }
        }
    }

    private void fetchMaps() {
        bottomDiagFrame = new Position3D(xCoord + inside.offsetX + length[ForgeDirection.WEST.ordinal()] * ForgeDirection.WEST.offsetX + ForgeDirection.WEST.offsetX,
                yCoord + inside.offsetY + length[ForgeDirection.DOWN.ordinal()] * ForgeDirection.DOWN.offsetY + ForgeDirection.DOWN.offsetY,
                zCoord + inside.offsetZ + length[ForgeDirection.NORTH.ordinal()] * ForgeDirection.NORTH.offsetZ + ForgeDirection.NORTH.offsetZ);
        topDiagFrame = new Position3D(xCoord + inside.offsetX + length[ForgeDirection.EAST.ordinal()] * ForgeDirection.EAST.offsetX + ForgeDirection.EAST.offsetX,
                yCoord + inside.offsetY + length[ForgeDirection.UP.ordinal()] * ForgeDirection.UP.offsetY + ForgeDirection.UP.offsetY,
                zCoord + inside.offsetZ + length[ForgeDirection.SOUTH.ordinal()] * ForgeDirection.SOUTH.offsetZ + ForgeDirection.SOUTH.offsetZ);

        maps = GenericUtil.getTankFrame(worldObj, bottomDiagFrame, topDiagFrame);
    }

    private boolean setupTank() {
        fetchMaps();

        otherValves = new ArrayList<>();
        tankFrames = new ArrayList<>();

        Position3D pos = new Position3D(xCoord, yCoord, zCoord);
        valveHeightPosition = Math.abs(bottomDiagFrame.getDistance(pos).getY());
        tankHeight = topDiagFrame.getDistance(bottomDiagFrame).getY() - 1;

        ExtendedBlock bottomDiagBlock = new ExtendedBlock(worldObj.getBlock(bottomDiagFrame.getX(), bottomDiagFrame.getY(), bottomDiagFrame.getZ()),
                worldObj.getBlockMetadata(bottomDiagFrame.getX(), bottomDiagFrame.getY(), bottomDiagFrame.getZ()));
        ExtendedBlock topDiagBlock = new ExtendedBlock(worldObj.getBlock(topDiagFrame.getX(), topDiagFrame.getY(), topDiagFrame.getZ()),
                worldObj.getBlockMetadata(topDiagFrame.getX(), topDiagFrame.getY(), topDiagFrame.getZ()));

        if(!bottomDiagBlock.equals(topDiagBlock) || !GenericUtil.isValidTankBlock(worldObj, bottomDiagFrame, bottomDiagBlock))
            return false;

        for(Map.Entry<Position3D, ExtendedBlock> airCheck : maps[2].entrySet()) {
            if(airCheck.getValue().getBlock() != Blocks.air)
                return false;
        }

        fluidCapacity = (maps[0].size() + maps[1].size() + maps[2].size()) * mbPerVirtualTank;

        for(Map.Entry<Position3D, ExtendedBlock> frameCheck : maps[0].entrySet()) {
            if(!frameCheck.getValue().equals(bottomDiagBlock))
                return false;
        }

        List<TileEntityValve> valves = new ArrayList<>();
        for(Map.Entry<Position3D, ExtendedBlock> insideFrameCheck : maps[1].entrySet()) {
            pos = insideFrameCheck.getKey();
            ExtendedBlock check = insideFrameCheck.getValue();
            TileEntity tile = worldObj.getTileEntity(pos.getX(), pos.getY(), pos.getZ());
            if(check.equals(bottomDiagBlock) || GenericUtil.isBlockGlass(check.getBlock(), check.getMetadata()) || tile instanceof TileEntityTankFrame)
                continue;
            if(tile instanceof TileEntityValve) {
                TileEntityValve valve = (TileEntityValve) tile;
                if(valve == this)
                    continue;

                if(valve.isMaster() && valve.getFluid() != null) {
                    this.fluidStack = valve.getFluid();
                    // Make sure we don't overfill a tank. If the new tank is smaller than the old one, excess liquid disappear.
                    this.fluidStack.amount = Math.min(this.fluidStack.amount, this.fluidCapacity);
                }
                valves.add(valve);
                continue;
            }
            return false;
        }

        for(TileEntityValve valve : valves) {
            pos = new Position3D(valve.xCoord, valve.yCoord, valve.zCoord);
            valve.valveHeightPosition = Math.abs(bottomDiagFrame.getDistance(pos).getY());

            valve.isMaster = false;
            valve.setMaster(this);
            setSlaveValveInside(maps[2], valve);
        }
        isMaster = true;

        for(Map.Entry<Position3D, ExtendedBlock> setTiles : maps[0].entrySet()) {
            pos = setTiles.getKey();
            TileEntityTankFrame tankFrame;
            if(setTiles.getValue().getBlock() != FancyFluidStorage.blockTankFrame) {
                tankFrame = new TileEntityTankFrame(this, setTiles.getValue());
                worldObj.setBlock(pos.getX(), pos.getY(), pos.getZ(), FancyFluidStorage.blockTankFrame, setTiles.getValue().getMetadata(), 2);
                worldObj.setTileEntity(pos.getX(), pos.getY(), pos.getZ(), tankFrame);
                tankFrame.markForUpdate();
            }
            else {
                tankFrame = (TileEntityTankFrame) worldObj.getTileEntity(pos.getX(), pos.getY(), pos.getZ());
                tankFrame.setValve(this);
            }
            tankFrames.add(tankFrame);
        }

        for(Map.Entry<Position3D, ExtendedBlock> setTiles : maps[1].entrySet()) {
            pos = setTiles.getKey();
            TileEntity tile = worldObj.getTileEntity(pos.getX(), pos.getY(), pos.getZ());
            if(tile != null) {
                if(tile instanceof TileEntityValve && tile != this)
                    otherValves.add((TileEntityValve) tile);

                if(tile instanceof TileEntityTankFrame) {
                    ((TileEntityTankFrame) tile).setValve(this);
                    tankFrames.add((TileEntityTankFrame) tile);
                }
            }
            else {
                TileEntityTankFrame tankFrame = new TileEntityTankFrame(this, setTiles.getValue());
                worldObj.setBlock(pos.getX(), pos.getY(), pos.getZ(), FancyFluidStorage.blockTankFrame, setTiles.getValue().getMetadata(), 2);
                worldObj.setTileEntity(pos.getX(), pos.getY(), pos.getZ(), tankFrame);
                tankFrame.markForUpdate();
                tankFrames.add(tankFrame);
            }
        }

        isValid = true;
        return true;
    }

    public void breakTank(TileEntity frame) {
        if (worldObj.isRemote)
            return;

        if(!isMaster()) {
            if(getMaster() != this)
                getMaster().breakTank(frame);

            return;
        }

        for(TileEntityValve valve : otherValves) {
            valve.fluidStack = getFluid();
            valve.master = null;
            valve.isValid = false;
            valve.markForUpdate();
        }

        for(TileEntityTankFrame tankFrame : tankFrames) {
            if(frame == tankFrame)
                continue;

            ExtendedBlock block = tankFrame.getBlock();
            Position3D pos = new Position3D(tankFrame.xCoord, tankFrame.yCoord, tankFrame.zCoord);
            if(block == null || worldObj.isAirBlock(pos.getX(), pos.getY(), pos.getZ()))
                continue;

            worldObj.removeTileEntity(tankFrame.xCoord, tankFrame.yCoord, tankFrame.zCoord);
            worldObj.setBlock(pos.getX(), pos.getY(), pos.getZ(), block.getBlock(), block.getMetadata(), 2);
        }

        isValid = false;

        this.updateBlockAndNeighbors();

        otherValves = new ArrayList<>();
        tankFrames = new ArrayList<>();
    }

    public boolean isValid() {
        return isValid;
    }

    private void updateBlockAndNeighbors() {
        if(worldObj.isRemote)
            return;

        this.markForUpdate();

        if(otherValves != null) {
            for(TileEntityValve otherValve : otherValves) {
                otherValve.isValid = isValid;
                otherValve.markForUpdate();
            }
        }

        ForgeDirection outside = getInside().getOpposite();
        TileEntity outsideTile = worldObj.getTileEntity(xCoord + outside.offsetX, yCoord + outside.offsetY, zCoord + outside.offsetZ);
        if (outsideTile != null && outsideTile instanceof IPipeTile) {
            ((IPipeTile) outsideTile).scheduleNeighborChange();
        }
    }

    public boolean isMaster() {
        return isMaster;
    }

    public TileEntityValve getMaster() {
        return master == null ? this : master;
    }

    public void setMaster(TileEntityValve master) {
        this.master = master;
    }

    public boolean getAutoOutput() {
        return isValid() && (isMaster() ? this.autoOutput : getMaster().getAutoOutput());

    }

    public void setAutoOutput(boolean autoOutput) {
        if(!isMaster()) {
            getMaster().setAutoOutput(autoOutput);
            return;
        }

        this.autoOutput = autoOutput;
        updateBlockAndNeighbors();
    }

    @Override
    public void readFromNBT(NBTTagCompound tag) {
        super.readFromNBT(tag);

        isValid = tag.getBoolean("isValid");
        inside = ForgeDirection.getOrientation(tag.getInteger("inside"));

        isMaster = tag.getBoolean("master");
        if(isMaster()) {
            if(tag.getBoolean("hasFluid")) {
                fluidStack = new FluidStack(FluidRegistry.getFluid(tag.getInteger("fluidID")), tag.getInteger("fluidAmount"));
                fluidCapacity = tag.getInteger("fluidCapacity");
            }
            else {
                fluidStack = null;
            }

            autoOutput = tag.getBoolean("autoOutput");
            tankHeight = tag.getInteger("tankHeight");
        }

        if(tag.hasKey("bottomDiagF")) {
            int[] bottomDiagF = tag.getIntArray("bottomDiagF");
            int[] topDiagF = tag.getIntArray("topDiagF");
            bottomDiagFrame = new Position3D(bottomDiagF[0], bottomDiagF[1], bottomDiagF[2]);
            topDiagFrame = new Position3D(topDiagF[0], topDiagF[1], topDiagF[2]);
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound tag) {
        tag.setBoolean("isValid", isValid);
        tag.setInteger("inside", inside.ordinal());

        tag.setBoolean("master", isMaster());
        if(isMaster()) {
            tag.setBoolean("hasFluid", fluidStack != null);
            if(fluidStack != null) {
                tag.setInteger("fluidID", fluidStack.getFluidID());
                tag.setInteger("fluidAmount", fluidStack.amount);
                tag.setInteger("fluidCapacity", fluidCapacity);
            }

            tag.setBoolean("autoOutput", autoOutput);
            tag.setInteger("tankHeight", tankHeight);
        }

        if(bottomDiagFrame != null && topDiagFrame != null) {
            tag.setIntArray("bottomDiagF", new int[]{bottomDiagFrame.getX(), bottomDiagFrame.getY(), bottomDiagFrame.getZ()});
            tag.setIntArray("topDiagF", new int[]{topDiagFrame.getX(), topDiagFrame.getY(), topDiagFrame.getZ()});
        }

        super.writeToNBT(tag);
    }

    @Override
    public void onDataPacket(NetworkManager net, S35PacketUpdateTileEntity pkt) {
        readFromNBT(pkt.func_148857_g());

        if ((!isMaster() || master == null) && pkt.func_148857_g().hasKey("masterValve")) {
            int[] masterCoords = pkt.func_148857_g().getIntArray("masterValve");
            TileEntity tile = worldObj.getTileEntity(masterCoords[0], masterCoords[1], masterCoords[2]);
            if(tile != null && tile instanceof TileEntityValve) {
                master = (TileEntityValve) tile;
            }
        }

         markForUpdate();
    }

    @Override
    public Packet getDescriptionPacket() {
        NBTTagCompound tag = new NBTTagCompound();
        writeToNBT(tag);

        if (!isMaster() && master != null) {
            tag.setIntArray("masterValve", new int[] {master.xCoord, master.yCoord, master.zCoord});
        }

        return new S35PacketUpdateTileEntity(xCoord, yCoord, zCoord, 0, tag);
    }

    private void markForUpdate() {
        if (!worldObj.isRemote) {
            for (TileEntityValve valve : otherValves)
                worldObj.markBlockForUpdate(valve.xCoord, valve.yCoord, valve.zCoord);
            for (TileEntityTankFrame frame : tankFrames)
                worldObj.markBlockForUpdate(frame.xCoord, frame.yCoord, frame.zCoord);
            worldObj.markBlockForUpdate(xCoord, yCoord, zCoord);
        }
    }

    @Override
    public AxisAlignedBB getRenderBoundingBox() {
        if(bottomDiagFrame == null || topDiagFrame == null)
            return super.getRenderBoundingBox();

        return AxisAlignedBB.getBoundingBox(bottomDiagFrame.getX(), bottomDiagFrame.getY(), bottomDiagFrame.getZ(), topDiagFrame.getX(), topDiagFrame.getY(), topDiagFrame.getZ());
    }

    // Tank logic!

    @Override
    public FluidStack getFluid() {
        if(!isValid())
            return null;

        return getMaster() == this ? fluidStack : getMaster().fluidStack;
    }

    @Override
    public int getFluidAmount() {
        if(!isValid() || getFluid() == null)
            return 0;

        return getFluid().amount;
    }

    @Override
    public int getCapacity() {
        if(!isValid())
            return 0;

        return getMaster() == this ? fluidCapacity : getMaster().fluidCapacity;
    }

    @Override
    public FluidTankInfo getInfo() {
        if(!isValid())
            return null;

        return new FluidTankInfo(getMaster());
    }

    @Override
    public int fill(FluidStack resource, boolean doFill) {
        if(getMaster() == this) {
            if(!isValid() || fluidStack != null && !fluidStack.isFluidEqual(resource))
                return 0;

            int possibleAmount = resource.amount;
            if(fluidStack != null)
                possibleAmount += getFluid().amount;

            int rest = resource.amount;
            if(possibleAmount > fluidCapacity) {
                rest = possibleAmount - fluidCapacity;
                possibleAmount = fluidCapacity;
            }

            if(doFill) {
                if (fluidStack == null)
                    fluidStack = resource;
                fluidStack.amount = possibleAmount;

                updateBlockAndNeighbors();
            }

            return rest;
        }
        else
            return getMaster().fill(resource, doFill);
    }

    @Override
    public FluidStack drain(int maxDrain, boolean doDrain) {
        if(getMaster() == this) {
            if(!isValid() || fluidStack == null)
                return null;

            int possibleAmount = fluidStack.amount - maxDrain;

            int drained = maxDrain;
            if(possibleAmount < 0) {
                drained += possibleAmount;
                possibleAmount = 0;
            }

            FluidStack returnStack = new FluidStack(fluidStack, drained);

            if(doDrain) {
                fluidStack.amount = possibleAmount;
                if (possibleAmount == 0)
                    fluidStack = null;

                updateBlockAndNeighbors();
            }

            return returnStack;
        }
        else
            return getMaster().drain(maxDrain, doDrain);
    }

    // IFluidHandler

    @Override
    public int fill(ForgeDirection from, FluidStack resource, boolean doFill) {
        return getMaster() == this ? fill(resource, doFill) : getMaster().fill(resource, doFill);
    }

    @Override
    public FluidStack drain(ForgeDirection from, FluidStack resource, boolean doDrain) {
        return getMaster() == this ? drain(resource.amount, doDrain) : getMaster().drain(resource.amount, doDrain);
    }

    @Override
    public FluidStack drain(ForgeDirection from, int maxDrain, boolean doDrain) {
        return getMaster() == this ? drain(maxDrain, doDrain) : getMaster().drain(maxDrain, doDrain);
    }

    @Override
    public boolean canFill(ForgeDirection from, Fluid fluid) {
        return isValid() && !(fluidStack != null && fluidStack.getFluid() != fluid);

    }

    @Override
    public boolean canDrain(ForgeDirection from, Fluid fluid) {
        return isValid() && !(fluidStack == null || fluidStack.getFluid() != fluid);

    }

    @Override
    public FluidTankInfo[] getTankInfo(ForgeDirection from) {
        if(!isValid())
            return null;

        return isMaster() ? new FluidTankInfo[]{ getInfo() } : getMaster().getTankInfo(from);
    }

    @Optional.Method(modid = "BuildCraftAPI|core")
    @Override
    public ConnectOverride overridePipeConnection(IPipeTile.PipeType pipeType, ForgeDirection from) {
        if(!isValid())
            return ConnectOverride.DISCONNECT;

        return ConnectOverride.CONNECT;
    }

    public String[] methodNames() {
        return new String[]{"getFluidName", "getFluidAmount", "getFluidCapacity", "setAutoOutput", "doesAutoOutput"};
    }

    @Optional.Method(modid = "ComputerCraft")
    @Override
    public String getType() {
        return "exTanksValve";
    }

    @Optional.Method(modid = "ComputerCraft")
    @Override
    public String[] getMethodNames() {
        return methodNames();
    }

    @Optional.Method(modid = "ComputerCraft")
    @Override
    public Object[] callMethod(IComputerAccess computer, ILuaContext context, int method, Object[] arguments) throws LuaException, InterruptedException {
        switch(method) {
            case 0: { // getFluidName
                if(this.getFluid() == null)
                    return null;
                return new Object[]{this.getFluid().getLocalizedName()};
            }
            case 1: { // getFluidAmount
                return new Object[]{this.getFluidAmount()};
            }
            case 2: { // getFluidCapacity
                return new Object[]{this.getCapacity()};
            }
            case 3: { // setAutoOutput
                if(arguments.length == 0) {
                    arguments = new Object[]{!this.getAutoOutput()};
                }
                if(!(arguments[0] instanceof Boolean)) {
                    throw new LuaException("expected argument 1 to be of type \"boolean\", found \"" + arguments[0].getClass().getSimpleName() + "\"");
                }
                this.setAutoOutput((boolean) arguments[0]);
                return new Object[]{this.getAutoOutput()};
            }
            case 4: { // doesAutoOutput
                return new Object[]{this.getAutoOutput()};
            }
            default:
        }
        return null;
    }

    @Optional.Method(modid = "ComputerCraft")
    @Override
    public void attach(IComputerAccess computer) {

    }

    @Optional.Method(modid = "ComputerCraft")
    @Override
    public void detach(IComputerAccess computer) {

    }

    @Optional.Method(modid = "ComputerCraft")
    @Override
    public boolean equals(IPeripheral other) {
        return false;
    }

    @Optional.Method(modid = "OpenComputers")
    @Override
    public String getComponentName() {
        return "extanks_valve";
    }

    @Optional.Method(modid = "OpenComputers")
    @Override
    public String[] methods() {
        return methodNames();
    }

    @Optional.Method(modid = "OpenComputers")
    @Override
    public Object[] invoke(String method, Context context, Arguments args) throws Exception {
        switch(method) {
            case "getFluidName": { // getFluidName
                if(this.getFluid() == null)
                    return null;
                return new Object[]{this.getFluid().getLocalizedName()};
            }
            case "getFluidAmount": { // getFluidAmount
                return new Object[]{this.getFluidAmount()};
            }
            case "getFluidCapacity": { // getCapacity
                return new Object[]{this.getCapacity()};
            }
            case "setAutoOutput": { // setAutoOutput
                this.setAutoOutput(args.optBoolean(0, !this.getAutoOutput()));
                return new Object[]{this.getAutoOutput()};
            }
            case "doesAutoOutput": { // doesAutoOutput
                return new Object[]{this.getAutoOutput()};
            }
            default:
        }
        return null;
    }
}