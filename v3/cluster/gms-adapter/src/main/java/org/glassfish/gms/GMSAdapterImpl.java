/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * https://glassfish.dev.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.glassfish.gms;

import com.sun.enterprise.config.serverbeans.*;
import com.sun.enterprise.ee.cms.core.*;
import com.sun.enterprise.ee.cms.core.AliveAndReadySignal;
import com.sun.enterprise.ee.cms.core.GroupManagementService;
import com.sun.enterprise.ee.cms.impl.client.*;
import com.sun.enterprise.ee.cms.logging.GMSLogDomain;
import com.sun.enterprise.ee.cms.spi.MemberStates;
import com.sun.enterprise.mgmt.transport.NetworkUtility;
import com.sun.enterprise.mgmt.transport.grizzly.GrizzlyConfigConstants;
import com.sun.logging.LogDomains;
import org.glassfish.api.Startup;
import org.glassfish.api.admin.ServerEnvironment;
import org.glassfish.api.event.EventListener;
import org.glassfish.api.event.EventTypes;
import org.glassfish.api.event.Events;
import org.glassfish.gms.bootstrap.GMSAdapter;
import org.glassfish.gms.bootstrap.HealthHistory;
import org.jvnet.hk2.annotations.Inject;
import org.jvnet.hk2.annotations.Scoped;
import org.jvnet.hk2.annotations.Service;
import org.jvnet.hk2.component.Habitat;
import org.jvnet.hk2.component.PerLookup;
import org.jvnet.hk2.component.PostConstruct;
import org.jvnet.hk2.config.Dom;
import org.jvnet.hk2.config.types.Property;

import java.util.List;
import java.util.Properties;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author Sheetal.Vartak@Sun.COM
 */
@Scoped(PerLookup.class)
@Service()
public class GMSAdapterImpl implements GMSAdapter, PostConstruct, CallBack {

    private static final Logger logger =
        LogDomains.getLogger(GMSAdapterImpl.class, LogDomains.GMS_LOGGER);
    
    private static final String BEGINS_WITH = "^";
    private static final String GMS_PROPERTY_PREFIX = "GMS_";
    private static final String GMS_PROPERTY_PREFIX_REGEXP = BEGINS_WITH + GMS_PROPERTY_PREFIX;

    private GroupManagementService gms;

    private final static String CORE = "CORE";
    private final static String SPECTATOR = "SPECTATOR";
    private final static String MEMBERTYPE_STRING = "MEMBER_TYPE";

    // all set in postConstruct
    private String instanceName = null;
    private boolean isDas = false;
    private Cluster cluster = null;
    private String clusterName = null;
    private Config clusterConfig = null;
    private long joinTime = 0L;

    private ConcurrentHashMap<CallBack, JoinNotificationActionFactory> callbackJoinActionFactoryMapping =
            new ConcurrentHashMap<CallBack, JoinNotificationActionFactory>();
    private ConcurrentHashMap<CallBack, JoinedAndReadyNotificationActionFactory> callbackJoinedAndReadyActionFactoryMapping =
            new ConcurrentHashMap<CallBack, JoinedAndReadyNotificationActionFactory>();
    private ConcurrentHashMap<CallBack, FailureNotificationActionFactory> callbackFailureActionFactoryMapping =
            new ConcurrentHashMap<CallBack, FailureNotificationActionFactory>();
    private ConcurrentHashMap<CallBack, FailureSuspectedActionFactory> callbackFailureSuspectedActionFactoryMapping =
            new ConcurrentHashMap<CallBack, FailureSuspectedActionFactory>();
    private ConcurrentHashMap<CallBack, GroupLeadershipNotificationActionFactory> callbackGroupLeadershipActionFactoryMapping =
            new ConcurrentHashMap<CallBack, GroupLeadershipNotificationActionFactory>();
    private ConcurrentHashMap<CallBack, PlannedShutdownActionFactory> callbackPlannedShutdownActionFactoryMapping =
            new ConcurrentHashMap<CallBack, PlannedShutdownActionFactory>();
    private EventListener glassfishEventListener = null;
    private boolean aliveAndReadyLoggingEnabled = false;
    private boolean testFailureRecoveryHandler = false;

