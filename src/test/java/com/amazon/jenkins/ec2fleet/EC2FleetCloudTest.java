package com.amazon.jenkins.ec2fleet;

import com.amazon.jenkins.ec2fleet.aws.EC2Api;
import com.amazon.jenkins.ec2fleet.fleet.AutoScalingGroupFleet;
import com.amazon.jenkins.ec2fleet.fleet.EC2Fleet;
import com.amazon.jenkins.ec2fleet.fleet.EC2Fleets;
import com.amazon.jenkins.ec2fleet.fleet.EC2SpotFleet;
import com.amazon.jenkins.ec2fleet.aws.RegionInfo;
import com.amazon.jenkins.ec2fleet.aws.AwsPermissionChecker;
import com.amazonaws.regions.RegionUtils;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import hudson.ExtensionList;
import hudson.model.Computer;
import hudson.model.Label;
import hudson.model.LabelFinder;
import hudson.model.Node;
import hudson.model.labels.LabelAtom;
import hudson.slaves.Cloud;
import hudson.slaves.ComputerConnector;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.FormValidation.Kind;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import jenkins.model.Nodes;
import org.apache.commons.lang.StringUtils;
import org.hamcrest.Matchers;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
@RunWith(MockitoJUnitRunner.class)
public class EC2FleetCloudTest {

    private MockedStatic<EC2Fleets> mockedEc2Fleets;

    private MockedStatic<FleetStateStats> mockedFleetStateStats;

    private MockedStatic<LabelFinder> mockedLabelFinder;

    private MockedStatic<Jenkins> mockedJenkins;

    private SpotFleetRequestConfig spotFleetRequestConfig1;
    private SpotFleetRequestConfig spotFleetRequestConfig2;
    private SpotFleetRequestConfig spotFleetRequestConfig3;
    private SpotFleetRequestConfig spotFleetRequestConfig4;
    private SpotFleetRequestConfig spotFleetRequestConfig5;
    private SpotFleetRequestConfig spotFleetRequestConfig6;
    private SpotFleetRequestConfig spotFleetRequestConfig7;
    private SpotFleetRequestConfig spotFleetRequestConfig8;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private Jenkins jenkins;

    @Mock
    private EC2Fleet ec2Fleet;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private EC2Api ec2Api;

    @Mock(strictness = Mock.Strictness.LENIENT)
    private AmazonEC2 amazonEC2;

    @Mock
    private EC2FleetNodeComputer idleComputer;

    @Mock
    private EC2FleetNodeComputer busyComputer;

    private EC2FleetCloud.ExecutorScaler noScaling;

    private EC2FleetCloud.ExecutorScaler weightedScaling;

    private int MiB_TO_GiB_MULTIPLIER = 1024;

    @Before
    public void before() {
        spotFleetRequestConfig1 = new SpotFleetRequestConfig();
        spotFleetRequestConfig1.setSpotFleetRequestState(BatchState.Active);
        spotFleetRequestConfig1.setSpotFleetRequestConfig(new SpotFleetRequestConfigData().withType(FleetType.Maintain));
        spotFleetRequestConfig2 = new SpotFleetRequestConfig();
        spotFleetRequestConfig2.setSpotFleetRequestState(BatchState.Submitted);
        spotFleetRequestConfig2.setSpotFleetRequestConfig(new SpotFleetRequestConfigData().withType(FleetType.Maintain));
        spotFleetRequestConfig3 = new SpotFleetRequestConfig();
        spotFleetRequestConfig3.setSpotFleetRequestState(BatchState.Modifying);
        spotFleetRequestConfig3.setSpotFleetRequestConfig(new SpotFleetRequestConfigData().withType(FleetType.Maintain));
        spotFleetRequestConfig4 = new SpotFleetRequestConfig();
        spotFleetRequestConfig4.setSpotFleetRequestState(BatchState.Cancelled);
        spotFleetRequestConfig4.setSpotFleetRequestConfig(new SpotFleetRequestConfigData().withType(FleetType.Maintain));
        spotFleetRequestConfig5 = new SpotFleetRequestConfig();
        spotFleetRequestConfig5.setSpotFleetRequestState(BatchState.Cancelled_running);
        spotFleetRequestConfig5.setSpotFleetRequestConfig(new SpotFleetRequestConfigData().withType(FleetType.Maintain));
        spotFleetRequestConfig6 = new SpotFleetRequestConfig();
        spotFleetRequestConfig6.setSpotFleetRequestState(BatchState.Cancelled_terminating);
        spotFleetRequestConfig6.setSpotFleetRequestConfig(new SpotFleetRequestConfigData().withType(FleetType.Maintain));
        spotFleetRequestConfig7 = new SpotFleetRequestConfig();
        spotFleetRequestConfig7.setSpotFleetRequestState(BatchState.Failed);
        spotFleetRequestConfig7.setSpotFleetRequestConfig(new SpotFleetRequestConfigData().withType(FleetType.Maintain));
        spotFleetRequestConfig8 = new SpotFleetRequestConfig();
        spotFleetRequestConfig8.setSpotFleetRequestState(BatchState.Active);
        spotFleetRequestConfig8.setSpotFleetRequestConfig(new SpotFleetRequestConfigData().withType(FleetType.Request));

        Registry.setEc2Api(ec2Api);
        mockedEc2Fleets = Mockito.mockStatic(EC2Fleets.class);
        mockedEc2Fleets.when(() -> EC2Fleets.get(anyString())).thenReturn(ec2Fleet);
        mockedJenkins = Mockito.mockStatic(Jenkins.class);
        mockedJenkins.when(Jenkins::get).thenReturn(jenkins);

        Mockito.when(idleComputer.isIdle()).thenReturn(true);
        Mockito.when(busyComputer.isIdle()).thenReturn(false);

        mockedFleetStateStats = Mockito.mockStatic(FleetStateStats.class);
        mockedLabelFinder = Mockito.mockStatic(LabelFinder.class);

        noScaling = new EC2FleetCloud.NoScaler();
        weightedScaling = new EC2FleetCloud.WeightedScaler();

    }

    @After
    public void after() {
        Registry.setEc2Api(new EC2Api());
        mockedJenkins.close();
        mockedLabelFinder.close();
        mockedFleetStateStats.close();
        mockedEc2Fleets.close();
    }

    @Test
    public void canProvision_fleetIsNull(){
        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", null, "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        Label label = new LabelAtom("momo");
        boolean result = fleetCloud.canProvision(new Cloud.CloudState(label, 0));
        Assert.assertFalse(result);
    }

    @Test
    public void canProvision_restrictUsageLabelIsNull(){
        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                true, "-1", false, 0, 0,
                10, false, false, noScaling);

