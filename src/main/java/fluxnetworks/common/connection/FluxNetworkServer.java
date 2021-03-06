package fluxnetworks.common.connection;

import fluxnetworks.FluxConfig;
import fluxnetworks.api.network.*;
import fluxnetworks.api.utils.Capabilities;
import fluxnetworks.api.utils.EnergyType;
import fluxnetworks.api.tiles.IFluxConnector;
import fluxnetworks.api.tiles.IFluxPlug;
import fluxnetworks.api.tiles.IFluxPoint;
import fluxnetworks.common.event.FluxConnectionEvent;
import fluxnetworks.common.core.FluxUtils;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.common.MinecraftForge;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

/**
 * Flux Network.
 */
public class FluxNetworkServer extends FluxNetworkBase {

    public HashMap<FluxCacheTypes, List<IFluxConnector>> connections = new HashMap<>();
    public Queue<IFluxConnector> toAdd = new ConcurrentLinkedQueue<>();
    public Queue<IFluxConnector> toRemove = new ConcurrentLinkedQueue<>();

    public boolean sortConnections = true;

    public List<PriorityGroup<IFluxPlug>> sortedPlugs = new ArrayList<>();
    public List<PriorityGroup<IFluxPoint>> sortedPoints = new ArrayList<>();

    private TransferIterator<IFluxPlug> plugTransferIterator = new TransferIterator<>();
    private TransferIterator<IFluxPoint> pointTransferIterator = new TransferIterator<>();

    public long bufferLimiter = 0;

    public FluxNetworkServer() {
        super();
    }

    public FluxNetworkServer(int id, String name, EnumSecurityType security, int color, UUID owner, EnergyType energy, String password) {
        super(id, name, security, color, owner, energy, password);
    }

    @SuppressWarnings("unchecked")
    public void addConnections() {
        if(toAdd.isEmpty()) {
            return;
        }
        Iterator<IFluxConnector> iterator = toAdd.iterator();
        while(iterator.hasNext()) {
            IFluxConnector flux = iterator.next();
            FluxCacheTypes.getValidTypes(flux).forEach(t -> FluxUtils.addWithCheck(getConnections(t), flux));
            MinecraftForge.EVENT_BUS.post(new FluxConnectionEvent.Connected(flux, this));
            iterator.remove();
            sortConnections = true;
        }
    }