    @Inject
    Events events;

    @Inject
    ServerEnvironment env;

    @Inject(name=ServerEnvironment.DEFAULT_INSTANCE_NAME)
    Server server;

    @Inject
    Habitat habitat;

    @Inject
    Clusters clusters;

    private HealthHistory hHistory;

    @Override
    public void postConstruct() {
    }

    AtomicBoolean initialized = new AtomicBoolean(false);
    AtomicBoolean initializationComplete = new AtomicBoolean(false);

    @Override
    public String getClusterName() {
        return clusterName;
    }

    @Override
    public boolean initialize(String clusterName) {
        if (initialized.compareAndSet(false, true)) {
            this.clusterName = clusterName;
            if (clusterName == null) {
                logger.log(Level.SEVERE, "gmsservice.no.cluster.name");
                return false;
            }
            try {
                gms = GMSFactory.getGMSModule(clusterName);
            } catch (GMSException ge) {
                // ignore
            }
            if (gms != null) {
                logger.log(Level.SEVERE, "gmsservice.multiple.adapter",
                    clusterName);
                return false;
            }

            Domain domain = habitat.getComponent(Domain.class);
            instanceName = env.getInstanceName();

            isDas = env.isDas();
            if (isDas) {
                for (Cluster clusterI : clusters.getCluster()) {
                    if (clusterName.compareTo(clusterI.getName()) == 0) {
                        cluster = clusterI;
                        break;
                    }
                }

                // only want to do this in the case of the DAS
                initializeHealthHistory(cluster);
            } else {
                cluster = server.getCluster();
                assert (clusterName.equals(cluster.getName()));
            }

            if (cluster == null) {
                logger.log(Level.WARNING, "gmsservice.nocluster.warning");
                return false;       //don't enable GMS
            }

            clusterConfig = domain.getConfigNamed(clusterName + "-config");
            if (logger.isLoggable(Level.CONFIG)) {
                logger.log(Level.CONFIG,
                    "clusterName=" + clusterName +
                    " clusterConfig=" + clusterConfig);
            }
            try {
                initializeGMS();
            } catch (GMSException e) {
                logger.log(Level.SEVERE, "gmsservice.failed.to.start", e);
                // prevent access to a malformed gms object.
                return false;

            // also ensure for any unchecked exceptions (such as NPE during initialization) during initialization
            // that the malformed gms object is not allowed to be accesssed through the gms adapter.
            } catch (Throwable t) {
                logger.log(Level.SEVERE, "gmsservice.failed.to.start.unexpected", t);
                // prevent access to a malformed gms object.
                return false;
            }
            initializationComplete.set(true);
        }
        return initialized.get();
    }

    @Override
    public void complete() {
        initialized.compareAndSet(true, false);
        initializationComplete.compareAndSet(true, false);
        gms = null;
        GMSFactory.removeGMSModule(clusterName);
    }

    @Override
    public HealthHistory getHealthHistory() {
        checkInitialized();
        return hHistory;
    }

    private void initializeHealthHistory(Cluster cluster) {
        try {
            /*
             * Should not fail, but we need to make sure it doesn't
             * affect GMS just in case.
             */
            hHistory = new HealthHistory(cluster);
            Dom.unwrap(cluster).addListener(hHistory);
        } catch (Throwable t) {
            logger.log(Level.WARNING, "gmsexception.new.health.history",
                t.getLocalizedMessage());
        }
    }