        Label label = null;
        boolean result = fleetCloud.canProvision(new Cloud.CloudState(label, 0));
        Assert.assertFalse(result);
    }

    @Test
    public void canProvision_LabelNotInLabelString(){
        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        Label label = new LabelAtom("momo");
        boolean result = fleetCloud.canProvision(new Cloud.CloudState(label, 0));
        Assert.assertFalse(result);
    }

    @Test
    public void canProvision_LabelInLabelString(){
        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "label1 momo", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        // have to mock these for the Label.parse(...) call otherwise we get an NPE
        when(jenkins.getLabelAtom("momo")).thenReturn(new LabelAtom("momo"));
        when(jenkins.getLabelAtom("label1")).thenReturn(new LabelAtom("label1"));

        Label label = new LabelAtom("momo");
        boolean result = fleetCloud.canProvision(new Cloud.CloudState(label, 0));
        Assert.assertTrue(result);
    }

    @Test
    public void provision_shouldProvisionNoneWhenMaxReached() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 10, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        // when
        Collection<NodeProvisioner.PlannedNode> r = fleetCloud.provision(new Cloud.CloudState(null, 0), 1);

        // then
        assertEquals(0, r.size());
        assertEquals(0, fleetCloud.getToAdd());
    }

    @Test
    public void provision_shouldProvisionNoneWhenMaxReachedAndNumExecutorsMoreOne() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 1, 8, 0, 3, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 1, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        // when
        Collection<NodeProvisioner.PlannedNode> r = fleetCloud.provision(new Cloud.CloudState(null, 0), 50);

        // then
        assertEquals(7, r.size());
        assertEquals(7, fleetCloud.getToAdd());
    }

    @Test
    public void provision_shouldProvisionNoneWhenMaxReachedAndNumExecutorsMoreOne1() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 1, 8, 0, 3, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 7, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        // when
        Collection<NodeProvisioner.PlannedNode> r = fleetCloud.provision(new Cloud.CloudState(null, 0), 50);

        // then
        assertEquals(1, r.size());
        assertEquals(1, fleetCloud.getToAdd());
    }

    @Test
    public void provision_shouldProvisionNoneWhenExceedMax() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 9, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 10, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        // when
        Collection<NodeProvisioner.PlannedNode> r = fleetCloud.provision(new Cloud.CloudState(null, 0), 1);

        // then
        assertEquals(0, r.size());
        assertEquals(0, fleetCloud.getToAdd());
    }

    @Test
    public void provision_shouldProvisionIfBelowMax() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 5, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        // when
        Collection<NodeProvisioner.PlannedNode> r = fleetCloud.provision(new Cloud.CloudState(null, 0), 1);

        // then
        assertEquals(1, r.size());
        assertEquals(1, fleetCloud.getToAdd());
    }

    @Test
    public void provision_shouldProvisionNoMoreMax() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 5, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        // when
        Collection<NodeProvisioner.PlannedNode> r = fleetCloud.provision(new Cloud.CloudState(null, 0), 10);

        // then
        assertEquals(5, r.size());
        assertEquals(5, fleetCloud.getToAdd());
    }

    @Test
    public void provision_shouldProvisionNoMoreMaxWhenMultipleCallBeforeUpdate() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 5, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        // when
        Collection<NodeProvisioner.PlannedNode> r1 = fleetCloud.provision(new Cloud.CloudState(null, 0), 2);
        Collection<NodeProvisioner.PlannedNode> r2 = fleetCloud.provision(new Cloud.CloudState(null, 0), 2);
        Collection<NodeProvisioner.PlannedNode> r3 = fleetCloud.provision(new Cloud.CloudState(null, 0), 5);

        // then
        assertEquals(2, r1.size());
        assertEquals(2, r2.size());
        assertEquals(1, r3.size());
        assertEquals(5, fleetCloud.getToAdd());
    }

    @Test
    public void provision_shouldProvisionNoneIfNotYetUpdated() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        // Don't set the status
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 1, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        // when
        Collection<NodeProvisioner.PlannedNode> r = fleetCloud.provision(new Cloud.CloudState(null, 0), 1);

        // then
        assertEquals(0, r.size());
        assertEquals(0, fleetCloud.getToAdd());
    }

    @Test
    public void scheduleToTerminate_shouldNotRemoveIfStatsNotUpdated() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        // Don't set the status
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 1, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        // when
        boolean r = fleetCloud.scheduleToTerminate("z", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // then
        assertFalse(r);
    }

    @Test
    public void scheduleToTerminate_notRemoveIfBelowMin() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 1, 1, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 0, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        // when
        boolean r = fleetCloud.scheduleToTerminate("z", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // then
        assertFalse(r);
    }

    @Test
    public void scheduleToTerminate_notRemoveIfEqualMin() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 1, 1, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 1, FleetStateStats.State.active(),
                Collections.singleton("z"), Collections.<String, Double>emptyMap()));

        // when
        boolean r = fleetCloud.scheduleToTerminate("z", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // then
        assertFalse(r);
    }

    @Test
    public void scheduleToTerminate_notRemoveIfEqualMinSpare() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));
        when(jenkins.getComputers()).thenReturn(new Computer[0]);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 5, 1, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 1, FleetStateStats.State.active(),
                Collections.singleton("z"), Collections.<String, Double>emptyMap()));

        // when
        boolean r = fleetCloud.scheduleToTerminate("z", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // then
        assertFalse(r);
    }

    @Test
    public void scheduleToTerminate_remove() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 1, 1, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 2, FleetStateStats.State.active(),
                new HashSet<>(Arrays.asList("z", "z1")), Collections.<String, Double>emptyMap()));

        // when
        boolean r = fleetCloud.scheduleToTerminate("z", false, EC2AgentTerminationReason.MAX_TOTAL_USES_EXHAUSTED);

        // then
        assertTrue(r);
        assertEquals(Collections.singletonMap("z", EC2AgentTerminationReason.MAX_TOTAL_USES_EXHAUSTED), fleetCloud.getInstanceIdsToTerminate());
    }

    @Test
    public void scheduleToTerminate_upToZeroNodes() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 1, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 2, FleetStateStats.State.active(),
                new HashSet<>(Arrays.asList("z-1", "z-2")), Collections.<String, Double>emptyMap()));

        // when
        boolean r1 = fleetCloud.scheduleToTerminate("z-1", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
        boolean r2 = fleetCloud.scheduleToTerminate("z-2", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // then
        assertTrue(r1);
        assertTrue(r2);
        assertEquals(new HashMap<String, EC2AgentTerminationReason>(){{
            put("z-1", EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
            put("z-2", EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
        }}, fleetCloud.getInstanceIdsToTerminate());
    }

    @Test
    public void scheduleToTerminate_removeNoMoreMinIfCalledMultipleBeforeUpdate() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 1, 1, 0, 1, true,
                false, "-1", false, 0, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 3, FleetStateStats.State.active(),
                new HashSet<>(Arrays.asList("z1", "z2", "z3")), Collections.<String, Double>emptyMap()));

        // when
        boolean r1 = fleetCloud.scheduleToTerminate("z1", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
        boolean r2 = fleetCloud.scheduleToTerminate("z2", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
        boolean r3 = fleetCloud.scheduleToTerminate("z3", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // then
        assertTrue(r1);
        assertTrue(r2);
        assertFalse(r3);
        assertEquals(new HashMap<String, EC2AgentTerminationReason>(){{
            put("z1", EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
            put("z2", EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
        }}, fleetCloud.getInstanceIdsToTerminate());
    }

    @Test
    public void update_shouldDoNothingIfNoTerminationOrProvisionAndFleetIsEmpty() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, null, false,
                false, 0, 0, 1, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, noScaling);

        // when
        FleetStateStats stats = fleetCloud.update();

        // then
        assertEquals(0, stats.getNumDesired());
        assertEquals(0, stats.getNumActive());
        assertEquals("fleetId", stats.getFleetId());
    }

    @Test
    public void update_shouldIncreaseTargetCapacityWhenProvisioned() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 0, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        fleetCloud.provision(new Cloud.CloudState(null, 0), 2);

        // when
        fleetCloud.update();

        // then
        verify(ec2Fleet).modify(anyString(), anyString(), anyString(), eq("fleetId"), eq(2), eq(0), eq(10));
    }

    @Test
    public void update_shouldResetTerminateAndProvision() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final FleetStateStats currentState = new FleetStateStats("fleetId", 5, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap());
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(currentState);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, noScaling);

        fleetCloud.setStats(currentState);

        fleetCloud.provision(new Cloud.CloudState(null, 0), 2);
        fleetCloud.scheduleToTerminate("i-1", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // when
        fleetCloud.update();

        // then
        verify(ec2Fleet).modify(anyString(), anyString(), anyString(), eq("fleetId"), eq(6), eq(0), eq(10));
        assertEquals(0, fleetCloud.getInstanceIdsToTerminate().size());
        assertEquals(0, fleetCloud.getToAdd());
    }

    @Test
    public void update_shouldNotIncreaseMoreThenMax() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 5, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        for (int i = 0; i < 10; i++) fleetCloud.provision(new Cloud.CloudState(null, 0), 1);
        for (int i = 0; i < 10; i++) fleetCloud.scheduleToTerminate("i-" + i, false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
        for (int i = 0; i < 10; i++) fleetCloud.provision(new Cloud.CloudState(null, 0), 1);

        // when
        fleetCloud.update();

        // then
        verify(ec2Fleet).modify(anyString(), anyString(), anyString(), eq("fleetId"), eq(0), eq(0), eq(10));
        assertEquals(0, fleetCloud.getInstanceIdsToTerminate().size());
        assertEquals(0, fleetCloud.getToAdd());
    }

    @Test
    public void update_shouldNotCountScheduledToTerminateWhenScaleUp() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 5, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 5, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        for (int i = 0; i < 10; i++) fleetCloud.provision(new Cloud.CloudState(null, 0), 1);
        for (int i = 0; i < 5; i++) fleetCloud.scheduleToTerminate("i-" + i, false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // when
        fleetCloud.update();

        // then
        verify(ec2Fleet).modify(anyString(), anyString(), anyString(), eq("fleetId"), eq(5), eq(0), eq(10));
        assertEquals(0, fleetCloud.getInstanceIdsToTerminate().size());
        assertEquals(0, fleetCloud.getToAdd());
    }

    @Test
    public void update_shouldDecreaseTargetCapacityAndTerminateInstancesIfScheduled() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 4, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, null, false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 4, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        fleetCloud.scheduleToTerminate("i-1", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
        fleetCloud.scheduleToTerminate("i-2", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // when
        fleetCloud.update();

        // then
        verify(ec2Fleet).modify(anyString(), anyString(), anyString(), eq("fleetId"), eq(2), eq(0), eq(10));
        verify(ec2Api).terminateInstances(amazonEC2, new HashSet<>(Arrays.asList("i-1", "i-2")));
    }

    @Test
    public void update_shouldAddNodeIfAnyNewDescribed() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceId("i-0");

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                false, "-1", false,
                0, 0, 10, false, false, noScaling);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        FleetStateStats stats = fleetCloud.update();

        // then
        assertEquals(0, stats.getNumDesired());
        assertEquals(1, stats.getNumActive());
        assertEquals("fleetId", stats.getFleetId());

        // and
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(Node.Mode.NORMAL, actualFleetNode.getMode());
    }

    @Test
    public void update_shouldTagNewNodesBeforeAdding() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final Instance instance1 = new Instance().withPublicIpAddress("p-ip").withInstanceId("i-0");
        final Instance instance2 = new Instance().withPublicIpAddress("p-ip").withInstanceId("i-1");
        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance1);
        instanceIdMap.put("i-1", instance2);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        new HashSet<>(Arrays.asList("i-0", "i-1")), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 2, 0, 1, false,
                false, "-1", false,
                0, 0, 10, false, false, noScaling);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        verify(ec2Api).tagInstances(amazonEC2, new HashSet<>(Arrays.asList("i-0", "i-1")), "ec2-fleet-plugin:cloud-name", "TestCloud");
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(Node.Mode.NORMAL, actualFleetNode.getMode());
    }

    @Test
    public void update_shouldTagNewNodesBeforeAddingWithFleetName() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final Instance instance1 = new Instance().withPublicIpAddress("p-ip").withInstanceId("i-0");
        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance1);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("my-fleet", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                false, "-1", false,
                0, 0, 10, false, false, noScaling);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        verify(ec2Api).tagInstances(amazonEC2, Collections.singleton("i-0"), "ec2-fleet-plugin:cloud-name", "my-fleet");
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(Node.Mode.NORMAL, actualFleetNode.getMode());
    }

    @Test
    public void update_givenFailedTaggingShouldIgnoreExceptionAndAddNode() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);
        doThrow(new UnsupportedOperationException("testexception"))
                .when(ec2Api).tagInstances(any(AmazonEC2.class), any(Set.class), anyString(), anyString());

        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceId("i-0");

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                false, "-1", false,
                0, 0, 10, false, false, noScaling);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        verify(ec2Api).tagInstances(amazonEC2, Collections.singleton("i-0"), "ec2-fleet-plugin:cloud-name", "TestCloud");
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(Node.Mode.NORMAL, actualFleetNode.getMode());
    }

    @Test
    public void update_shouldAddNodeIfAnyNewDescribed_restrictUsage() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceId("i-0");
        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, noScaling);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        FleetStateStats stats = fleetCloud.update();

        // then
        assertEquals(0, stats.getNumDesired());
        assertEquals(1, stats.getNumActive());
        assertEquals("fleetId", stats.getFleetId());

        // and
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(Node.Mode.EXCLUSIVE, actualFleetNode.getMode());
    }

    @Test
    public void update_shouldAddNodeWithNumExecutors_whenWeightProvidedButNotEnabled() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final String instanceType = "t";
        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceType(instanceType)
                .withInstanceId("i-0");
        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"),
                        Collections.singletonMap(instanceType, 1.1)));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, noScaling);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(1, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_givenManuallyUpdatedFleetShouldCorrectLocalTargetCapacityToKeepZeroOrPositive() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                Collections.emptyMap());

        final FleetStateStats initState = new FleetStateStats("fleetId", 0,
                FleetStateStats.State.active(),
                new HashSet<>(Arrays.asList("i-0", "i-1")), Collections.<String, Double>emptyMap());
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(initState);

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, noScaling);
        fleetCloud.setStats(initState);

        doNothing().when(jenkins).addNode(any(Node.class));

        fleetCloud.scheduleToTerminate("i-0", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
        fleetCloud.scheduleToTerminate("i-1", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // when
        fleetCloud.update();

        // then
        // should reset list to empty
        verify(ec2Fleet).modify(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());
        Assert.assertEquals(0, fleetCloud.getPlannedNodesCache().size());
    }

    @Test
    public void update_shouldTrimPlannedNodesIfExceedTargetCapacity() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                Collections.emptyMap());

        final FleetStateStats initState = new FleetStateStats("fleetId", 0,
                FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap());
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(initState);

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, noScaling);
        fleetCloud.setStats(initState);

        doNothing().when(jenkins).addNode(any(Node.class));

        // when
        Collection<NodeProvisioner.PlannedNode> plannedNodes = fleetCloud.provision(new Cloud.CloudState(null, 0), 10);
        Assert.assertEquals(10, plannedNodes.size());
        for (NodeProvisioner.PlannedNode plannedNode : plannedNodes) {
            Assert.assertFalse(plannedNode.future.isCancelled());
        }

        // try to modify and reset to add
        fleetCloud.update();
        // reset to old empty state
        fleetCloud.setStats(initState);
        fleetCloud.update();

        // then
        // should reset list to empty
        Assert.assertEquals(0, fleetCloud.getPlannedNodesCache().size());
        // make sure all trimmed planned nodes were cancelled
        Assert.assertEquals(10, plannedNodes.size());
        for (NodeProvisioner.PlannedNode plannedNode : plannedNodes) {
            Assert.assertTrue("Planned node should be cancelled", plannedNode.future.isCancelled());
        }
    }

    @Test
    public void update_shouldTrimPlannedNodesBasedOnUpdatedTargetCapacityIfProvisionCalledInBetween() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                Collections.emptyMap());

        final FleetStateStats initState = new FleetStateStats("fleetId", 0,
                FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap());
        when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(initState);

        mockNodeCreatingPart();

        final EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, noScaling);
        fleetCloud.setStats(initState);

        doNothing().when(jenkins).addNode(any(Node.class));

        fleetCloud.provision(new Cloud.CloudState(null, 0), 1);

        // intercept modify operation to emulate call of provision during update method
        doAnswer(new Answer() {
            @Override
            public Object answer(InvocationOnMock invocation) {
                fleetCloud.provision(new Cloud.CloudState(null, 0), 1);
                return null;
            }
        }).when(ec2Fleet).modify(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());

        // when
        fleetCloud.update();

        // then
        // should be two, planned one added before update another during update
        Assert.assertEquals(2, fleetCloud.getPlannedNodesCache().size());
    }

    @Test
    public void update_shouldUpdateStateWithFleetTargetCapacityPlusToAdd() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final String instanceType = "t";
        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceType(instanceType)
                .withInstanceId("i-0");
        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        final FleetStateStats initState = new FleetStateStats("fleetId", 5,
                FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap());
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(initState);

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0,1, false,
                true, "-1", false,
                0, 0, 10, false, false, noScaling);
        fleetCloud.setStats(initState);

        doNothing().when(jenkins).addNode(any(Node.class));

        fleetCloud.provision(new Cloud.CloudState(null, 0), 2);

        // when
        fleetCloud.update();

        // then
        Assert.assertEquals(7, fleetCloud.getStats().getNumDesired());
    }

    /**
     * See {@link EC2FleetCloudTest#update_shouldUpdateStateWithFleetTargetCapacityPlusToAdd()}
     */
    @Test
    public void update_shouldUpdateStateWithFleetTargetCapacityMinusToTerminate() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final String instanceType = "t";
        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceType(instanceType)
                .withInstanceId("i-0");

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        final FleetStateStats initState = new FleetStateStats("fleetId", 5,
                FleetStateStats.State.active(),
                Collections.singleton("i-0"), Collections.<String, Double>emptyMap());
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(initState);

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10,0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, noScaling);
        fleetCloud.setStats(initState);

        doNothing().when(jenkins).addNode(any(Node.class));

        fleetCloud.scheduleToTerminate("i-0", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);

        // when
        fleetCloud.update();

        // then
        Assert.assertEquals(4, fleetCloud.getStats().getNumDesired());
    }

    /**
     * For context, see https://github.com/jenkinsci/ec2-fleet-plugin/issues/363
     */
    @Test
    public void update_shouldTerminateIdleOrNullInstancesOnly() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);
        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(new HashMap<String, Instance>(){{
                put("i-1", new Instance().withPublicIpAddress("p-ip").withInstanceId("i-1"));
                put("i-2", new Instance().withPublicIpAddress("p-ip").withInstanceId("i-2"));
                put("i-3", new Instance().withPublicIpAddress("p-ip").withInstanceId("i-3"));
            }});
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        new HashSet<>(Arrays.asList("i-1", "i-2", "i-3")), Collections.<String, Double>emptyMap()));
        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 2, 0, 1, false,
                false, "-1", false,
                0, 0, 10, false, false, noScaling);

        when(jenkins.getComputer("i-1")).thenReturn(idleComputer);
        when(jenkins.getComputer("i-2")).thenReturn(busyComputer);
        when(jenkins.getComputer("i-3")).thenReturn(null);

        // when
        fleetCloud.scheduleToTerminate("i-1", false, EC2AgentTerminationReason.IDLE_FOR_TOO_LONG);
        fleetCloud.scheduleToTerminate("i-2", true, EC2AgentTerminationReason.MAX_TOTAL_USES_EXHAUSTED);
        fleetCloud.scheduleToTerminate("i-3", false, EC2AgentTerminationReason.AGENT_DELETED);

        // then - verify both instances were scheduled for termination
        assertEquals(new HashSet<>(Arrays.asList("i-1", "i-2", "i-3")), fleetCloud.getInstanceIdsToTerminate().keySet());

        // when
        fleetCloud.update();

        // then - i-2 remains scheduled for termination, for next update cycle as it is busy
        verify(ec2Api).terminateInstances(amazonEC2, new HashSet<>(Arrays.asList("i-1", "i-3")));
        assertEquals(new HashSet<>(Arrays.asList("i-2")), fleetCloud.getInstanceIdsToTerminate().keySet());
    }

    @Test
    public void update_shouldUpdateStateWithMinSpare() throws IOException {
        // given
        final int minSpareSize = 2;
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);
        when(jenkins.getComputers()).thenReturn(new Computer[0]);

        final FleetStateStats initState = new FleetStateStats("fleetId", 0,
                FleetStateStats.State.active(),
                Collections.emptySet(), Collections.<String, Double>emptyMap());
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(initState);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, minSpareSize, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, noScaling);
        fleetCloud.setStats(initState);

        doNothing().when(jenkins).addNode(any(Node.class));

        // when
        fleetCloud.update();

        // then
        Assert.assertEquals(minSpareSize, fleetCloud.getStats().getNumDesired());
    }

    @Test
    public void update_shouldAddNodeWithScaledNumExecutors_whenWeightPresentAndEnabled() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final String instanceType = "t";
        final String instanceId = "i-0";
        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceType(instanceType)
                .withInstanceId(instanceId);

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put(instanceId, instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton(instanceId),
                        Collections.singletonMap(instanceType, 2.0)));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(2, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_shouldAddNodeWithNumExecutors_whenWeightPresentAndEnabledButForDiffType() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final String instanceType = "t";
        final String instanceId = "i-0";
        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceType(instanceType)
                .withInstanceId(instanceId);

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put(instanceId, instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton(instanceId),
                        Collections.singletonMap("diff-t", 2.0)));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(1, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_shouldAddNodeWithRoundToLowScaledNumExecutors_whenWeightPresentAndEnabled() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final String instanceType = "t";
        final String instanceId = "i-0";
        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceType(instanceType)
                .withInstanceId(instanceId);

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put(instanceId, instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton(instanceId),
                        Collections.singletonMap(instanceType, 1.44)));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(1, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_shouldAddNodeWithRoundToLowScaledNumExecutors_whenWeightPresentAndEnabled1() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final String instanceType = "t";
        final String instanceId = "i-0";
        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceType(instanceType)
                .withInstanceId(instanceId);

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put(instanceId, instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton(instanceId),
                        Collections.singletonMap(instanceType, 1.5)));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);

        ArgumentCaptor<EC2FleetNode> nodeCaptor = ArgumentCaptor.forClass(EC2FleetNode.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(2, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_shouldAddNodeWithScaledToOneNumExecutors_whenWeightPresentButLessOneAndEnabled() {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final String instanceType = "t";
        final String instanceId = "i-0";
        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceType(instanceType)
                .withInstanceId(instanceId);

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put(instanceId, instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton(instanceId),
                        Collections.<String, Double>emptyMap()));

        Mockito.doThrow(new UnsupportedOperationException("Test exception")).when(ec2Fleet)
                .modify(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt(), anyInt());

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);
        // set init state so we can do provision
        fleetCloud.setStats(new FleetStateStats("", 0, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));
        // run provision
        fleetCloud.provision(new Cloud.CloudState(null, 0), 1);

        // when
        try {
            fleetCloud.update();
            Assert.fail("Where is my exception?");
        } catch (UnsupportedOperationException e) {
            Assert.assertEquals(1, fleetCloud.getToAdd());
        }
    }

    @Test
    public void update_givenFailedModifyShouldNotUpdateToAddToDelete() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final String instanceType = "t";
        final String instanceId = "i-0";
        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceType(instanceType)
                .withInstanceId(instanceId);

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put(instanceId, instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton(instanceId),
                        Collections.singletonMap(instanceType, .1)));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(1, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_givenFleetInModifyingShouldNotDoAnyUpdates() throws IOException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final String instanceType = "t";
        final String instanceId = "i-0";
        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceType(instanceType)
                .withInstanceId(instanceId);

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put(instanceId, instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        final FleetStateStats stats = new FleetStateStats("fleetId", 0,
                FleetStateStats.State.modifying(""),
                Collections.singleton(instanceId),
                Collections.singletonMap(instanceType, .1));
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString())).thenReturn(stats);

        final FleetStateStats initialState = new FleetStateStats("fleetId", 0,
                FleetStateStats.State.active(),
                Collections.singleton(instanceId),
                Collections.singletonMap(instanceType, .1));

        mockNodeCreatingPart();

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);
        fleetCloud.setStats(initialState);

        doNothing().when(jenkins).addNode(any(Node.class));

        // when
        FleetStateStats newStats = fleetCloud.update();

        // then
        Assert.assertSame(initialState, newStats);
        Assert.assertSame(initialState, fleetCloud.getStats());
        verify(ec2Fleet, never()).modify(any(String.class), any(String.class), any(String.class), any(String.class), anyInt(), anyInt(), anyInt());
        verify(jenkins, never()).addNode(any(Node.class));
    }

    @Test
    public void update_scheduledFuturesExecutesAfterTimeout() throws IOException, InterruptedException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        final int timeout = 1;

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 10, 0,1, true,
                false, "-1", false, timeout, 0,
                1, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 5, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        // when
        Collection<NodeProvisioner.PlannedNode> r = fleetCloud.provision(new Cloud.CloudState(null, 0), 1);
        ScheduledFuture<?> scheduledFuture = fleetCloud.getPlannedNodeScheduledFutures().get(0);

        // sleep for a little more than the timeout to let the scheduled future execute
        Thread.sleep(TimeUnit.SECONDS.toMillis(fleetCloud.getScheduledFutureTimeoutSec()) + 200);

        // then
        Assert.assertTrue(scheduledFuture.isDone());
    }

    @Test
    public void update_scheduledFuturesIsCancelledAfterUpdate() throws IOException, InterruptedException {
        // given
        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        final int timeout = 1;

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "", "", null, null, false,
                false, 0, 0, 10, 0,1, true,
                false, "-1", false, timeout, 0,
                10, false, false, noScaling);

        fleetCloud.setStats(new FleetStateStats("", 5, FleetStateStats.State.active(),
                Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        // when
        Collection<NodeProvisioner.PlannedNode> r = fleetCloud.provision(new Cloud.CloudState(null, 0), 1);
        ScheduledFuture<?> scheduledFuture = fleetCloud.getPlannedNodeScheduledFutures().get(0);

        // call update before the timeout expires
        fleetCloud.update();

        // then
        Assert.assertTrue(scheduledFuture.isCancelled());
    }

    @Test
    public void update_shouldScaleUpToMinSize() {
        // given
        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("", 0, FleetStateStats.State.active(),
                        Collections.<String>emptySet(), Collections.<String, Double>emptyMap()));

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 1, 1, 0,1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);

        // when
        fleetCloud.update();

        // then
        Assert.assertEquals(fleetCloud.getStats().getNumDesired(), 1);
    }

    @Test
    public void update_whenScalingByNodeHardwareWithLessVCPUs_shouldScaleExecutorsByVCPUs() throws IOException {
        when(amazonEC2.describeInstanceTypes(any(DescribeInstanceTypesRequest.class)))
                .thenReturn(new DescribeInstanceTypesResult()
                        .withInstanceTypes(
                                new InstanceTypeInfo()
                                        .withMemoryInfo(
                                                new MemoryInfo()
                                                        .withSizeInMiB((long)4*MiB_TO_GiB_MULTIPLIER))
                                        .withVCpuInfo(
                                                new VCpuInfo()
                                                        .withDefaultVCpus(2))));

        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceId("i-0")
                .withState(new InstanceState()
                            .withName(InstanceStateName.Running));

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud.NodeHardwareScaler nodeHardwareScaler = new EC2FleetCloud.NodeHardwareScaler(1, 1);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, nodeHardwareScaler);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(2, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_whenScalingByNodeHardwareWithLessMemory_shouldScaleExecutorsByMemory() throws IOException {
        when(amazonEC2.describeInstanceTypes(any(DescribeInstanceTypesRequest.class)))
                .thenReturn(new DescribeInstanceTypesResult()
                        .withInstanceTypes(
                                new InstanceTypeInfo()
                                        .withMemoryInfo(
                                                new MemoryInfo()
                                                        .withSizeInMiB((long)6*MiB_TO_GiB_MULTIPLIER))
                                        .withVCpuInfo(
                                                new VCpuInfo()
                                                        .withDefaultVCpus(8))));

        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceId("i-0")
                .withState(new InstanceState()
                        .withName(InstanceStateName.Running));

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud.NodeHardwareScaler nodeHardwareScaler = new EC2FleetCloud.NodeHardwareScaler(2, 2);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, nodeHardwareScaler);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(3, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_whenScalingByNodeHardwareWithNoVCPUs_shouldScaleExecutorsByMemory() throws IOException {
        when(amazonEC2.describeInstanceTypes(any(DescribeInstanceTypesRequest.class)))
                .thenReturn(new DescribeInstanceTypesResult()
                        .withInstanceTypes(
                                new InstanceTypeInfo()
                                        .withMemoryInfo(
                                                new MemoryInfo()
                                                        .withSizeInMiB((long)4*MiB_TO_GiB_MULTIPLIER))
                                        .withVCpuInfo(
                                                new VCpuInfo()
                                                        .withDefaultVCpus(2))));

        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceId("i-0")
                .withState(new InstanceState()
                        .withName(InstanceStateName.Running));

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud.NodeHardwareScaler nodeHardwareScaler = new EC2FleetCloud.NodeHardwareScaler(0, 1);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, nodeHardwareScaler);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(4, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_whenScalingByNodeHardwareWithNoMemory_shouldScaleExecutorsByVCPUs() throws IOException {
        when(amazonEC2.describeInstanceTypes(any(DescribeInstanceTypesRequest.class)))
                .thenReturn(new DescribeInstanceTypesResult()
                        .withInstanceTypes(
                                new InstanceTypeInfo()
                                        .withMemoryInfo(
                                                new MemoryInfo()
                                                        .withSizeInMiB((long)3*MiB_TO_GiB_MULTIPLIER))
                                        .withVCpuInfo(
                                                new VCpuInfo()
                                                        .withDefaultVCpus(8))));

        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceId("i-0")
                .withState(new InstanceState()
                        .withName(InstanceStateName.Running));

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud.NodeHardwareScaler nodeHardwareScaler = new EC2FleetCloud.NodeHardwareScaler(2, 0);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, nodeHardwareScaler);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(4, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_whenScalingByNodeHardwareByMemoryWithLowMemory_shouldSetOneExecutor() throws IOException {
        when(amazonEC2.describeInstanceTypes(any(DescribeInstanceTypesRequest.class)))
                .thenReturn(new DescribeInstanceTypesResult()
                        .withInstanceTypes(
                                new InstanceTypeInfo()
                                        .withMemoryInfo(
                                                new MemoryInfo()
                                                        .withSizeInMiB((long)2*MiB_TO_GiB_MULTIPLIER))
                                        .withVCpuInfo(
                                                new VCpuInfo()
                                                        .withDefaultVCpus(2))));

        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceId("i-0")
                .withState(new InstanceState()
                        .withName(InstanceStateName.Running));

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud.NodeHardwareScaler nodeHardwareScaler = new EC2FleetCloud.NodeHardwareScaler(0, 4);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, nodeHardwareScaler);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(1, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_whenScalingByNodeHardwareByVCPUsWithLowVCPUCount_shouldSetOneExecutor() throws IOException {
        when(amazonEC2.describeInstanceTypes(any(DescribeInstanceTypesRequest.class)))
                .thenReturn(new DescribeInstanceTypesResult()
                        .withInstanceTypes(
                                new InstanceTypeInfo()
                                        .withMemoryInfo(
                                                new MemoryInfo()
                                                        .withSizeInMiB((long)4*MiB_TO_GiB_MULTIPLIER))
                                        .withVCpuInfo(
                                                new VCpuInfo()
                                                        .withDefaultVCpus(2))));

        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceId("i-0")
                .withState(new InstanceState()
                        .withName(InstanceStateName.Running));

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud.NodeHardwareScaler nodeHardwareScaler = new EC2FleetCloud.NodeHardwareScaler(5, 0);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0, 1, true,
                false, "-1", false, 0,
                0, 10, false, false, nodeHardwareScaler);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(1, actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_whenScalingByNodeHardwareWithNoVCPUsAndNoMemory_shouldSetExecutorsToNumExecutors() throws IOException {
        when(amazonEC2.describeInstanceTypes(any(DescribeInstanceTypesRequest.class)))
                .thenReturn(new DescribeInstanceTypesResult()
                        .withInstanceTypes(
                                new InstanceTypeInfo()
                                        .withMemoryInfo(
                                                new MemoryInfo()
                                                        .withSizeInMiB((long)4*MiB_TO_GiB_MULTIPLIER))
                                        .withVCpuInfo(
                                                new VCpuInfo()
                                                        .withDefaultVCpus(2))));

        when(ec2Api.connect(any(String.class), any(String.class), anyString())).thenReturn(amazonEC2);

        final Instance instance = new Instance()
                .withPublicIpAddress("p-ip")
                .withInstanceId("i-0")
                .withState(new InstanceState()
                        .withName(InstanceStateName.Running));

        final HashMap<String, Instance> instanceIdMap = new HashMap<>();
        instanceIdMap.put("i-0", instance);

        when(ec2Api.describeInstances(any(AmazonEC2.class), any(Set.class))).thenReturn(
                instanceIdMap);

        Mockito.when(ec2Fleet.getState(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new FleetStateStats("fleetId", 0, FleetStateStats.State.active(),
                        Collections.singleton("i-0"), Collections.<String, Double>emptyMap()));

        mockNodeCreatingPart();

        EC2FleetCloud.NodeHardwareScaler nodeHardwareScaler = new EC2FleetCloud.NodeHardwareScaler(0, 0);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 10, 0, 3, true,
                false, "-1", false, 0,
                0, 10, false, false, nodeHardwareScaler);

        ArgumentCaptor<Node> nodeCaptor = ArgumentCaptor.forClass(Node.class);
        doNothing().when(jenkins).addNode(nodeCaptor.capture());

        // when
        fleetCloud.update();

        // then
        Node actualFleetNode = nodeCaptor.getValue();
        assertEquals(fleetCloud.getNumExecutors(), actualFleetNode.getNumExecutors());
    }

    @Test
    public void update_shouldTerminateInstancesInAutoScalingGroup() throws IOException, IllegalAccessException, NoSuchFieldException {
        // Arrange
        final AutoScalingGroupFleet autoScalingGroupFleet = mock(AutoScalingGroupFleet.class);
        when(EC2Fleets.get(anyString())).thenReturn(autoScalingGroupFleet);
        when(autoScalingGroupFleet.isAutoScalingGroup()).thenReturn(true);

        final FleetStateStats stats = new FleetStateStats("fleetId", 1, FleetStateStats.State.active(),
                Collections.singleton("i-0"), Collections.<String, Double>emptyMap());
        when(autoScalingGroupFleet.getState(anyString(), any(), any(), anyString())).thenReturn(stats);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                null, "fleetId", null, null, mock(ComputerConnector.class), false, false,
                0, 0, 10, 0, 1, false, false, null, false, null, null, null, false, false, null);

        // Set up instanceIdsToTerminate
        HashMap<String, EC2AgentTerminationReason> toTerminate = new HashMap<>();
        toTerminate.put("i-0", EC2AgentTerminationReason.MAX_TOTAL_USES_EXHAUSTED);
        fleetCloud.setStats(stats);
        Field field = EC2FleetCloud.class.getDeclaredField("instanceIdsToTerminate");
        field.setAccessible(true);
        field.set(fleetCloud, toTerminate);

        // Act
        fleetCloud.update();

        // Assert
        verify(autoScalingGroupFleet).terminateInstances(anyString(), any(), any(), eq(Collections.singleton("i-0")));
    }

    @Test
    public void update_shouldTerminateInstancesInEC2Api() throws IOException, NoSuchFieldException, IllegalAccessException {
        // Arrange
        final EC2Fleet ec2Fleet = mock(EC2Fleet.class);
        when(EC2Fleets.get(anyString())).thenReturn(ec2Fleet);
        when(ec2Fleet.isAutoScalingGroup()).thenReturn(false);

        final AmazonEC2 amazonEC2 = mock(AmazonEC2.class);
        when(Registry.getEc2Api().connect(anyString(), any(), any())).thenReturn(amazonEC2);

        final FleetStateStats stats = new FleetStateStats("fleetId", 1, FleetStateStats.State.active(),
                Collections.singleton("i-0"), Collections.<String, Double>emptyMap());
        when(ec2Fleet.getState(anyString(), any(), any(), anyString())).thenReturn(stats);

        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                null, "fleetId", null, null, mock(ComputerConnector.class), false, false,
                0, 0, 10, 0, 1, false, false, null, false, null, null, null, false, false, null);

        // Set up instanceIdsToTerminate
        HashMap<String, EC2AgentTerminationReason> toTerminate = new HashMap<>();
        toTerminate.put("i-0", EC2AgentTerminationReason.MAX_TOTAL_USES_EXHAUSTED);
        fleetCloud.setStats(stats);
        Field field = EC2FleetCloud.class.getDeclaredField("instanceIdsToTerminate");
        field.setAccessible(true);
        field.set(fleetCloud, toTerminate);

        // Act
        fleetCloud.update();

        // Assert
        verify(Registry.getEc2Api()).terminateInstances(eq(amazonEC2), eq(Collections.singleton("i-0")));
    }

    @Test
    public void removeScheduledFutures_success() {
        // given
        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);

        ArrayList<ScheduledFuture<?>> scheduledFutures = new ArrayList<>();
        scheduledFutures.add(mock(ScheduledFuture.class));
        fleetCloud.setPlannedNodeScheduledFutures(scheduledFutures);

        // when
        boolean result = fleetCloud.removePlannedNodeScheduledFutures(1);

        // then
        Assert.assertEquals(0, fleetCloud.getPlannedNodeScheduledFutures().size());
        Assert.assertTrue(result);
    }

    @Test
    public void removeScheduledFutures_scheduledFutureIsEmpty() {
        // given
        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);

        ArrayList<ScheduledFuture<?>> scheduledFutures = new ArrayList<>();
        fleetCloud.setPlannedNodeScheduledFutures(scheduledFutures);

        // when
        boolean result = fleetCloud.removePlannedNodeScheduledFutures(1);

        // then
        Assert.assertFalse(result);
    }

    @Test
    public void removeScheduledFutures_numToRemoveIsZero() {
        // given
        EC2FleetCloud fleetCloud = new EC2FleetCloud("TestCloud", "credId", null, "region",
                "", "fleetId", "", null, Mockito.mock(ComputerConnector.class), false,
                false, 0, 0, 1, 0, 1, false,
                true, "-1", false,
                0, 0, 10, false, false, weightedScaling);

        ArrayList<ScheduledFuture<?>> scheduledFutures = new ArrayList<>();
        scheduledFutures.add(mock(ScheduledFuture.class));
        fleetCloud.setPlannedNodeScheduledFutures(scheduledFutures);

        // when
        boolean result = fleetCloud.removePlannedNodeScheduledFutures(0);

        // then
        Assert.assertEquals(1, fleetCloud.getPlannedNodeScheduledFutures().size());
        Assert.assertFalse(result);
    }

    @Test
    public void descriptorImpl_doFillRegionItems_returnStaticRegionsIfApiCallFailed() {
        AmazonEC2Client amazonEC2Client = mock(AmazonEC2Client.class);
        when(ec2Api.connect(anyString(), anyString(), anyString())).thenReturn(amazonEC2Client);

        ListBoxModel r = new EC2FleetCloud.DescriptorImpl().doFillRegionItems("");
        HashSet<String> staticRegions = new HashSet<>(RegionInfo.getRegionNames());
        staticRegions.addAll(RegionUtils.getRegions().stream().map(com.amazonaws.regions.Region::getName).collect(Collectors.toSet()));

        Assert.assertThat(staticRegions.size(), Matchers.greaterThan(0));
        assertEquals(staticRegions.size(), r.size());
    }

    @Test
    public void descriptorImpl_doTestConnection_NoMissingPermissions() throws Exception {
        try (MockedConstruction<AwsPermissionChecker> mockedAwsPermissionChecker = Mockito.mockConstruction(AwsPermissionChecker.class, (awsPermissionChecker, context) -> {
            when(awsPermissionChecker.getMissingPermissions(null)).thenReturn(new ArrayList<>());
        })) {
            final FormValidation formValidation = new EC2FleetCloud.DescriptorImpl().doTestConnection("credentials", null, null, null);

            Assert.assertTrue(formValidation.getMessage().contains("Success"));
        }
    }

    @Test
    public void descriptorImpl_doTestConnection_missingDescribeInstancePermission() throws Exception {
        try (MockedConstruction<AwsPermissionChecker> mockedAwsPermissionChecker = Mockito.mockConstruction(AwsPermissionChecker.class, (awsPermissionChecker, context) -> {
            when(awsPermissionChecker.getMissingPermissions(null)).thenReturn(Collections.singletonList(AwsPermissionChecker.FleetAPI.DescribeInstances.name()));
        })) {
            final FormValidation formValidation = new EC2FleetCloud.DescriptorImpl().doTestConnection("credentials", null, null, null);

            Assert.assertThat(formValidation.getMessage(), Matchers.containsString(AwsPermissionChecker.FleetAPI.DescribeInstances.name()));
        }
    }

    @Test
    public void descriptorImpl_doTestConnection_missingMultiplePermissions() throws Exception {
        try (MockedConstruction<AwsPermissionChecker> mockedAwsPermissionChecker = Mockito.mockConstruction(AwsPermissionChecker.class, (awsPermissionChecker, context) -> {
            final List<String> missingPermissions = new ArrayList<>();
            missingPermissions.add(AwsPermissionChecker.FleetAPI.DescribeInstances.name());
            missingPermissions.add(AwsPermissionChecker.FleetAPI.CreateTags.name());

            when(awsPermissionChecker.getMissingPermissions(null)).thenReturn(missingPermissions);
        })) {
            final FormValidation formValidation = new EC2FleetCloud.DescriptorImpl().doTestConnection("credentials", null, null, null);

            Assert.assertThat(formValidation.getMessage(), Matchers.containsString(AwsPermissionChecker.FleetAPI.DescribeInstances.name()));
            Assert.assertThat(formValidation.getMessage(), Matchers.containsString(AwsPermissionChecker.FleetAPI.CreateTags.name()));
        }
    }

    @Test
    public void descriptorImpl_doFillRegionItems_returnStaticRegionsAndDynamic() {
        AmazonEC2Client amazonEC2Client = mock(AmazonEC2Client.class);
        when(ec2Api.connect(anyString(), nullable(String.class), nullable(String.class))).thenReturn(amazonEC2Client);
        when(amazonEC2Client.describeRegions()).thenReturn(new DescribeRegionsResult().withRegions(new Region().withRegionName("dynamic-region")));

        ListBoxModel r = new EC2FleetCloud.DescriptorImpl().doFillRegionItems("");
        HashSet<String> staticRegions = new HashSet<>(RegionInfo.getRegionNames());
        staticRegions.addAll(RegionUtils.getRegions().stream().map(com.amazonaws.regions.Region::getName).collect(Collectors.toSet()));

        Assert.assertThat(r.size(), Matchers.greaterThan(0));
        Assert.assertThat(r.toString(), Matchers.containsString("dynamic-region"));
        assertEquals(staticRegions.size() + 1, r.size());
    }

    @Test
    public void descriptorImpl_doFillRegionItems_shouldDisplayRegionCodeWhenRegionDescriptionMissing() {
        final String dynamicRegion = "dynamic-region";
        AmazonEC2Client amazonEC2Client = mock(AmazonEC2Client.class);
        when(ec2Api.connect(anyString(), nullable(String.class), nullable(String.class))).thenReturn(amazonEC2Client);
        when(amazonEC2Client.describeRegions()).thenReturn(new DescribeRegionsResult().withRegions(new Region().withRegionName(dynamicRegion)));

        final ListBoxModel regionsListBoxModel = new EC2FleetCloud.DescriptorImpl().doFillRegionItems("");
        boolean isPresent = false;

        for (final ListBoxModel.Option item : regionsListBoxModel) {
            if (StringUtils.equals(item.value, dynamicRegion)) {
                isPresent = true;
                // verify that display name is same when description is missing
                assertEquals(dynamicRegion, item.name);
            }
        }
        if(!isPresent) {
            fail("Dynamic Region not added to the list");
        }
    }

    @Test
    public void descriptorImpl_doFillRegionItems_shouldDisplayVirginiaDescription() {
        final String regionName = "us-east-1";
        final String displayName = "us-east-1 US East (N. Virginia)";
        AmazonEC2Client amazonEC2Client = mock(AmazonEC2Client.class);
        when(ec2Api.connect(anyString(), nullable(String.class), nullable(String.class))).thenReturn(amazonEC2Client);
        when(amazonEC2Client.describeRegions()).thenReturn(new DescribeRegionsResult().withRegions(new Region().withRegionName(regionName)));

        final ListBoxModel regionsListBoxModel = new EC2FleetCloud.DescriptorImpl().doFillRegionItems("");
        boolean isPresent = false;

        for (final ListBoxModel.Option item : regionsListBoxModel) {
            if (StringUtils.equals(item.value, regionName)) {
                isPresent = true;
                assertEquals(displayName, item.name);
            }
        }
        if(!isPresent) {
            fail(String.format("%s not added to the region list", regionName));
        }
    }

    @Test
    public void descriptorImpl_doFillRegionItems_returnConsistOrderBetweenCalls() {
        AmazonEC2Client amazonEC2Client = mock(AmazonEC2Client.class);
        when(ec2Api.connect(anyString(), nullable(String.class), nullable(String.class))).thenReturn(amazonEC2Client);
        when(amazonEC2Client.describeRegions()).thenReturn(new DescribeRegionsResult().withRegions(new Region().withRegionName("dynamic-region")));

        ListBoxModel r1 = new EC2FleetCloud.DescriptorImpl().doFillRegionItems("");
        ListBoxModel r2 = new EC2FleetCloud.DescriptorImpl().doFillRegionItems("");
        ListBoxModel r3 = new EC2FleetCloud.DescriptorImpl().doFillRegionItems("");

        assertEquals(r1.toString(), r2.toString());
        assertEquals(r2.toString(), r3.toString());
    }

    @Test
    public void descriptorImpl_doCheckFleetName_validName() {
        FormValidation formValidation = new EC2FleetCloud.DescriptorImpl().doCheckFleet("FleetCloud");
        assertEquals(formValidation.kind, Kind.OK);
    }

    @Test
    public void descriptorImpl_doCheckFleetName_invalidName() {
        FormValidation formValidation = new EC2FleetCloud.DescriptorImpl().doCheckFleet(null);
        assertEquals(formValidation.kind, Kind.ERROR);
    }

    @Test
    public void descriptorImpl_doFillFleetItems_returnEmptyListIfNoEmptyEC2Fleet() {
        ListBoxModel r = new EC2FleetCloud.DescriptorImpl().doFillFleetItems(
                false, "", "", "", "");

        assertEquals(1, r.size());
        assertEquals("", r.get(0).value);
    }

    @Test
    public void descriptorImpl_doFillFleetItems_returnFleetsProvidedByAllEC2Fleets() {
        final EC2Fleet ec2SpotFleet = mock(EC2SpotFleet.class);
        final EC2Fleet autoScalingGroupFleet = mock(AutoScalingGroupFleet.class);
        mockedEc2Fleets.when(EC2Fleets::all).thenReturn(Arrays.asList(ec2SpotFleet, autoScalingGroupFleet));

        ListBoxModel r = new EC2FleetCloud.DescriptorImpl().doFillFleetItems(
                false, "", "", "", "");

        assertEquals(1, r.size());
        assertEquals("", r.get(0).value);
        verify(ec2SpotFleet).describe("", "", "", r, "", false);
        verify(autoScalingGroupFleet).describe("", "", "", r, "", false);
    }

    @Test
    public void descriptorImpl_doFillFleetItems_returnEmptyListIfAnyException() {
        final EC2Fleet ec2SpotFleet = mock(EC2SpotFleet.class);
        doThrow(new RuntimeException("test")).when(ec2SpotFleet).describe(
                anyString(), anyString(), anyString(), any(ListBoxModel.class), anyString(), anyBoolean());

        final EC2Fleet autoScalingGroupFleet = mock(AutoScalingGroupFleet.class);
        mockedEc2Fleets.when(EC2Fleets::all).thenReturn(Arrays.asList(ec2SpotFleet, autoScalingGroupFleet));

        ListBoxModel r = new EC2FleetCloud.DescriptorImpl().doFillFleetItems(
                false, "", "", "", "");

        assertEquals(1, r.size());
        assertEquals("", r.get(0).value);
    }

    @Test
    public void descriptorImpl_doCheckFleet_default() {
        FormValidation formValidation = new EC2FleetCloud.DescriptorImpl().doCheckFleet("");
        assertEquals(formValidation.kind, Kind.ERROR);
    }

    @Test
    public void descriptorImpl_doCheckFleet_nonDefault() {
        FormValidation formValidation = new EC2FleetCloud.DescriptorImpl().doCheckFleet("ASG1");
        assertEquals(formValidation.kind, Kind.OK);

    }

    @Test
    public void getDisplayName_returnDisplayName() {
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "CloudName", null, null, null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                1, true, false, "-1", false
                , 0, 0,
                10, false, false, noScaling);
        assertEquals(ec2FleetCloud.getDisplayName(), "CloudName");
    }

    @Test
    public void getAwsCredentialsId_returnNull_whenNoCredentialsIdOrAwsCredentialsId() {
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "TestCloud",  null, null, null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                1, true, false, "-1", false,
                0, 0,
                10, false, false, noScaling);
        Assert.assertNull(ec2FleetCloud.getAwsCredentialsId());
    }

    @Test
    public void getAwsCredentialsId_returnValue_whenCredentialsIdPresent() {
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "TestCloud", null, "Opa", null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                1, true, false, "-1", false
                , 0, 0,
                10, false, false, noScaling);
        assertEquals("Opa", ec2FleetCloud.getAwsCredentialsId());
    }

    @Test
    public void getAwsCredentialsId_returnValue_whenAwsCredentialsIdPresent() {
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "TestCloud", "Opa", null, null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                1, true, false, "-1", false
                , 0, 0,
                10, false, false, noScaling);
        assertEquals("Opa", ec2FleetCloud.getAwsCredentialsId());
    }

    @Test
    public void getAwsCredentialsId_returnAwsCredentialsId_whenAwsCredentialsIdAndCredentialsIdPresent() {
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "TestCloud", "A", "B", null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                1, true, false, "-1", false
                , 0, 0,
                10, false, false, noScaling);
        assertEquals("A", ec2FleetCloud.getAwsCredentialsId());
    }

    // todo create test cases update failed to modify fleet

    @Test
    public void getCloudStatusInterval_returnCloudStatusInterval() {
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "CloudName", null, null, null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                1, true, false, "-1", false
                , 0, 0,
                45, false, false, noScaling);
        assertEquals(45, ec2FleetCloud.getCloudStatusIntervalSec());
    }

    @Test
    public void create_numExecutorsLessThenOneShouldUpgradedToOne() {
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "CloudName", null, null, null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                0, true, false, "-1", false
                , 0, 0,
                45, false, false, noScaling);
        assertEquals(1, ec2FleetCloud.getNumExecutors());
    }

    @Test
    public void hasUnlimitedUsesForNodes_shouldReturnTrueWhenUnlimited() {
        final int maxTotalUses = -1;
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "CloudName", null, null, null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                0, true, false, String.valueOf(maxTotalUses), false
                , 0, 0,
                45, false, false, noScaling);
        assertTrue(ec2FleetCloud.hasUnlimitedUsesForNodes());
    }

    @Test
    public void hasUnlimitedUsesForNodes_shouldReturnDefaultTrueForNull() {
        final String maxTotalUses = null;
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "CloudName", null, null, null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                0, true, false, maxTotalUses, false
                , 0, 0,
                45, false, false, noScaling);
        assertTrue(ec2FleetCloud.hasUnlimitedUsesForNodes());
    }

    @Test
    public void hasUnlimitedUsesForNodes_shouldReturnDefaultTrueForEmptyString() {
        final String maxTotalUses = "";
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "CloudName", null, null, null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                0, true, false, maxTotalUses, false
                , 0, 0,
                45, false, false, noScaling);
        assertTrue(ec2FleetCloud.hasUnlimitedUsesForNodes());
    }

    @Test
    public void hasUnlimitedUsesForNodes_shouldReturnFalseWhenLimited() {
        final int maxTotalUses = 5;
        EC2FleetCloud ec2FleetCloud = new EC2FleetCloud(
                "CloudName", null, null, null, null, null,
                null, null, null, false,
                false, null, 0, 1, 0,
                0, true, false, String.valueOf(maxTotalUses), false
                , 0, 0,
                45, false, false, noScaling);
        assertFalse(ec2FleetCloud.hasUnlimitedUsesForNodes());
    }

    private void mockNodeCreatingPart() {
        when(jenkins.getNodesObject()).thenReturn(mock(Nodes.class));

        ExtensionList labelFinder = mock(ExtensionList.class);
        when(labelFinder.iterator()).thenReturn(Collections.emptyIterator());
        mockedLabelFinder.when(LabelFinder::all).thenReturn(labelFinder);

        // mocking part of node creation process Jenkins.get().getLabelAtom(l)
        when(jenkins.getLabelAtom(anyString())).thenReturn(new LabelAtom("mock-label"));
    }

}