    @SuppressWarnings("unchecked")
    public void removeConnections() {
        if(toRemove.isEmpty()) {
            return;
        }
        Iterator<IFluxConnector> iterator = toRemove.iterator();
        while(iterator.hasNext()) {
            IFluxConnector flux = iterator.next();
            FluxCacheTypes.getValidTypes(flux).forEach(t -> ((List<IFluxConnector>) getConnections(t)).removeIf(f -> f == flux));
            iterator.remove();
            sortConnections = true;
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T extends IFluxConnector> List<T> getConnections(FluxCacheTypes<T> type) {
        return (List<T>) connections.computeIfAbsent(type, m -> new ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onEndServerTick() {
        network_stats.getValue().onStartServerTick();
        network_stats.getValue().startProfiling();
        addConnections();
        removeConnections();
        if(sortConnections) {
            sortConnections();
            sortConnections = false;
        }
        bufferLimiter = 0;
        if(!sortedPoints.isEmpty()) {
            sortedPoints.forEach(g -> g.getConnectors().forEach(p -> bufferLimiter += p.getTransferHandler().removeFromNetwork(Long.MAX_VALUE, true)));
            if (bufferLimiter > 0 && !sortedPlugs.isEmpty()) {
                pointTransferIterator.reset(sortedPoints, true);
                plugTransferIterator.reset(sortedPlugs, false);
                CYCLE:
                while (pointTransferIterator.hasNext()) {
                    while (plugTransferIterator.hasNext()) {
                        IFluxPlug plug = plugTransferIterator.getCurrentFlux();
                        IFluxPoint point = pointTransferIterator.getCurrentFlux();
                        if(plug.getConnectionType() == point.getConnectionType()) {
                            break CYCLE; // Storage always have the lowest priority, the cycle can be broken here.
                        }
                        long operate = Math.min(plug.getTransferHandler().getBuffer(), point.getTransferHandler().getRequest());
                        long removed = point.getTransferHandler().removeFromNetwork(operate, false);
                        if(removed > 0) {
                            plug.getTransferHandler().addToNetwork(removed);
                            if (point.getTransferHandler().getRequest() <= 0) {
                                continue CYCLE;
                            }
                        } else {
                            // If we can only transfer 3RF, it returns 0 (3RF < 1EU), but this plug still need transfer (3RF > 0), and can't afford current point,
                            // So manually increment plug to prevent dead loop. (hasNext detect if it need transfer)
                            if(plug.getTransferHandler().getBuffer() < 4) {
                                plugTransferIterator.incrementFlux();
                            } else {
                                pointTransferIterator.incrementFlux();
                                continue CYCLE;
                            }
                        }
                    }
                    break; //all plugs have been used
                }
            }
        }
        List<IFluxConnector> fluxConnectors = getConnections(FluxCacheTypes.flux);
        fluxConnectors.forEach(fluxConnector -> fluxConnector.getTransferHandler().onLastEndTick());
        network_stats.getValue().stopProfiling();
        network_stats.getValue().onEndServerTick();
    }


    @Override
    public EnumAccessType getMemberPermission(EntityPlayer player) {
        if(FluxConfig.enableSuperAdmin) {
            ISuperAdmin sa = player.getCapability(Capabilities.SUPER_ADMIN, null);
            if(sa != null && sa.getPermission()) {
                return EnumAccessType.SUPER_ADMIN;
            }
        }
        return network_players.getValue().stream().collect(Collectors.toMap(NetworkMember::getPlayerUUID, NetworkMember::getAccessPermission)).getOrDefault(EntityPlayer.getUUID(player.getGameProfile()), network_security.getValue().isEncrypted() ? EnumAccessType.NONE : EnumAccessType.USER);
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onRemoved() {
        getConnections(FluxCacheTypes.flux).forEach(flux -> MinecraftForge.EVENT_BUS.post(new FluxConnectionEvent.Disconnected((IFluxConnector) flux, this)));
        connections.clear();
        toAdd.clear();
        toRemove.clear();
        sortedPoints.clear();
        sortedPlugs.clear();
    }

    @Override
    public void queueConnectionAddition(IFluxConnector flux) {
        toAdd.add(flux);
        toRemove.remove(flux);
        addToLite(flux);
    }

    @Override
    public void queueConnectionRemoval(IFluxConnector flux, boolean chunkUnload) {
        toRemove.add(flux);
        toAdd.remove(flux);
        if(chunkUnload) {
            changeChunkLoaded(flux, false);
        } else {
            removeFromLite(flux);
        }
    }

    private void addToLite(IFluxConnector flux) {
        Optional<IFluxConnector> c = all_connectors.getValue().stream().filter(f -> f.getCoords().equals(flux.getCoords())).findFirst();
        if(c.isPresent()) {
            changeChunkLoaded(flux, true);
        } else {
            FluxLiteConnector lite = new FluxLiteConnector(flux);
            all_connectors.getValue().add(lite);
        }
    }

    private void removeFromLite(IFluxConnector flux) {
        all_connectors.getValue().removeIf(f -> f.getCoords().equals(flux.getCoords()));
    }

    private void changeChunkLoaded(IFluxConnector flux, boolean chunkLoaded) {
        Optional<IFluxConnector> c = all_connectors.getValue().stream().filter(f -> f.getCoords().equals(flux.getCoords())).findFirst();
        c.ifPresent(fluxConnector -> fluxConnector.setChunkLoaded(chunkLoaded));
    }

    @Override
    public void addNewMember(String name) {
        NetworkMember a = NetworkMember.createMemberByUsername(name);
        if(network_players.getValue().stream().noneMatch(f -> f.getPlayerUUID().equals(a.getPlayerUUID()))) {
            network_players.getValue().add(a);
        }
    }

    @Override
    public void removeMember(UUID uuid) {
        network_players.getValue().removeIf(p -> p.getPlayerUUID().equals(uuid) && !p.getAccessPermission().canDelete());
    }

    @Override
    public Optional<NetworkMember> getValidMember(UUID player) {
        return network_players.getValue().stream().filter(f -> f.getPlayerUUID().equals(player)).findFirst();
    }

    public void markLiteSettingChanged(IFluxConnector flux) {

    }

    @SuppressWarnings("unchecked")
    private void sortConnections() {
        sortedPlugs.clear();
        sortedPoints.clear();
        List<IFluxPlug> plugs = getConnections(FluxCacheTypes.plug);
        List<IFluxPoint> points = getConnections(FluxCacheTypes.point);
        plugs.forEach(p -> PriorityGroup.getOrCreateGroup(p.getPriority(), sortedPlugs).getConnectors().add(p));
        points.forEach(p -> PriorityGroup.getOrCreateGroup(p.getPriority(), sortedPoints).getConnectors().add(p));
        sortedPlugs.sort(Comparator.comparing(p -> -p.getPriority()));
        sortedPoints.sort(Comparator.comparing(p -> -p.getPriority()));
    }
}
