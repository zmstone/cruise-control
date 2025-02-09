/*
 * Copyright 2017 LinkedIn Corp. Licensed under the BSD 2-Clause License (the "License"). See License in the project root for license information.
 */

package com.linkedin.kafka.cruisecontrol.detector;

import com.linkedin.cruisecontrol.detector.Anomaly;
import com.linkedin.cruisecontrol.exception.NotEnoughValidWindowsException;
import com.linkedin.kafka.cruisecontrol.KafkaCruiseControl;
import com.linkedin.kafka.cruisecontrol.KafkaCruiseControlUnitTestUtils;
import com.linkedin.kafka.cruisecontrol.analyzer.OptimizerResult;
import com.linkedin.kafka.cruisecontrol.common.KafkaCruiseControlThreadFactory;
import com.linkedin.kafka.cruisecontrol.config.KafkaCruiseControlConfig;
import com.linkedin.kafka.cruisecontrol.detector.notifier.AnomalyNotificationResult;
import com.linkedin.kafka.cruisecontrol.detector.notifier.AnomalyNotifier;
import com.linkedin.kafka.cruisecontrol.detector.notifier.AnomalyType;
import com.linkedin.kafka.cruisecontrol.exception.KafkaCruiseControlException;
import com.linkedin.kafka.cruisecontrol.executor.ExecutorState;
import com.linkedin.kafka.cruisecontrol.model.ClusterModel;
import com.linkedin.kafka.cruisecontrol.monitor.ModelCompletenessRequirements;
import com.linkedin.kafka.cruisecontrol.monitor.task.LoadMonitorTaskRunner;
import com.linkedin.kafka.cruisecontrol.servlet.response.stats.BrokerStats;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.easymock.EasyMock;
import org.junit.Test;

import static com.linkedin.kafka.cruisecontrol.detector.AnomalyDetectorState.NUM_SELF_HEALING_STARTED;
import static com.linkedin.kafka.cruisecontrol.detector.AnomalyDetectorUtils.KAFKA_CRUISE_CONTROL_OBJECT_CONFIG;
import static com.linkedin.kafka.cruisecontrol.detector.BrokerFailureDetector.FAILED_BROKERS_OBJECT_CONFIG;
import static com.linkedin.kafka.cruisecontrol.model.RandomCluster.singleBrokerWithBadDisk;
import static com.linkedin.kafka.cruisecontrol.servlet.handler.async.runnable.RebalanceRunnable.SELF_HEALING_IGNORE_PROPOSAL_CACHE;
import static com.linkedin.kafka.cruisecontrol.servlet.handler.async.runnable.RebalanceRunnable.SELF_HEALING_IS_REBALANCE_DISK_MODE;
import static com.linkedin.kafka.cruisecontrol.servlet.handler.async.runnable.RunnableUtils.SELF_HEALING_DRYRUN;
import static com.linkedin.kafka.cruisecontrol.servlet.handler.async.runnable.RunnableUtils.SELF_HEALING_DESTINATION_BROKER_IDS;
import static com.linkedin.kafka.cruisecontrol.servlet.handler.async.runnable.RunnableUtils.SELF_HEALING_REPLICA_MOVEMENT_STRATEGY;
import static com.linkedin.kafka.cruisecontrol.servlet.handler.async.runnable.RunnableUtils.SELF_HEALING_EXCLUDED_TOPICS;
import static com.linkedin.kafka.cruisecontrol.servlet.handler.async.runnable.RunnableUtils.SELF_HEALING_CONCURRENT_MOVEMENTS;
import static com.linkedin.kafka.cruisecontrol.servlet.handler.async.runnable.RunnableUtils.SELF_HEALING_EXECUTION_PROGRESS_CHECK_INTERVAL_MS;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;


/**
 * Unit test class for anomaly detector.
 */
