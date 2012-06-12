// Copyright 2012 Citrix Systems, Inc. Licensed under the
// Apache License, Version 2.0 (the "License"); you may not use this
// file except in compliance with the License.  Citrix Systems, Inc.
// reserves all rights not expressly granted by the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
// 
// Automatically generated by addcopyright.py at 04/03/2012
package com.cloud.network.router;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.ejb.Local;

import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager.OnError;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.SetupGuestNetworkAnswer;
import com.cloud.agent.api.SetupGuestNetworkCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.routing.IpAssocVpcCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.routing.SetSourceNatCommand;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.dc.DataCenterVO;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.InsufficientVirtualNetworkCapcityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.network.IPAddressVO;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.IsolationType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.VirtualRouterProvider;
import com.cloud.network.VirtualRouterProvider.VirtualRouterProviderType;
import com.cloud.network.VpcVirtualNetworkApplianceService;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.router.VirtualRouter.Role;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.Dao.VpcDao;
import com.cloud.network.vpc.Dao.VpcOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.utils.component.Inject;
import com.cloud.utils.db.DB;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfile.Param;

/**
 * @author Alena Prokharchyk
 */

@Local(value = {VpcVirtualNetworkApplianceManager.class, VpcVirtualNetworkApplianceService.class})
public class VpcVirtualNetworkApplianceManagerImpl extends VirtualNetworkApplianceManagerImpl implements VpcVirtualNetworkApplianceManager{
    private static final Logger s_logger = Logger.getLogger(VpcVirtualNetworkApplianceManagerImpl.class);

    @Inject
    VpcDao _vpcDao = null;
    @Inject
    VpcOfferingDao _vpcOffDao = null;
    @Inject
    PhysicalNetworkDao _pNtwkDao = null;
    @Inject
    NetworkService _ntwkService = null;
    
    @Override
    public List<DomainRouterVO> deployVirtualRouterInVpc(Vpc vpc, DeployDestination dest, Account owner, 
            Map<Param, Object> params) throws InsufficientCapacityException,
            ConcurrentOperationException, ResourceUnavailableException {

        List<DomainRouterVO> routers = findOrDeployVirtualRouterInVpc(vpc, dest, owner, params);
        
        return startRouters(params, routers);
    }
    
    @DB
    protected List<DomainRouterVO> findOrDeployVirtualRouterInVpc(Vpc vpc, DeployDestination dest, Account owner,
            Map<Param, Object> params) throws ConcurrentOperationException, 
            InsufficientCapacityException, ResourceUnavailableException {

        s_logger.debug("Deploying Virtual Router in VPC "+ vpc);
        Vpc vpcLock = _vpcDao.acquireInLockTable(vpc.getId());
        if (vpcLock == null) {
            throw new ConcurrentOperationException("Unable to lock vpc " + vpc.getId());
        }
        
        //1) Get deployment plan and find out the list of routers     
        Pair<DeploymentPlan, List<DomainRouterVO>> planAndRouters = getDeploymentPlanAndRouters(vpc.getId(), dest);
        DeploymentPlan plan = planAndRouters.first();
        List<DomainRouterVO> routers = planAndRouters.second();
        try { 
            //2) Return routers if exist
            if (routers.size() >= 1) {
                return routers;
            }
            
            Long offeringId = _vpcOffDao.findById(vpc.getVpcOfferingId()).getServiceOfferingId();
            if (offeringId == null) {
                offeringId = _offering.getId();
            }
            //3) Deploy Virtual Router
            List<? extends PhysicalNetwork> pNtwks = _pNtwkDao.listByZone(vpc.getZoneId());
            
            VirtualRouterProvider vpcVrProvider = null;
           
            for (PhysicalNetwork pNtwk : pNtwks) {
                PhysicalNetworkServiceProvider provider = _physicalProviderDao.findByServiceProvider(pNtwk.getId(), 
                        VirtualRouterProviderType.VPCVirtualRouter.toString());
                if (provider == null) {
                    throw new CloudRuntimeException("Cannot find service provider " + 
                            VirtualRouterProviderType.VPCVirtualRouter.toString() + " in physical network " + pNtwk.getId());
                }
                vpcVrProvider = _vrProviderDao.findByNspIdAndType(provider.getId(), 
                        VirtualRouterProviderType.VPCVirtualRouter);
                if (vpcVrProvider != null) {
                    break;
                }
            }
            
            PublicIp sourceNatIp = _networkMgr.assignSourceNatIpAddressToVpc(owner, vpc);
            
            DomainRouterVO router = deployVpcRouter(owner, dest, plan, params, false, vpcVrProvider, offeringId,
                    vpc.getId(), sourceNatIp);
            routers.add(router);
            
        } finally {
            if (vpcLock != null) {
                _vpcDao.releaseFromLockTable(vpc.getId());
            }
        }
        return routers;
    }
    