    private void readGMSConfigProps(Properties configProps) {
        configProps.put(MEMBERTYPE_STRING, isDas ? SPECTATOR : CORE);
        for (ServiceProviderConfigurationKeys key : ServiceProviderConfigurationKeys.values()) {
            String keyName = key.toString();
            try {
            switch (key) {
                case MULTICASTADDRESS:
                    if (cluster != null) {
                        String value = cluster.getGmsMulticastAddress();
                        if (value != null) {
                            configProps.put(keyName, value);
                        }
                    }
                    break;

                case MULTICASTPORT:
                    if (cluster != null) {
                        String value = cluster.getGmsMulticastPort();
                        if (value != null) {
                            configProps.put(keyName, value);
                        }
                    }
                    break;

                case FAILURE_DETECTION_TIMEOUT:
                    if (clusterConfig != null) {
                        String  value = clusterConfig.getGroupManagementService().getFailureDetection().getHeartbeatFrequencyInMillis();
                        if (value != null) {
                            configProps.put(keyName, value);
                        }
                    }
                    break;

                case FAILURE_DETECTION_RETRIES:
                    if (clusterConfig != null) {
                        String  value = clusterConfig.getGroupManagementService().getFailureDetection().getMaxMissedHeartbeats();
                        if (value != null) {
                            configProps.put(keyName, value);
                        }
                    }
                    break;

                case FAILURE_VERIFICATION_TIMEOUT:
                    if (clusterConfig != null) {
                        String  value = clusterConfig.getGroupManagementService().getFailureDetection().getVerifyFailureWaittimeInMillis();
                        if (value != null) {
                            configProps.put(keyName, value);
                        }
                    }
                    break;

                case DISCOVERY_TIMEOUT:
                    if (clusterConfig != null) {
                        String  value = clusterConfig.getGroupManagementService().getGroupDiscoveryTimeoutInMillis();
                        if (value != null) {
                            configProps.put(keyName, value);
                        }
                    }
                    break;

                case IS_BOOTSTRAPPING_NODE:
                    configProps.put(keyName, isDas ? Boolean.TRUE.toString() : Boolean.FALSE.toString());
                    break;

                case VIRTUAL_MULTICAST_URI_LIST:
                    // todo
                    break;

                case BIND_INTERFACE_ADDRESS:
                    if (cluster != null) {
                        String value = cluster.getGmsBindInterfaceAddress();
                        if (value != null) {
                            value = value.trim();
                        }
                        if (value != null && value.length() > 1 && value.charAt(0) != '$') {

                            // todo: remove check for value length greater than 1.
                            // this value could be anything from IPv4 address, IPv6 address, hostname, network interface name.
                            // Only supported IPv4 address in gf v2.
                            if (NetworkUtility.isBindAddressValid(value)) {
                                configProps.put(keyName, value);
                            } else {
                                logger.log(Level.SEVERE,
                                    "gmsservice.bind.int.address.invalid",
                                    value);
                            }
                        }
                    }
                    break;

                case FAILURE_DETECTION_TCP_RETRANSMIT_TIMEOUT:
                    if (clusterConfig != null) {
                        String  value = clusterConfig.getGroupManagementService().getFailureDetection().getVerifyFailureConnectTimeoutInMillis();
                        if (value != null) {
                            configProps.put(keyName, value);
                        }
                    }
                    break;

                case MULTICAST_POOLSIZE:
                case INCOMING_MESSAGE_QUEUE_SIZE :
                // case MAX_MESSAGE_LENGTH:    todo uncomment with shoal-gms.jar with this defined is promoted.
                case FAILURE_DETECTION_TCP_RETRANSMIT_PORT:

                    if (clusterConfig != null) {
                        Property prop = clusterConfig.getGroupManagementService().getProperty(keyName);
                        if (prop == null) {
                            if (logger.isLoggable(Level.FINE)) {
                                logger.log(Level.FINE, String.format(
                                    "No config property found for %s",
                                    keyName));
                            }
                            break;
                        }
                        String value = prop.getValue().trim();
                        if (value != null) {
                            configProps.put(keyName, value);
                        }
                        /*
                        int positiveint = 0;
                        try {
                            positiveint = Integer.getInteger(value);
                        } catch (Throwable t) {}

                        // todo
                        if (positiveint > 0) {
                            configProps.put(keyName, positiveint);
                        } // todo else log event that invalid value was provided.
                        */
                    }
                    break;

                // These Shoal GMS configuration parameters are not supported to be set.
                // Must place here or they will get flagged as not handled.
                case LOOPBACK:
                    break;

                // end unsupported Shoal GMS configuration parameters.


                default:
                    if (logger.isLoggable(Level.FINE)) {
                        logger.log(Level.FINE, String.format(
                            "service provider key %s ignored", keyName));
                    }
                    break;
            }  /* end switch over ServiceProviderConfigurationKeys enum */
            } catch (Throwable t) {
                logger.log(Level.WARNING,
                    "gmsexception.processing.config.props",
                    t.getLocalizedMessage());
            }
        } /* end for loop over ServiceProviderConfigurationKeys */

        // check for Grizzly transport specific properties in GroupManagementService property list and then cluster property list.
        // cluster property is more specific than group-mangement-service, so allow cluster property to override group-management-service proeprty
        // if a GrizzlyConfigConstant property is in both list.
        List<Property> props = null;
        if (clusterConfig != null) {
            props = clusterConfig.getGroupManagementService().getProperty();
            for (Property prop : props) {
                String name = prop.getName().trim();
                String value = prop.getValue().trim();
                if (name == null || value == null) {
                    continue;
                }
                if (logger.isLoggable(Level.CONFIG)) {
                    logger.log(Level.CONFIG,
                        "processing group-management-service property name=" +
                            name + " value= " + value);
                }
                if (value.startsWith("${")) {
                    if (logger.isLoggable(Level.CONFIG)) {
                        logger.log(Level.CONFIG,
                            "skipping group-management-service property name=" +
                                name +
                                " since value is unresolved symbolic token=" +
                                value);
                    }
                } else if (name != null ) {
                        if (logger.isLoggable(Level.CONFIG)) {
                            logger.log(Level.CONFIG,
                                "processing group-management-service property name=" +
                                    name + " value= " + value);
                        }
                        if (name.startsWith(GMS_PROPERTY_PREFIX)) {
                            name = name.replaceFirst(GMS_PROPERTY_PREFIX_REGEXP, "");
                        }
                        configProps.put(name, value);
                        if (! validateGMSProperty(name)) {
                            logger.log(Level.WARNING, "gmsexception.ignoring.property",
                                           new Object [] {name, value, ""} );
                        }

                }
            }
        }
        if (cluster != null) {
            props = cluster.getProperty();
            for (Property prop : props) {
                String name = prop.getName().trim();
                String value = prop.getValue().trim();
                if (name == null || value == null) continue;
                if (logger.isLoggable(Level.CONFIG)) {
                    logger.log(Level.CONFIG,
                        "processing cluster property name=" + name +
                        " value= " + value);
                }
                if (value.startsWith("${")) {
                    if (logger.isLoggable(Level.CONFIG)) {
                        logger.log(Level.CONFIG,
                            "skipping cluster property name=" + name +
                            " since value is unresolved symbolic token=" +
                            value);
                    }
                } else if (name != null ) {
                        if (name.startsWith(GMS_PROPERTY_PREFIX)) {
                            name = name.replaceFirst(GMS_PROPERTY_PREFIX_REGEXP, "");
                        }
                        // undocumented property for testing purposes.
                        // impossible to register handlers in a regular app before gms starts up.
                        if (name.compareTo("ALIVEANDREADY_LOGGING") == 0){
                            aliveAndReadyLoggingEnabled = Boolean.parseBoolean(value);
                        } else if (name.compareTo("LISTENER_PORT") == 0 ) {

                            // special case mapping.  Glassfish Cluster property GMS_LISTENER_PORT maps to Grizzly Config Constants TCPSTARTPORT and TCPENDPORT.
                            configProps.put(GrizzlyConfigConstants.TCPSTARTPORT.toString(), value);
                            configProps.put(GrizzlyConfigConstants.TCPENDPORT.toString(), value);
                        } else if (name.compareTo("TEST_FAILURE_RECOVERY") == 0) {
                            testFailureRecoveryHandler = Boolean.parseBoolean(value);
                        } else {
                            // handle normal case.  one to one mapping.
                            configProps.put(name, value);
                            logger.log(Level.CONFIG,
                        "processing cluster property name=" + name +
                        " value= " + value);
                            if (! validateGMSProperty(name)) {
                                logger.log(Level.WARNING, "gmsexception.cluster.property.error",
                                           new Object [] {name, value, ""} );
                            }
                        }
                }
            }
        }
    }