public class AnomalyDetectorTest {
  static private final long MOCK_ANOMALY_DETECTION_INTERVAL_MS = 3000L;
  static private final long MOCK_DELAY_CHECK_MS = 1000L;
  static private final Map<AnomalyType, Float> MOCK_SELF_HEALING_ENABLED_RATIO = new HashMap<>(AnomalyType.cachedValues().size());
  static {
    for (AnomalyType anomalyType : AnomalyType.cachedValues()) {
      MOCK_SELF_HEALING_ENABLED_RATIO.put(anomalyType, 0.99f);
    }
  }

  private static void startPeriodicDetectors(ScheduledExecutorService mockDetectorScheduler,
                                             GoalViolationDetector mockGoalViolationDetector,
                                             MetricAnomalyDetector mockMetricAnomalyDetector,
                                             DiskFailureDetector mockDiskFailureDetector,
                                             ScheduledExecutorService executorService) {
    // Starting periodic goal violation detection.
    EasyMock.expect(mockDetectorScheduler.scheduleAtFixedRate(EasyMock.eq(mockGoalViolationDetector),
                                                              EasyMock.anyLong(),
                                                              EasyMock.eq(MOCK_ANOMALY_DETECTION_INTERVAL_MS),
                                                              EasyMock.eq(TimeUnit.MILLISECONDS)))
            .andReturn(null);

    // Starting periodic metric anomaly detection.
    EasyMock.expect(mockDetectorScheduler.scheduleAtFixedRate(EasyMock.eq(mockMetricAnomalyDetector),
                                                              EasyMock.anyLong(),
                                                              EasyMock.eq(MOCK_ANOMALY_DETECTION_INTERVAL_MS),
                                                              EasyMock.eq(TimeUnit.MILLISECONDS)))
            .andReturn(null);

    // Starting periodic disk failure detection.
    EasyMock.expect(mockDetectorScheduler.scheduleAtFixedRate(EasyMock.eq(mockDiskFailureDetector),
                                                              EasyMock.anyLong(),
                                                              EasyMock.eq(MOCK_ANOMALY_DETECTION_INTERVAL_MS),
                                                              EasyMock.eq(TimeUnit.MILLISECONDS)))
            .andReturn(null);

    // Starting anomaly handler
    EasyMock.expect(mockDetectorScheduler.submit(EasyMock.isA(AnomalyDetector.AnomalyHandlerTask.class)))
            .andDelegateTo(executorService);
  }

  private static void shutdownDetector(ScheduledExecutorService mockDetectorScheduler,
                                       ScheduledExecutorService executorService) throws InterruptedException {
    mockDetectorScheduler.shutdown();
    EasyMock.expectLastCall().andDelegateTo(executorService);
    EasyMock.expect(mockDetectorScheduler.awaitTermination(MOCK_ANOMALY_DETECTION_INTERVAL_MS, TimeUnit.MILLISECONDS)).andDelegateTo(executorService);
    EasyMock.expect(mockDetectorScheduler.isTerminated()).andDelegateTo(executorService);
  }

  private static void replayCommonMocks(AnomalyNotifier mockAnomalyNotifier,
                                        BrokerFailureDetector mockBrokerFailureDetector,
                                        GoalViolationDetector mockGoalViolationDetector,
                                        MetricAnomalyDetector mockMetricAnomalyDetector,
                                        ScheduledExecutorService mockDetectorScheduler,
                                        KafkaCruiseControl mockKafkaCruiseControl) {
    EasyMock.replay(mockAnomalyNotifier);
    EasyMock.replay(mockBrokerFailureDetector);
    EasyMock.replay(mockGoalViolationDetector);
    EasyMock.replay(mockMetricAnomalyDetector);
    EasyMock.replay(mockDetectorScheduler);
    EasyMock.replay(mockKafkaCruiseControl);
  }

  private static void expectAndReplayFixMocks(OptimizerResult mockOptimizerResult, BrokerStats mockBrokerStats) {
    EasyMock.expect(mockOptimizerResult.goalProposals()).andReturn(Collections.emptySet());
    EasyMock.expect(mockOptimizerResult.getProposalSummaryForJson()).andReturn(Collections.emptyMap());
    EasyMock.expect(mockOptimizerResult.statsByGoalName()).andReturn(new LinkedHashMap<>(0)).times(2);
    EasyMock.expect(mockOptimizerResult.brokerStatsAfterOptimization()).andReturn(mockBrokerStats).times(2);
    EasyMock.expect(mockBrokerStats.getJsonStructure()).andReturn(Collections.emptyMap());
    EasyMock.expect(mockOptimizerResult.getProposalSummary()).andReturn(null);

    EasyMock.replay(mockOptimizerResult);
    EasyMock.replay(mockBrokerStats);

  }