    protected Pair<DeploymentPlan, List<DomainRouterVO>> getDeploymentPlanAndRouters(long vpcId, DeployDestination dest) {
        long dcId = dest.getDataCenter().getId();
        
        DeploymentPlan plan = new DataCenterDeployment(dcId);
        List<DomainRouterVO> routers = _routerDao.listRoutersByVpcId(vpcId);
        
        return new Pair<DeploymentPlan, List<DomainRouterVO>>(plan, routers);
    }
    
    @Override
    public boolean addVpcRouterToGuestNetwork(VirtualRouter router, Network network, boolean isRedundant) 
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        boolean dnsProvided = _networkMgr.isProviderSupportServiceInNetwork(network.getId(), Service.Dns, Provider.VPCVirtualRouter);
        boolean dhcpProvided = _networkMgr.isProviderSupportServiceInNetwork(network.getId(), Service.Dhcp, 
                Provider.VPCVirtualRouter);
        
        boolean setupDns = dnsProvided || dhcpProvided;
        
        return addVpcRouterToGuestNetwork(router, network, isRedundant, setupDns);
    }
    
    protected boolean addVpcRouterToGuestNetwork(VirtualRouter router, Network network, boolean isRedundant, boolean setupDns) 
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        
        if (network.getTrafficType() != TrafficType.Guest) {
            s_logger.warn("Network " + network + " is not of type " + TrafficType.Guest);
            return false;
        }
        
        //Add router to the Guest network
        boolean result = true;
        try {
            if (!_routerDao.isRouterPartOfGuestNetwork(router.getId(), network.getId())) {
                DomainRouterVO routerVO = _routerDao.findById(router.getId());
                _routerDao.addRouterToGuestNetwork(routerVO, network);
            } 
            
            NicProfile guestNic = _itMgr.addVmToNetwork(router, network, null);
            //setup guest network
            if (guestNic != null) {
                result = setupVpcGuestNetwork(network, router, true, isRedundant, guestNic, setupDns);
            } else {
                s_logger.warn("Failed to add router " + router + " to guest network " + network);
                result = false;
            }
        } catch (Exception ex) {
            s_logger.warn("Failed to add router " + router + " to network " + network + " due to ", ex);
            result = false;
        } finally {
            if (!result) {
                s_logger.debug("Removing the router " + router + " from network " + network + " as a part of cleanup");
                if (removeRouterFromGuestNetwork(router, network, isRedundant)) {
                    s_logger.debug("Removed the router " + router + " from network " + network + " as a part of cleanup");
                } else {
                    s_logger.warn("Failed to remove the router " + router + " from network " + network + " as a part of cleanup");
                }
            }
        }
        
        return result;
    }

    @Override
    public boolean removeRouterFromGuestNetwork(VirtualRouter router, Network network, boolean isRedundant) 
            throws ConcurrentOperationException, ResourceUnavailableException {
        if (network.getTrafficType() != TrafficType.Guest) {
            s_logger.warn("Network " + network + " is not of type " + TrafficType.Guest);
            return false;
        }
        
        //Check if router is a part of the Guest network
        if (!_networkMgr.isVmPartOfNetwork(router.getId(), network.getId())) {
            s_logger.debug("Router " + router + " is not a part of the Guest network " + network);
            return true;
        }
        
        boolean result = setupVpcGuestNetwork(network, router, false, isRedundant, _networkMgr.getNicProfile(router, network.getId()), false);
        if (!result) {
            s_logger.warn("Failed to destroy guest network config " + network + " on router " + router);
            return false;
        }
        
        result = result && _itMgr.removeVmFromNetwork(router, network, null);
        
        if (result) {
            if (result) {
                //check if router is already part of network
                if (_routerDao.isRouterPartOfGuestNetwork(router.getId(), network.getId())) {
                    s_logger.debug("Removing router " + router + " from network" + network);
                    _routerDao.removeRouterFromNetwork(router.getId(), network.getId());
                }
            }
        }
        return result;
    }
    
    protected boolean addPublicIpToVpc(VirtualRouter router, Network publicNetwork, PublicIp ipAddress) 
            throws ConcurrentOperationException,ResourceUnavailableException, InsufficientCapacityException {
        
        if (publicNetwork.getTrafficType() != TrafficType.Public) {
            s_logger.warn("Network " + publicNetwork + " is not of type " + TrafficType.Public);
            return false;
        }
        
        //Add router to the Public network
        boolean result = true;
        try {
            NicProfile defaultNic = new NicProfile();
            if (ipAddress.isSourceNat()) {
                defaultNic.setDefaultNic(true);
            }
            defaultNic.setIp4Address(ipAddress.getAddress().addr());
            defaultNic.setGateway(ipAddress.getGateway());
            defaultNic.setNetmask(ipAddress.getNetmask());
            defaultNic.setMacAddress(ipAddress.getMacAddress());
            defaultNic.setBroadcastType(BroadcastDomainType.Vlan);
            defaultNic.setBroadcastUri(BroadcastDomainType.Vlan.toUri(ipAddress.getVlanTag()));
            defaultNic.setIsolationUri(IsolationType.Vlan.toUri(ipAddress.getVlanTag()));
            
            NicProfile publicNic = _itMgr.addVmToNetwork(router, publicNetwork, defaultNic);
            //setup public network
            if (publicNic != null) {
                publicNic.setDefaultNic(true);
                if (ipAddress != null) {
                    IPAddressVO ipVO = _ipAddressDao.findById(ipAddress.getId());
                    PublicIp publicIp = new PublicIp(ipVO, _vlanDao.findById(ipVO.getVlanId()), 
                            NetUtils.createSequenceBasedMacAddress(ipVO.getMacAddress()));
                    result = associtePublicIpInVpc(publicNetwork, router, false, publicIp);
                }
            } else {
                result = false;
                s_logger.warn("Failed to plug nic for " + ipAddress + " to VPC router " + router);
            }
        } catch (Exception ex) {
            s_logger.warn("Failed to add ip address " + ipAddress + " from the public network " + publicNetwork + 
                    " to VPC router " + router + " due to ", ex);
            result = false;
        }
        
        return result;
    }
    
    
    protected boolean removePublicIpFromVpcRouter(VirtualRouter router, Network publicNetwork, PublicIp ipAddress) 
            throws ConcurrentOperationException, ResourceUnavailableException {
        
        if (publicNetwork.getTrafficType() != TrafficType.Public) {
            s_logger.warn("Network " + publicNetwork + " is not of type " + TrafficType.Public);
            return false;
        }
                        
        boolean result = true;
        IPAddressVO ipVO = _ipAddressDao.findById(ipAddress.getId());
        _networkMgr.markIpAsUnavailable(ipVO.getId());
        PublicIp publicIp = new PublicIp(ipVO, _vlanDao.findById(ipVO.getVlanId()), 
                NetUtils.createSequenceBasedMacAddress(ipVO.getMacAddress()));
        result = associtePublicIpInVpc(publicNetwork, router, false, publicIp);
        
        if (!result) {
            s_logger.warn("Failed to disassociate public ip " + ipAddress  + " from router " + router);
            return false;
        }
        
        URI broadcastUri = BroadcastDomainType.Vlan.toUri(ipAddress.getVlanTag());
        if (_itMgr.removeVmFromNetwork(router, publicNetwork, broadcastUri)) {
            s_logger.debug("Successfully removed router " + router + " from vlan " + ipAddress.getVlanTag() +" of public network " + publicNetwork);
            return true;
        } else {
            s_logger.warn("Failed to remove router " + router + " from vlan " + ipAddress.getVlanTag() +" of public network " + publicNetwork);
            return false;
        }
    }
    
    protected boolean associtePublicIpInVpc(Network network, VirtualRouter router, boolean add, PublicIp ipAddress) 
            throws ConcurrentOperationException, ResourceUnavailableException{
        
        List<PublicIp> publicIps = new ArrayList<PublicIp>(1);
        publicIps.add(ipAddress);
        Commands cmds = new Commands(OnError.Stop);
        createVpcAssociateIPCommands(router, publicIps, cmds, 0);
        
        if (sendCommandsToRouter(router, cmds)) {
            s_logger.debug("Successfully applied ip association for ip " + ipAddress + " in vpc network " + network);
            return true;
        } else {
            s_logger.warn("Failed to associate ip address " + ipAddress + " in vpc network " + network);
            return false;
        }
    }
    
    
    @Override
    public boolean finalizeStart(VirtualMachineProfile<DomainRouterVO> profile, long hostId, Commands cmds,
            ReservationContext context) {
        
        if (!super.finalizeStart(profile, hostId, cmds, context)) {
            return false;
        }
        
        DomainRouterVO router = profile.getVirtualMachine();
        
        //Get guest nic info
        Map<Nic, Network> guestNics = new HashMap<Nic, Network>();
        Map<Nic, Network> publicNics = new HashMap<Nic, Network>();
        List<Network> guestNetworks = new ArrayList<Network>();
        
        List<? extends Nic> routerNics = _nicDao.listByVmId(profile.getId());
        for (Nic routerNic : routerNics) {
            Network network = _networkMgr.getNetwork(routerNic.getNetworkId());
            if (network.getTrafficType() == TrafficType.Guest) {
                guestNics.put(routerNic, network);
                guestNetworks.add(network);
            } else if (network.getTrafficType() == TrafficType.Public) {
                publicNics.put(routerNic, network);
            }
        }
        
        try {
            //add router to public and guest networks
            for (Nic publicNic : publicNics.keySet()) {
                Network publicNtwk = publicNics.get(publicNic);
                IPAddressVO userIp = _ipAddressDao.findByIpAndSourceNetworkId(publicNtwk.getId(), 
                        publicNic.getIp4Address());
                PublicIp publicIp = new PublicIp(userIp, _vlanDao.findById(userIp.getVlanId()), 
                        NetUtils.createSequenceBasedMacAddress(userIp.getMacAddress()));
                if (!addPublicIpToVpc(router, publicNtwk, publicIp)) {
                    s_logger.warn("Failed to add router router " + router + " to public network " + publicNtwk);
                    return false;
                }
            }
            
            for (Nic guestNic : guestNics.keySet()) {  
                Network guestNtwk = guestNics.get(guestNic);
                boolean setupDns = _networkMgr.setupDns(guestNtwk, Provider.VPCVirtualRouter);
                
                if (!addVpcRouterToGuestNetwork(router, guestNtwk, false, setupDns)) {
                    s_logger.warn("Failed to add router router " + router + " to guest network " + guestNtwk);
                    return false;
                }
            }
        } catch (Exception ex) {
            s_logger.warn("Failed to add router " + router + " to network due to exception ", ex);
            return false;
        }     

        return true;
    }
    
    protected DomainRouterVO deployVpcRouter(Account owner, DeployDestination dest, DeploymentPlan plan, Map<Param, Object> params,
            boolean isRedundant, VirtualRouterProvider vrProvider, long svcOffId,
            Long vpcId, PublicIp sourceNatIp) throws ConcurrentOperationException, 
            InsufficientAddressCapacityException, InsufficientServerCapacityException, InsufficientCapacityException, 
            StorageUnavailableException, ResourceUnavailableException {
        
        DomainRouterVO router = 
                super.deployRouter(owner, dest, plan, params, isRedundant, vrProvider, svcOffId, vpcId, sourceNatIp);
        
        //Plug public nic
        boolean addToPublicNtwk = true;
        if (sourceNatIp != null) {
            Network publicNetwork = _networkDao.listByZoneAndTrafficType(dest.getDataCenter().getId(), TrafficType.Public).get(0);
            addToPublicNtwk = addPublicIpToVpc(router, publicNetwork, sourceNatIp); 
        }
        
        if (!addToPublicNtwk) {
            s_logger.warn("Failed to add router " + router + " to public network in zone " + dest.getDataCenter() + " cleaninig up");
            destroyRouter(router.getId());
            return null;
        }
        
        return router;
    }
    
    @Override
    public boolean plugNic(Network network, NicTO nic, VirtualMachineTO vm, 
            ReservationContext context, DeployDestination dest) throws ConcurrentOperationException, ResourceUnavailableException,
            InsufficientCapacityException {     
        boolean result = true;
        
        try {
            PlugNicCommand plugNicCmd = new PlugNicCommand(vm, nic);
            
            Commands cmds = new Commands(OnError.Stop);
            cmds.addCommand("plugnic", plugNicCmd);
            _agentMgr.send(dest.getHost().getId(), cmds);
            
            PlugNicAnswer plugNicAnswer = cmds.getAnswer(PlugNicAnswer.class);
            if (!(plugNicAnswer != null && plugNicAnswer.getResult())) {
                s_logger.warn("Unable to plug nic for vm " + vm.getHostName());
                result = false;
            } 

        } catch (OperationTimedoutException e) {
            throw new AgentUnavailableException("Unable to plug nic for router " + vm.getHostName() + " in network " + network,
                    dest.getHost().getId(), e);
        }
        
        return result;
    }

    @Override
    public boolean unplugNic(Network network, NicTO nic, VirtualMachineTO vm,
            ReservationContext context, DeployDestination dest) throws ConcurrentOperationException, ResourceUnavailableException {
        
        boolean result = true;
        DomainRouterVO router = _routerDao.findById(vm.getId());
        try {
            UnPlugNicCommand unplugNicCmd = new UnPlugNicCommand(vm, nic);
            Commands cmds = new Commands(OnError.Stop);
            cmds.addCommand("unplugnic", unplugNicCmd);
            _agentMgr.send(dest.getHost().getId(), cmds);
            
            UnPlugNicAnswer unplugNicAnswer = cmds.getAnswer(UnPlugNicAnswer.class);
            if (!(unplugNicAnswer != null && unplugNicAnswer.getResult())) {
                s_logger.warn("Unable to unplug nic from router " + router);
                result = false;
            } 

        } catch (OperationTimedoutException e) {
            throw new AgentUnavailableException("Unable to unplug nic from rotuer " + router + " from network " + network,
                    dest.getHost().getId(), e);
        }
        
        return result;
    }
    
    protected boolean setupVpcGuestNetwork(Network network, VirtualRouter router, boolean add, boolean isRedundant,
            NicProfile guestNic, boolean setupDns) 
            throws ConcurrentOperationException, ResourceUnavailableException{
        
        String networkDomain = network.getNetworkDomain();
        String dhcpRange = getGuestDhcpRange(guestNic, network, _configMgr.getZone(network.getDataCenterId()));
        
        boolean result = true;
        
        Nic nic = _nicDao.findByInstanceIdAndNetworkId(network.getId(), router.getId());
        long guestVlanTag = Long.parseLong(nic.getBroadcastUri().getHost());
        
        String brd = NetUtils.long2Ip(NetUtils.ip2Long(guestNic.getIp4Address()) | ~NetUtils.ip2Long(guestNic.getNetmask()));
        Integer priority = null;
        if (isRedundant) {
            List<DomainRouterVO> routers = _routerDao.listByNetworkAndRole(network.getId(), Role.VIRTUAL_ROUTER);
            try {
                getUpdatedPriority(network, routers, _routerDao.findById(router.getId()));
            } catch (InsufficientVirtualNetworkCapcityException e) {
                s_logger.error("Failed to get update priority!", e);
                throw new CloudRuntimeException("Failed to get update priority!");
            }
        }
        
        String defaultDns1 = null;
        String defaultDns2 = null;
        
        if (setupDns) {
            defaultDns1 = guestNic.getDns1();
            defaultDns2 = guestNic.getDns2();
        }
        
        NicProfile nicProfile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), 
                _networkMgr.getNetworkRate(network.getId(), router.getId()), 
                _networkMgr.isSecurityGroupSupportedInNetwork(network), _networkMgr.getNetworkTag(router.getHypervisorType(), network));

        SetupGuestNetworkCommand setupCmd = new SetupGuestNetworkCommand(dhcpRange, networkDomain, isRedundant, priority, 
                defaultDns1, defaultDns2, add, _itMgr.toNicTO(nicProfile, router.getHypervisorType()));
        setupCmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
        setupCmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(network.getId(), router.getId()));
        setupCmd.setAccessDetail(NetworkElementCommand.GUEST_VLAN_TAG, String.valueOf(guestVlanTag));
        setupCmd.setAccessDetail(NetworkElementCommand.GUEST_NETWORK_GATEWAY, network.getGateway());
        setupCmd.setAccessDetail(NetworkElementCommand.GUEST_BRIDGE, brd);
        setupCmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
        
        Commands cmds = new Commands(OnError.Stop);
        cmds.addCommand("setupguestnetwork", setupCmd);
        sendCommandsToRouter(router, cmds);
        
        SetupGuestNetworkAnswer setupAnswer = cmds.getAnswer(SetupGuestNetworkAnswer.class);
        String setup = add ? "set" : "destroy";
        if (!(setupAnswer != null && setupAnswer.getResult())) {
            s_logger.warn("Unable to " + setup + " guest network on router " + router);
            result = false;
        } 
        
        return result;
    }
    
    private void createVpcAssociateIPCommands(final VirtualRouter router, final List<? extends PublicIpAddress> ips,
            Commands cmds, long vmId) {
        
        Pair<IpAddressTO, Long> sourceNatIpAdd = null;
        Boolean addSourceNat = null;
        // Ensure that in multiple vlans case we first send all ip addresses of vlan1, then all ip addresses of vlan2, etc..
        Map<String, ArrayList<PublicIpAddress>> vlanIpMap = new HashMap<String, ArrayList<PublicIpAddress>>();
        for (final PublicIpAddress ipAddress : ips) {
            String vlanTag = ipAddress.getVlanTag();
            ArrayList<PublicIpAddress> ipList = vlanIpMap.get(vlanTag);
            if (ipList == null) {
                ipList = new ArrayList<PublicIpAddress>();
            }
            //VR doesn't support release for sourceNat IP address; so reset the state
            if (ipAddress.isSourceNat() && ipAddress.getState() == IpAddress.State.Releasing) {
                ipAddress.setState(IpAddress.State.Allocated);
            }
            ipList.add(ipAddress);
            vlanIpMap.put(vlanTag, ipList);
        }

        for (Map.Entry<String, ArrayList<PublicIpAddress>> vlanAndIp : vlanIpMap.entrySet()) {
            List<PublicIpAddress> ipAddrList = vlanAndIp.getValue();

            // Get network rate - required for IpAssoc
            Integer networkRate = _networkMgr.getNetworkRate(ipAddrList.get(0).getNetworkId(), router.getId());
            Network network = _networkMgr.getNetwork(ipAddrList.get(0).getNetworkId());

            IpAddressTO[] ipsToSend = new IpAddressTO[ipAddrList.size()];
            int i = 0;

            for (final PublicIpAddress ipAddr : ipAddrList) {
                boolean add = (ipAddr.getState() == IpAddress.State.Releasing ? false : true);

                IpAddressTO ip = new IpAddressTO(ipAddr.getAccountId(), ipAddr.getAddress().addr(), add, false, 
                        ipAddr.isSourceNat(), ipAddr.getVlanTag(), ipAddr.getGateway(), ipAddr.getNetmask(), ipAddr.getMacAddress(),
                        null, networkRate, ipAddr.isOneToOneNat());

                ip.setTrafficType(network.getTrafficType());
                ip.setNetworkName(_networkMgr.getNetworkTag(router.getHypervisorType(), network));
                ipsToSend[i++] = ip;
                if (ipAddr.isSourceNat()) {
                    sourceNatIpAdd = new Pair<IpAddressTO, Long>(ip, ipAddr.getNetworkId());
                    addSourceNat = add;
                }
            }
            IpAssocVpcCommand cmd = new IpAssocVpcCommand(ipsToSend);
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(ipAddrList.get(0).getNetworkId(), router.getId()));
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
            DataCenterVO dcVo = _dcDao.findById(router.getDataCenterIdToDeployIn());
            cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());

            cmds.addCommand("IPAssocVpcCommand", cmd);
        }
        
        //set source nat ip
        if (sourceNatIpAdd != null) {
            IpAddressTO sourceNatIp = sourceNatIpAdd.first();
            Long networkId = sourceNatIpAdd.second();
            SetSourceNatCommand cmd = new SetSourceNatCommand(sourceNatIp, addSourceNat);
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_IP, getRouterControlIp(router.getId()));
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_GUEST_IP, getRouterIpInNetwork(networkId, router.getId()));
            cmd.setAccessDetail(NetworkElementCommand.ROUTER_NAME, router.getInstanceName());
            DataCenterVO dcVo = _dcDao.findById(router.getDataCenterIdToDeployIn());
            cmd.setAccessDetail(NetworkElementCommand.ZONE_NETWORK_TYPE, dcVo.getNetworkType().toString());
            cmds.addCommand("SetSourceNatCommand", cmd);
        }
    }
    
    @Override
    public boolean associateIP(Network network, final List<? extends PublicIpAddress> ipAddress, List<? extends VirtualRouter> routers)
            throws ResourceUnavailableException {
        if (ipAddress == null || ipAddress.isEmpty()) {
            s_logger.debug("No ip association rules to be applied for network " + network.getId());
            return true;
        }
        
        //1) check which nics need to be plugged and plug them
        for (PublicIpAddress ip : ipAddress) {
            for (VirtualRouter router : routers) {
                URI broadcastUri = BroadcastDomainType.Vlan.toUri(ip.getVlanTag());
                Nic nic = _nicDao.findByInstanceIdNetworkIdAndBroadcastUri(network.getId(), router.getId(), 
                        broadcastUri.toString());
                if (nic != null) {
                    //have to plug the nic(s)
                    NicProfile defaultNic = new NicProfile();
                    if (ip.isSourceNat()) {
                        defaultNic.setDefaultNic(true);
                    }
                    defaultNic.setIp4Address(ip.getAddress().addr());
                    defaultNic.setGateway(ip.getGateway());
                    defaultNic.setNetmask(ip.getNetmask());
                    defaultNic.setMacAddress(ip.getMacAddress());
                    defaultNic.setBroadcastType(BroadcastDomainType.Vlan);
                    defaultNic.setBroadcastUri(BroadcastDomainType.Vlan.toUri(ip.getVlanTag()));
                    defaultNic.setIsolationUri(IsolationType.Vlan.toUri(ip.getVlanTag()));
                    
                    NicProfile publicNic = null;
                    Network publicNtwk = null;
                    try {
                        publicNtwk = _networkMgr.getNetwork(ip.getNetworkId());
                        publicNic = _itMgr.addVmToNetwork(router, publicNtwk, defaultNic);
                    } catch (ConcurrentOperationException e) {
                        s_logger.warn("Failed to add router " + router + " to vlan " + ip.getVlanTag() + 
                                " in public network " + publicNtwk + " due to ", e);
                    } catch (InsufficientCapacityException e) {
                        s_logger.warn("Failed to add router " + router + " to vlan " + ip.getVlanTag() + 
                                " in public network " + publicNtwk + " due to ", e);
                    } finally {
                        if (publicNic == null) {
                            s_logger.warn("Failed to add router " + router + " to vlan " + ip.getVlanTag() + 
                                    " in public network " + publicNtwk);
                            return false;
                        }
                    }
                }
            }
        }
        
        //2) apply the ips
        return applyRules(network, routers, "vpc ip association", false, null, false, new RuleApplier() {
            @Override
            public boolean execute(Network network, VirtualRouter router) throws ResourceUnavailableException {
                Commands cmds = new Commands(OnError.Continue);
                createVpcAssociateIPCommands(router, ipAddress, cmds, 0);
                return sendCommandsToRouter(router, cmds);
            }
        });
    }
}