    private boolean validateGMSProperty(String propertyName) {
        boolean result = false;
        try {
            GrizzlyConfigConstants key = GrizzlyConfigConstants.valueOf(propertyName);
            result = true;
        } catch (Throwable t) {}
        try {
            ServiceProviderConfigurationKeys key = ServiceProviderConfigurationKeys.valueOf(propertyName);
            result = true;
        } catch (Throwable t) {}
        return result;
    }

    private void initializeGMS() throws GMSException{
        Properties configProps = new Properties();
        int HA_MAX_GMS_MESSAGE_LENGTH =  4 * (1024 * 1024)  + (2 * 1024);  // Default to 4 MB limit in glassfish.
        configProps.put(ServiceProviderConfigurationKeys.MAX_MESSAGE_LENGTH.toString(), Integer.toString(HA_MAX_GMS_MESSAGE_LENGTH));


        // read GMS configuration from domain.xml
        readGMSConfigProps(configProps);

        printProps(configProps);

        String memberType = (String) configProps.get(MEMBERTYPE_STRING);
        gms = (GroupManagementService) GMSFactory.startGMSModule(instanceName, clusterName,
                GroupManagementService.MemberType.valueOf(memberType), configProps);
        //remove GMSLogDomain.getLogger(GMSLogDomain.GMS_LOGGER).setLevel(gmsLogLevel);
        GMSFactory.setGMSEnabledState(clusterName, Boolean.TRUE);
        if (gms != null) {
            try {
                registerJoinedAndReadyNotificationListener(this);
                registerJoinNotificationListener(this);
                registerFailureNotificationListener(this);
                registerPlannedShutdownListener(this);
                registerFailureSuspectedListener(this);

                //fix gf it 12905
                if (testFailureRecoveryHandler && ! env.isDas()) {

                    // this must be here or appointed recovery server notification is not printed out for automated testing.
                    registerFailureRecoveryListener("GlassfishFailureRecoveryHandlerTest", this);
                }

                glassfishEventListener = new org.glassfish.api.event.EventListener() {
                    public void event(Event event) {
                        if (gms == null) {
                            // handle cases where gms is not set and for some reason this handler did not get unregistered.
                            return;
                        }
                        if (event.is(EventTypes.PREPARE_SHUTDOWN)) {
                            logger.log(Level.INFO, "gmsservice.server_shutdown.received",
                                       new Object[]{gms.getInstanceName(), gms.getGroupName(), event.name()});

                            // todo: remove these when removing the test register ones above.
                            removeJoinedAndReadyNotificationListener(GMSAdapterImpl.this);
                            removeJoinNotificationListener(GMSAdapterImpl.this);
                            removeFailureNotificationListener(GMSAdapterImpl.this);
                            removeFailureSuspectedListener(GMSAdapterImpl.this);
                            gms.shutdown(GMSConstants.shutdownType.INSTANCE_SHUTDOWN);
                            removePlannedShutdownListener(GMSAdapterImpl.this);
                            events.unregister(glassfishEventListener);
                        } else if (event.is(EventTypes.SERVER_READY)) {
                             // consider putting following, includding call to joinedAndReady into a timertask.
                              // this time would give instance time to get its heartbeat cache updated by all running
                              // READY cluster memebrs
//                            final long MAX_WAIT_DURATION = 4000;
//
//                            long elapsedDuration = (joinTime == 0L) ? 0 : System.currentTimeMillis() - joinTime;
//                            long waittime = MAX_WAIT_DURATION - elapsedDuration;
//                            if (waittime > 0L && waittime <= MAX_WAIT_DURATION) {
//                                try {
//                                    logger.info("wait " + waittime + " ms before signaling joined and ready");
//                                    Thread.sleep(waittime);
//                                } catch(Throwable t) {}
//                            }
//                          validateCoreMembers();
                            gms.reportJoinedAndReadyState();
                        }
                    }
                };
                events.register(glassfishEventListener);
                gms.join();
                joinTime = System.currentTimeMillis();
                logger.log(Level.INFO, "gmsservice.member.joined.group",
                    new Object [] {instanceName, clusterName});
            } catch (GMSException e) {
                // failed to start so unregister event listener that calls GMS.
                events.unregister(glassfishEventListener);
                throw e;
            }

            logger.log(Level.INFO, "gmsservice.started",
                new Object[] {instanceName, clusterName});

        } else throw new GMSException("gms object is null.");
    }