  @Test
  public void testDelayedCheck() throws InterruptedException {
    LinkedBlockingDeque<Anomaly> anomalies = new LinkedBlockingDeque<>();
    AdminClient mockAdminClient = EasyMock.createNiceMock(AdminClient.class);
    AnomalyNotifier mockAnomalyNotifier = EasyMock.mock(AnomalyNotifier.class);
    BrokerFailureDetector mockBrokerFailureDetector = EasyMock.createNiceMock(BrokerFailureDetector.class);
    GoalViolationDetector mockGoalViolationDetector = EasyMock.createNiceMock(GoalViolationDetector.class);
    MetricAnomalyDetector mockMetricAnomalyDetector = EasyMock.createNiceMock(MetricAnomalyDetector.class);
    DiskFailureDetector mockDiskFailureDetector = EasyMock.createNiceMock(DiskFailureDetector.class);
    ScheduledExecutorService mockDetectorScheduler = EasyMock.mock(ScheduledExecutorService.class);
    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    KafkaCruiseControl mockKafkaCruiseControl = EasyMock.mock(KafkaCruiseControl.class);
    EasyMock.expect(mockAnomalyNotifier.onBrokerFailure(EasyMock.isA(BrokerFailures.class)))
            .andReturn(AnomalyNotificationResult.check(MOCK_DELAY_CHECK_MS));
    EasyMock.expect(mockAnomalyNotifier.selfHealingEnabledRatio()).andReturn(MOCK_SELF_HEALING_ENABLED_RATIO);
    Properties props = KafkaCruiseControlUnitTestUtils.getKafkaCruiseControlProperties();
    KafkaCruiseControlConfig kafkaCruiseControlConfig = new KafkaCruiseControlConfig(props);
    EasyMock.expect(mockKafkaCruiseControl.config()).andReturn(kafkaCruiseControlConfig).times(1, 2);
    startPeriodicDetectors(mockDetectorScheduler, mockGoalViolationDetector, mockMetricAnomalyDetector, mockDiskFailureDetector, executorService);
    // Schedule a delayed check
    EasyMock.expect(mockDetectorScheduler.schedule(EasyMock.isA(Runnable.class),
                                                   EasyMock.eq(MOCK_DELAY_CHECK_MS),
                                                   EasyMock.eq(TimeUnit.MILLISECONDS)))
            .andReturn(null);
    shutdownDetector(mockDetectorScheduler, executorService);

    // The following state are used to test the delayed check when executor is idle.
    EasyMock.expect(mockKafkaCruiseControl.executionState()).andReturn(ExecutorState.State.NO_TASK_IN_PROGRESS);
    replayCommonMocks(mockAnomalyNotifier, mockBrokerFailureDetector, mockGoalViolationDetector, mockMetricAnomalyDetector,
                      mockDetectorScheduler, mockKafkaCruiseControl);

    AnomalyDetector anomalyDetector = new AnomalyDetector(anomalies, mockAdminClient, MOCK_ANOMALY_DETECTION_INTERVAL_MS, mockKafkaCruiseControl,
                                                          mockAnomalyNotifier, mockGoalViolationDetector, mockBrokerFailureDetector,
                                                          mockMetricAnomalyDetector, mockDiskFailureDetector, mockDetectorScheduler);

    try {
      anomalyDetector.startDetection();
      Map<String, Object> parameterConfigOverrides = new HashMap<>(2);
      parameterConfigOverrides.put(KAFKA_CRUISE_CONTROL_OBJECT_CONFIG, mockKafkaCruiseControl);
      parameterConfigOverrides.put(FAILED_BROKERS_OBJECT_CONFIG, Collections.singletonMap(0, 100L));
      anomalies.add(kafkaCruiseControlConfig.getConfiguredInstance(KafkaCruiseControlConfig.BROKER_FAILURES_CLASS_CONFIG,
                                                                   BrokerFailures.class,
                                                                   parameterConfigOverrides));
      while (anomalyDetector.numCheckedWithDelay() < 1) {
        // Wait for the anomaly to be checked with delay before attempting to shutdown the anomaly detector.
      }
      anomalyDetector.shutdown();
      assertEquals(0, anomalyDetector.numSelfHealingStarted());
      assertEquals(1, anomalyDetector.numCheckedWithDelay());
      assertTrue(executorService.awaitTermination(5000, TimeUnit.MILLISECONDS));
      AnomalyDetectorState anomalyDetectorState = anomalyDetector.anomalyDetectorState();
      assertEquals((long) anomalyDetectorState.metrics().get(NUM_SELF_HEALING_STARTED), 0L);
      assertEquals(anomalyDetectorState.recentAnomaliesByType().get(AnomalyType.BROKER_FAILURE).size(), 1);
      assertEquals(anomalyDetectorState.recentAnomaliesByType().get(AnomalyType.GOAL_VIOLATION).size(), 0);
      assertEquals(anomalyDetectorState.recentAnomaliesByType().get(AnomalyType.METRIC_ANOMALY).size(), 0);
    } finally {
      executorService.shutdown();
    }
    EasyMock.verify(mockAnomalyNotifier, mockDetectorScheduler, mockKafkaCruiseControl);
  }

  @Test
  public void testFixGoalViolation() throws InterruptedException, KafkaCruiseControlException, NotEnoughValidWindowsException {
    testFixAnomaly(AnomalyType.GOAL_VIOLATION);
  }

  @Test
  public void testFixDiskFailure() throws InterruptedException, KafkaCruiseControlException, NotEnoughValidWindowsException {
    testFixAnomaly(AnomalyType.DISK_FAILURE);
  }

  private void testFixAnomaly(AnomalyType anomalyType)
      throws InterruptedException, KafkaCruiseControlException, NotEnoughValidWindowsException {
    LinkedBlockingDeque<Anomaly> anomalies = new LinkedBlockingDeque<>();
    AdminClient mockAdminClient = EasyMock.createNiceMock(AdminClient.class);
    AnomalyNotifier mockAnomalyNotifier = EasyMock.mock(AnomalyNotifier.class);
    BrokerFailureDetector mockBrokerFailureDetector = EasyMock.createNiceMock(BrokerFailureDetector.class);
    GoalViolationDetector mockGoalViolationDetector = EasyMock.createNiceMock(GoalViolationDetector.class);
    MetricAnomalyDetector mockMetricAnomalyDetector = EasyMock.createNiceMock(MetricAnomalyDetector.class);
    DiskFailureDetector mockDiskFailureDetector = EasyMock.createNiceMock(DiskFailureDetector.class);
    ScheduledExecutorService mockDetectorScheduler = EasyMock.mock(ScheduledExecutorService.class);
    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    KafkaCruiseControl mockKafkaCruiseControl = EasyMock.mock(KafkaCruiseControl.class);
    ModelCompletenessRequirements mockModelCompletenessRequirements = EasyMock.mock(ModelCompletenessRequirements.class);
    EasyMock.expect(mockModelCompletenessRequirements.weaker(EasyMock.anyObject())).andReturn(mockModelCompletenessRequirements);
    EasyMock.expect(mockModelCompletenessRequirements.minRequiredNumWindows()).andReturn(0);
    EasyMock.replay(mockModelCompletenessRequirements);
    OptimizerResult mockOptimizerResult = EasyMock.mock(OptimizerResult.class);
    BrokerStats mockBrokerStats = EasyMock.mock(BrokerStats.class);
    Properties props = KafkaCruiseControlUnitTestUtils.getKafkaCruiseControlProperties();
    KafkaCruiseControlConfig kafkaCruiseControlConfig = new KafkaCruiseControlConfig(props);
    EasyMock.expect(mockKafkaCruiseControl.config()).andReturn(kafkaCruiseControlConfig).times(1, 4);
    mockKafkaCruiseControl.sanityCheckDryRun(EasyMock.eq(SELF_HEALING_DRYRUN));
    EasyMock.expect(mockKafkaCruiseControl.modelCompletenessRequirements(EasyMock.anyObject())).andReturn(mockModelCompletenessRequirements);
    EasyMock.expect(mockKafkaCruiseControl.getLoadMonitorTaskRunnerState())
            .andReturn(LoadMonitorTaskRunner.LoadMonitorTaskRunnerState.RUNNING).times(1, 4);

    startPeriodicDetectors(mockDetectorScheduler, mockGoalViolationDetector, mockMetricAnomalyDetector, mockDiskFailureDetector, executorService);
    shutdownDetector(mockDetectorScheduler, executorService);

    // The following state are used to test the delayed check when executor is idle.
    EasyMock.expect(mockKafkaCruiseControl.executionState()).andReturn(ExecutorState.State.NO_TASK_IN_PROGRESS);

    EasyMock.expect(mockAnomalyNotifier.selfHealingEnabledRatio()).andReturn(MOCK_SELF_HEALING_ENABLED_RATIO);
    if (anomalyType == AnomalyType.GOAL_VIOLATION) {
      EasyMock.expect(mockKafkaCruiseControl
                          .ignoreProposalCache(EasyMock.anyObject(),
                                               EasyMock.anyObject(),
                                               EasyMock.eq(SELF_HEALING_EXCLUDED_TOPICS),
                                               EasyMock.eq(KafkaCruiseControlConfig.DEFAULT_GOAL_VIOLATION_EXCLUDE_RECENT_BROKERS_CONFIG),
                                               EasyMock.eq(SELF_HEALING_IGNORE_PROPOSAL_CACHE),
                                               EasyMock.eq(true),
                                               EasyMock.eq(SELF_HEALING_DESTINATION_BROKER_IDS),
                                               EasyMock.eq(SELF_HEALING_IS_REBALANCE_DISK_MODE))).andReturn(false);

      EasyMock.expect(mockKafkaCruiseControl.getProposals(EasyMock.anyObject(),
                                                          EasyMock.eq(KafkaCruiseControlConfig.DEFAULT_ANOMALY_DETECTION_ALLOW_CAPACITY_ESTIMATION_CONFIG)))
              .andReturn(mockOptimizerResult);

      mockKafkaCruiseControl.executeProposals(EasyMock.anyObject(),
                                              EasyMock.anyObject(),
                                              EasyMock.eq(false),
                                              EasyMock.eq(SELF_HEALING_CONCURRENT_MOVEMENTS),
                                              EasyMock.eq(SELF_HEALING_CONCURRENT_MOVEMENTS),
                                              EasyMock.eq(SELF_HEALING_CONCURRENT_MOVEMENTS),
                                              EasyMock.eq(SELF_HEALING_EXECUTION_PROGRESS_CHECK_INTERVAL_MS),
                                              EasyMock.eq(SELF_HEALING_REPLICA_MOVEMENT_STRATEGY),
                                              EasyMock.eq(null),
                                              EasyMock.eq(false),
                                              EasyMock.anyString(),
                                              EasyMock.anyString());

      EasyMock.expect(mockAnomalyNotifier.onGoalViolation(EasyMock.isA(GoalViolations.class))).andReturn(AnomalyNotificationResult.fix());
    } else if (anomalyType == AnomalyType.DISK_FAILURE) {
      ClusterModel singleBrokerWithBadDisk = singleBrokerWithBadDisk();
      EasyMock.expect(mockKafkaCruiseControl.clusterModel(EasyMock.anyObject(), EasyMock.anyObject())).andReturn(singleBrokerWithBadDisk);
      ExecutorState executorState = EasyMock.mock(ExecutorState.class);
      EasyMock.expect(mockKafkaCruiseControl.executorState()).andReturn(executorState);
      EasyMock.expect(executorState.recentlyDemotedBrokers()).andReturn(Collections.emptySet());
      EasyMock.expect(executorState.recentlyRemovedBrokers()).andReturn(Collections.emptySet());
      EasyMock.replay(executorState);
      EasyMock.expect(mockKafkaCruiseControl.excludedTopics(singleBrokerWithBadDisk, SELF_HEALING_EXCLUDED_TOPICS)).andReturn(Collections.emptySet());
      EasyMock.expect(mockKafkaCruiseControl.optimizations(EasyMock.eq(singleBrokerWithBadDisk),
                                                           EasyMock.anyObject(),
                                                           EasyMock.anyObject(),
                                                           EasyMock.eq(null),
                                                           EasyMock.anyObject()))
              .andReturn(mockOptimizerResult);

      mockKafkaCruiseControl.executeProposals(EasyMock.anyObject(),
                                              EasyMock.anyObject(),
                                              EasyMock.eq(false),
                                              EasyMock.eq(SELF_HEALING_CONCURRENT_MOVEMENTS),
                                              EasyMock.eq(SELF_HEALING_CONCURRENT_MOVEMENTS),
                                              EasyMock.eq(SELF_HEALING_CONCURRENT_MOVEMENTS),
                                              EasyMock.eq(SELF_HEALING_EXECUTION_PROGRESS_CHECK_INTERVAL_MS),
                                              EasyMock.eq(SELF_HEALING_REPLICA_MOVEMENT_STRATEGY),
                                              EasyMock.eq(null),
                                              EasyMock.eq(false),
                                              EasyMock.anyString(),
                                              EasyMock.anyString());

      EasyMock.expect(mockKafkaCruiseControl.acquireForModelGeneration(EasyMock.anyObject())).andReturn(null);
      EasyMock.expect(mockAnomalyNotifier.onDiskFailure(EasyMock.isA(DiskFailures.class))).andReturn(AnomalyNotificationResult.fix());
    }
    EasyMock.expect(mockKafkaCruiseControl.meetCompletenessRequirements(Collections.emptyList())).andReturn(true);
    EasyMock.expect(mockDetectorScheduler.schedule(EasyMock.isA(Runnable.class),
                                                   EasyMock.eq(0L),
                                                   EasyMock.eq(TimeUnit.MILLISECONDS)))
            .andReturn(null);
    replayCommonMocks(mockAnomalyNotifier, mockBrokerFailureDetector, mockGoalViolationDetector, mockMetricAnomalyDetector,
                      mockDetectorScheduler, mockKafkaCruiseControl);
    expectAndReplayFixMocks(mockOptimizerResult, mockBrokerStats);

    AnomalyDetector anomalyDetector = new AnomalyDetector(anomalies, mockAdminClient, MOCK_ANOMALY_DETECTION_INTERVAL_MS, mockKafkaCruiseControl,
                                                          mockAnomalyNotifier, mockGoalViolationDetector, mockBrokerFailureDetector,
                                                          mockMetricAnomalyDetector, mockDiskFailureDetector, mockDetectorScheduler);

    try {
      anomalyDetector.startDetection();
      if (anomalyType == AnomalyType.GOAL_VIOLATION) {
        Map<String, Object> parameterConfigOverrides = Collections.singletonMap(KAFKA_CRUISE_CONTROL_OBJECT_CONFIG, mockKafkaCruiseControl);
        GoalViolations violations = kafkaCruiseControlConfig.getConfiguredInstance(KafkaCruiseControlConfig.GOAL_VIOLATIONS_CLASS_CONFIG,
                                                                                   GoalViolations.class,
                                                                                   parameterConfigOverrides);
        violations.addViolation("RackAwareGoal", true);
        anomalies.add(violations);
      } else if (anomalyType == AnomalyType.DISK_FAILURE) {
        Map<Integer, Map<String, Long>> failedDisksByBroker = Collections.singletonMap(0, Collections.singletonMap("tmp", 0L));
        DiskFailures diskFailures = new DiskFailures(mockKafkaCruiseControl, failedDisksByBroker, true,
                                                     true, true,
                                                     Collections.emptyList());
        anomalies.add(diskFailures);
      }
      while (anomalyDetector.numSelfHealingStarted() < 1) {
        // Wait for the anomaly to be fixed before attempting to shutdown the anomaly detector.
      }
      anomalyDetector.shutdown();
      assertEquals(1, anomalyDetector.numSelfHealingStarted());
      assertEquals(0, anomalyDetector.numCheckedWithDelay());
      assertTrue(executorService.awaitTermination(5000, TimeUnit.MILLISECONDS));
      AnomalyDetectorState anomalyDetectorState = anomalyDetector.anomalyDetectorState();
      assertEquals((long) anomalyDetectorState.metrics().get(NUM_SELF_HEALING_STARTED), 1L);
      assertEquals(anomalyDetectorState.recentAnomaliesByType().get(AnomalyType.BROKER_FAILURE).size(), 0);
      assertEquals(anomalyDetectorState.recentAnomaliesByType().get(AnomalyType.GOAL_VIOLATION).size(),
                   anomalyType == AnomalyType.GOAL_VIOLATION ? 1 : 0);
      assertEquals(anomalyDetectorState.recentAnomaliesByType().get(AnomalyType.DISK_FAILURE).size(),
                   anomalyType == AnomalyType.DISK_FAILURE ? 1 : 0);
      assertEquals(anomalyDetectorState.recentAnomaliesByType().get(AnomalyType.METRIC_ANOMALY).size(), 0);
      EasyMock.verify(mockAnomalyNotifier, mockDetectorScheduler, mockKafkaCruiseControl);
    } finally {
      executorService.shutdown();
    }
    EasyMock.verify(mockAnomalyNotifier, mockDetectorScheduler, mockKafkaCruiseControl);
  }