    private void validateCoreMembers() {
        List<String> currentCoreMembers = gms.getGroupHandle().getCurrentCoreMembers();
        SortedSet<String> unknownMembers = new TreeSet<String>();
        for (String member : currentCoreMembers) {
            MemberStates state = gms.getGroupHandle().getMemberState(member, 10000, 0);
            if (state == MemberStates.UNKNOWN) {
                unknownMembers.add(member);

            }
        }
        if (unknownMembers.size() > 0) {
            logger.log(Level.INFO,
                "gmsservice.member.state.unknown", unknownMembers);
        }
    }

    private void printProps(Properties prop) {
        if (!logger.isLoggable(Level.CONFIG)) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (String key : prop.stringPropertyNames()) {
            sb.append(key).append(" = ").append(prop.get(key)).append("  ");
        }
        logger.log(Level.CONFIG,
            "Printing all GMS properties: ", sb.toString());
    }

    public Startup.Lifecycle getLifecycle() {
        return Startup.Lifecycle.SERVER;
    }

    private void checkInitialized() {
        if( ! initialized.get() || ! initializationComplete.get())  {
            throw new IllegalStateException("GMSAdapter not properly initialized.");
        }
    }
    @Override
    public GroupManagementService getModule() {
        checkInitialized();
        return gms;
    }

    public GroupManagementService getGMS(String groupName) {
        //return the gms instance for that group
        try {
            return GMSFactory.getGMSModule(groupName);
        } catch (GMSException e) {
            logger.log(Level.SEVERE, "gmsexception.cannot.get.group.module",
                new Object [] {groupName , e.getLocalizedMessage()});
            return null;
        }
    }

    @Override
    public void processNotification(Signal signal) {
        if (logger.isLoggable(Level.FINE)) {
            logger.log(Level.FINE, "GMSService: Received a notification ",
                signal.getClass().getName());
        }
        try {
            /*
             * Should not fail, but we need to make sure it doesn't
             * affect GMS just in case. In the non-DAS case, hHistory
             * will always be null so we skip it. In the DAS case,
             * it shouldn't be null unless we've already seen an
             * error logged during construction.
             */
            if (hHistory != null) {
                hHistory.updateHealth(signal);
            }
        } catch (Throwable t) {
            logger.log(Level.WARNING, "gmsexception.update.health.history",
                t.getLocalizedMessage());
        }
        // testing only.  one must set cluster property GMS_TEST_FAILURE_RECOVERY to true for the following to execute. */
        if (testFailureRecoveryHandler && signal instanceof FailureRecoverySignal) {
            FailureRecoverySignal frsSignal = (FailureRecoverySignal)signal;
            logger.log(Level.INFO, "gmsservice.failurerecovery.start.notification", new Object[]{frsSignal.getComponentName(), frsSignal.getMemberToken()});
            try {
                Thread.sleep(20 * 1000); // sleep 20 seconds. simulate wait time to allow instance to restart and do self recovery before another instance does it.
            } catch (InterruptedException ie) {
            }
            logger.log(Level.INFO, "gmsservice.failurerecovery.completed.notification", new Object[]{frsSignal.getComponentName(), frsSignal.getMemberToken()});
        }
        if (this.aliveAndReadyLoggingEnabled) {
            if (signal instanceof JoinedAndReadyNotificationSignal ||
                signal instanceof FailureNotificationSignal ||
                signal instanceof PlannedShutdownSignal) {
                AliveAndReadySignal arSignal = (AliveAndReadySignal)signal;
                String signalSubevent = "";
                if (signal instanceof JoinedAndReadyNotificationSignal) {
                    JoinedAndReadyNotificationSignal jrsig = (JoinedAndReadyNotificationSignal)signal;
                    if (jrsig.getEventSubType() == GMSConstants.startupType.GROUP_STARTUP) {
                        signalSubevent = " Subevent: " + GMSConstants.startupType.GROUP_STARTUP;
                    } else if (jrsig.getRejoinSubevent() != null) {
                        signalSubevent = " Subevent: " + jrsig.getRejoinSubevent();
                    }
                }
                if (signal instanceof PlannedShutdownSignal) {
                    PlannedShutdownSignal pssig = (PlannedShutdownSignal)signal;
                    if (pssig.getEventSubType() == GMSConstants.shutdownType.GROUP_SHUTDOWN) {
                        signalSubevent = " Subevent:" + GMSConstants.shutdownType.GROUP_SHUTDOWN.toString();
                    }
                }
                AliveAndReadyView current = arSignal.getCurrentView();
                AliveAndReadyView previous = arSignal.getPreviousView();
                logger.log(Level.INFO, "gmsservice.alive.ready.signal",
                    new Object [] {
                        signal.getClass().getSimpleName() + signalSubevent,
                        signal.getMemberToken(),
                        signal.getGroupName(),
                        current,
                        previous
                    });
            }
        }
    }