  @Test
  public void testExecutionInProgress() throws InterruptedException {
    LinkedBlockingDeque<Anomaly> anomalies = new LinkedBlockingDeque<>();
    AdminClient mockAdminClient = EasyMock.createNiceMock(AdminClient.class);
    AnomalyNotifier mockAnomalyNotifier = EasyMock.mock(AnomalyNotifier.class);
    BrokerFailureDetector mockBrokerFailureDetector = EasyMock.createNiceMock(BrokerFailureDetector.class);
    GoalViolationDetector mockGoalViolationDetector = EasyMock.createNiceMock(GoalViolationDetector.class);
    MetricAnomalyDetector mockMetricAnomalyDetector = EasyMock.createNiceMock(MetricAnomalyDetector.class);
    DiskFailureDetector mockDiskFailureDetector = EasyMock.createNiceMock(DiskFailureDetector.class);
    ScheduledExecutorService mockDetectorScheduler = EasyMock.mock(ScheduledExecutorService.class);
    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    KafkaCruiseControl mockKafkaCruiseControl = EasyMock.mock(KafkaCruiseControl.class);
    Properties props = KafkaCruiseControlUnitTestUtils.getKafkaCruiseControlProperties();
    KafkaCruiseControlConfig kafkaCruiseControlConfig = new KafkaCruiseControlConfig(props);
    EasyMock.expect(mockKafkaCruiseControl.config()).andReturn(kafkaCruiseControlConfig).times(2);

    startPeriodicDetectors(mockDetectorScheduler, mockGoalViolationDetector, mockMetricAnomalyDetector, mockDiskFailureDetector, executorService);
    shutdownDetector(mockDetectorScheduler, executorService);
    EasyMock.expect(mockAnomalyNotifier.selfHealingEnabledRatio()).andReturn(MOCK_SELF_HEALING_ENABLED_RATIO);

    // The following state are used to test the delayed check when executor is idle.
    EasyMock.expect(mockKafkaCruiseControl.executionState())
            .andReturn(ExecutorState.State.INTER_BROKER_REPLICA_MOVEMENT_TASK_IN_PROGRESS);

    replayCommonMocks(mockAnomalyNotifier, mockBrokerFailureDetector, mockGoalViolationDetector, mockMetricAnomalyDetector,
                      mockDetectorScheduler, mockKafkaCruiseControl);

    AnomalyDetector anomalyDetector = new AnomalyDetector(anomalies, mockAdminClient, MOCK_ANOMALY_DETECTION_INTERVAL_MS, mockKafkaCruiseControl,
                                                          mockAnomalyNotifier, mockGoalViolationDetector, mockBrokerFailureDetector,
                                                          mockMetricAnomalyDetector, mockDiskFailureDetector, mockDetectorScheduler);

    try {
      anomalyDetector.startDetection();
      Map<String, Object> parameterConfigOverrides = Collections.singletonMap(KAFKA_CRUISE_CONTROL_OBJECT_CONFIG, mockKafkaCruiseControl);
      anomalies.add(kafkaCruiseControlConfig.getConfiguredInstance(KafkaCruiseControlConfig.GOAL_VIOLATIONS_CLASS_CONFIG,
                                                                   GoalViolations.class,
                                                                   parameterConfigOverrides));
      while (!anomalies.isEmpty()) {
        // Just wait for the anomalies to be drained.
      }
      anomalyDetector.shutdown();
      assertEquals(0, anomalyDetector.numSelfHealingStarted());
      assertEquals(0, anomalyDetector.numCheckedWithDelay());
      assertTrue(executorService.awaitTermination(5000, TimeUnit.MILLISECONDS));
      AnomalyDetectorState anomalyDetectorState = anomalyDetector.anomalyDetectorState();
      assertEquals((long) anomalyDetectorState.metrics().get(NUM_SELF_HEALING_STARTED), 0L);
      assertEquals(anomalyDetectorState.recentAnomaliesByType().get(AnomalyType.BROKER_FAILURE).size(), 0);
      assertEquals(anomalyDetectorState.recentAnomaliesByType().get(AnomalyType.GOAL_VIOLATION).size(), 1);
      assertEquals(anomalyDetectorState.recentAnomaliesByType().get(AnomalyType.METRIC_ANOMALY).size(), 0);
    } finally {
      executorService.shutdown();
    }
    EasyMock.verify(mockAnomalyNotifier, mockDetectorScheduler, mockKafkaCruiseControl);
  }