    // each of the getModule(s) methods are temporary. see class-level comment.

    /**
     * Registers a JoinNotification Listener.
     *
     * @param callback processes GMS notification JoinNotificationSignal
     */
    @Override
    public void registerJoinNotificationListener(CallBack callback) {
        if (gms != null  && callback != null) {
            JoinNotificationActionFactory jnaf =  new JoinNotificationActionFactoryImpl(callback);
            gms.addActionFactory(jnaf);
            callbackJoinActionFactoryMapping.put(callback, jnaf);
        }
    }

    /**
     * Registers a JoinAndReadyNotification Listener.
     *
     * @param callback processes GMS notification JoinAndReadyNotificationSignal
     */
    @Override
    public void registerJoinedAndReadyNotificationListener(CallBack callback) {
        if (gms != null && callback != null) {
            JoinedAndReadyNotificationActionFactory jnaf =  new JoinedAndReadyNotificationActionFactoryImpl(callback);
            gms.addActionFactory(jnaf);
            callbackJoinedAndReadyActionFactoryMapping.put(callback, jnaf);
        }
    }

    /**
     * Register a listener for all events that represent a member has left the group.
     *
     * @param callback Signal can be either PlannedShutdownSignal, FailureNotificationSignal or JoinNotificationSignal(subevent Rejoin).
     */
    @Override
    public void registerMemberLeavingListener(CallBack callback) {
        if (gms != null && callback != null) {
            registerFailureNotificationListener(callback);
            registerPlannedShutdownListener(callback);
            registerJoinNotificationListener(callback);
        }
    }

    /**
     * Registers a PlannedShutdown Listener.
     *
     * @param callback processes GMS notification PlannedShutdownSignal
     */
    @Override
    public void registerPlannedShutdownListener(CallBack callback) {
        if (gms != null && callback != null) {
            PlannedShutdownActionFactory psaf = new PlannedShutdownActionFactoryImpl(callback);
            callbackPlannedShutdownActionFactoryMapping.put(callback, psaf);
            gms.addActionFactory(psaf);
        }
    }

    /**
     * Registers a FailureSuspected Listener.
     *
     * @param callback processes GMS notification FailureSuspectedSignal
     */
    @Override
    public void registerFailureSuspectedListener(CallBack callback) {
        if (gms != null) {
            FailureSuspectedActionFactory fsaf = new FailureSuspectedActionFactoryImpl(callback);
            callbackFailureSuspectedActionFactoryMapping.put(callback, fsaf);
            gms.addActionFactory(fsaf);
        }
    }

    /**
     * Registers a FailureNotification Listener.
     *
     * @param callback processes GMS notification FailureNotificationSignal
     */
    @Override
    public void registerFailureNotificationListener(CallBack callback) {
        if (gms != null) {
            FailureNotificationActionFactory fnaf = new FailureNotificationActionFactoryImpl(callback);
            callbackFailureActionFactoryMapping.put(callback, fnaf);
            gms.addActionFactory(fnaf);
        }
    }

    /**
     * Registers a FailureRecovery Listener.
     *
     * @param callback      processes GMS notification FailureRecoverySignal
     * @param componentName The name of the parent application's component that should be notified of selected for
     *                      performing recovery operations. One or more components in the parent application may
     *                      want to be notified of such selection for their respective recovery operations.
     */
    @Override
    public void registerFailureRecoveryListener(String componentName, CallBack callback) {
        if (gms != null) {
            gms.addActionFactory(componentName, new FailureRecoveryActionFactoryImpl(callback));
        }
    }

    /**
     * Registers a Message Listener.
     *
     * @param componentName   Name of the component that would like to consume
     *                        Messages. One or more components in the parent application would want to
     *                        be notified when messages arrive addressed to them. This registration
     *                        allows GMS to deliver messages to specific components.
     * @param messageListener processes GMS MessageSignal
     */
    @Override
    public void registerMessageListener(String componentName, CallBack messageListener) {
        if (gms != null) {
            gms.addActionFactory(new MessageActionFactoryImpl(messageListener), componentName);
        }
    }

    /**
     * Registers a GroupLeadershipNotification Listener.
     *
     * @param callback processes GMS notification GroupLeadershipNotificationSignal. This event occurs when the GMS masters leaves the Group
     *                 and another member of the group takes over leadership. The signal indicates the new leader.
     */
    @Override
    public void registerGroupLeadershipNotificationListener(CallBack callback) {
        if (gms != null) {
            gms.addActionFactory(new GroupLeadershipNotificationActionFactoryImpl(callback));
        }
    }

    @Override
    public void removeFailureRecoveryListener(String componentName) {
        if (gms != null) {
            gms.removeFailureRecoveryActionFactory(componentName);
        }
    }

    @Override
    public void removeMessageListener(String componentName){
        if (gms != null) {
            gms.removeMessageActionFactory(componentName);
        }
    }

    @Override
    public void removeFailureNotificationListener(CallBack callback){
        if (gms != null) {
            FailureNotificationActionFactory fnaf = callbackFailureActionFactoryMapping.remove(callback);
            if (fnaf != null) {
                gms.removeActionFactory(fnaf);
            }
        }
    }

    @Override
    public void removeFailureSuspectedListener(CallBack callback){
         if (gms != null) {
            FailureSuspectedActionFactory fsaf = callbackFailureSuspectedActionFactoryMapping.remove(callback);
            if (fsaf != null) {
                gms.removeFailureSuspectedActionFactory(fsaf);
            }
        }
    }

    @Override
    public void removeJoinNotificationListener(CallBack callback){
        if (gms != null) {
            JoinNotificationActionFactory jaf = callbackJoinActionFactoryMapping.get(callback);
            if (jaf != null)  {
                gms.removeActionFactory(jaf);
            }
        }
    }

    @Override
    public void removeJoinedAndReadyNotificationListener(CallBack callback){
        if (gms != null) {
            JoinedAndReadyNotificationActionFactory jaf = callbackJoinedAndReadyActionFactoryMapping.get(callback);
            if (jaf != null)  {
                gms.removeActionFactory(jaf);
            }
        }
    }

    @Override
    public void removePlannedShutdownListener(CallBack callback){
        if (gms != null) {
            PlannedShutdownActionFactory psaf = callbackPlannedShutdownActionFactoryMapping.remove(callback);
            if (psaf != null) {
                gms.removeActionFactory(psaf);
            }
        }
    }

    @Override
    public void removeGroupLeadershipLNotificationistener(CallBack callback){
         if (gms != null) {
            GroupLeadershipNotificationActionFactory glnf = callbackGroupLeadershipActionFactoryMapping.get(callback);
            if (glnf != null)  {
                gms.removeActionFactory(glnf);
            }
        }
    }

    @Override
    public void removeMemberLeavingListener(CallBack callback){
        removePlannedShutdownListener(callback);
        removeFailureNotificationListener(callback);
        removeJoinNotificationListener(callback);
    }

}