  @Test
  public void testShutdown() throws InterruptedException {
    AnomalyNotifier mockAnomalyNotifier = EasyMock.createNiceMock(AnomalyNotifier.class);
    AdminClient mockAdminClient = EasyMock.createNiceMock(AdminClient.class);
    BrokerFailureDetector mockBrokerFailureDetector = EasyMock.createNiceMock(BrokerFailureDetector.class);
    GoalViolationDetector mockGoalViolationDetector = EasyMock.createNiceMock(GoalViolationDetector.class);
    MetricAnomalyDetector mockMetricAnomalyDetector = EasyMock.createNiceMock(MetricAnomalyDetector.class);
    DiskFailureDetector mockDiskFailureDetector = EasyMock.createNiceMock(DiskFailureDetector.class);
    KafkaCruiseControl mockKafkaCruiseControl = EasyMock.createNiceMock(KafkaCruiseControl.class);
    ScheduledExecutorService detectorScheduler =
        Executors.newScheduledThreadPool(2, new KafkaCruiseControlThreadFactory("AnomalyDetector", false, null));

    AnomalyDetector anomalyDetector = new AnomalyDetector(new LinkedBlockingDeque<>(), mockAdminClient, MOCK_ANOMALY_DETECTION_INTERVAL_MS,
                                                          mockKafkaCruiseControl, mockAnomalyNotifier, mockGoalViolationDetector,
                                                          mockBrokerFailureDetector, mockMetricAnomalyDetector, mockDiskFailureDetector,
                                                          detectorScheduler);

    anomalyDetector.shutdown();
    Thread t = new Thread(anomalyDetector::shutdown);
    t.start();
    t.join(30000L);
    assertEquals(0, anomalyDetector.numSelfHealingStarted());
    assertEquals(0, anomalyDetector.numCheckedWithDelay());
    assertTrue(detectorScheduler.isTerminated());
  }
}
