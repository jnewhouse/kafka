/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.connect.runtime.standalone;

import org.apache.kafka.connect.connector.policy.ConnectorClientConfigOverridePolicy;
import org.apache.kafka.connect.errors.AlreadyExistsException;
import org.apache.kafka.connect.errors.ConnectException;
import org.apache.kafka.connect.errors.NotFoundException;
import org.apache.kafka.connect.runtime.AbstractHerder;
import org.apache.kafka.connect.runtime.ConnectorConfig;
import org.apache.kafka.connect.runtime.HerderConnectorContext;
import org.apache.kafka.connect.runtime.HerderRequest;
import org.apache.kafka.connect.runtime.RestartPlan;
import org.apache.kafka.connect.runtime.RestartRequest;
import org.apache.kafka.connect.runtime.SessionKey;
import org.apache.kafka.connect.runtime.SinkConnectorConfig;
import org.apache.kafka.connect.runtime.SourceConnectorConfig;
import org.apache.kafka.connect.runtime.TargetState;
import org.apache.kafka.connect.runtime.Worker;
import org.apache.kafka.connect.storage.ClusterConfigState;
import org.apache.kafka.connect.runtime.rest.InternalRequestSignature;
import org.apache.kafka.connect.runtime.rest.entities.ConfigInfos;
import org.apache.kafka.connect.runtime.rest.entities.ConnectorInfo;
import org.apache.kafka.connect.runtime.rest.entities.ConnectorStateInfo;
import org.apache.kafka.connect.runtime.rest.entities.TaskInfo;
import org.apache.kafka.connect.storage.ConfigBackingStore;
import org.apache.kafka.connect.storage.MemoryConfigBackingStore;
import org.apache.kafka.connect.storage.MemoryStatusBackingStore;
import org.apache.kafka.connect.storage.StatusBackingStore;
import org.apache.kafka.connect.util.Callback;
import org.apache.kafka.connect.util.ConnectorTaskId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Single process, in-memory "herder". Useful for a standalone Kafka Connect process.
 */
public class StandaloneHerder extends AbstractHerder {
    private static final Logger log = LoggerFactory.getLogger(StandaloneHerder.class);

    private final AtomicLong requestSeqNum = new AtomicLong();
    private final ScheduledExecutorService requestExecutorService;

    private ClusterConfigState configState;

    public StandaloneHerder(Worker worker, String kafkaClusterId,
                            ConnectorClientConfigOverridePolicy connectorClientConfigOverridePolicy) {
        this(worker,
                worker.workerId(),
                kafkaClusterId,
                new MemoryStatusBackingStore(),
                new MemoryConfigBackingStore(worker.configTransformer()),
             connectorClientConfigOverridePolicy);
    }

    // visible for testing
    StandaloneHerder(Worker worker,
                     String workerId,
                     String kafkaClusterId,
                     StatusBackingStore statusBackingStore,
                     MemoryConfigBackingStore configBackingStore,
                     ConnectorClientConfigOverridePolicy connectorClientConfigOverridePolicy) {
        super(worker, workerId, kafkaClusterId, statusBackingStore, configBackingStore, connectorClientConfigOverridePolicy);
        this.configState = ClusterConfigState.EMPTY;
        this.requestExecutorService = Executors.newSingleThreadScheduledExecutor();
        configBackingStore.setUpdateListener(new ConfigUpdateListener());
    }

    @Override
    public synchronized void start() {
        log.info("Herder starting");
        startServices();
        running = true;
        log.info("Herder started");
    }

    @Override
    public synchronized void stop() {
        log.info("Herder stopping");
        requestExecutorService.shutdown();
        try {
            if (!requestExecutorService.awaitTermination(30, TimeUnit.SECONDS))
                requestExecutorService.shutdownNow();
        } catch (InterruptedException e) {
            // ignore
        }

        // There's no coordination/hand-off to do here since this is all standalone. Instead, we
        // should just clean up the stuff we normally would, i.e. cleanly checkpoint and shutdown all
        // the tasks.
        for (String connName : connectors()) {
            removeConnectorTasks(connName);
            worker.stopAndAwaitConnector(connName);
        }
        stopServices();
        running = false;
        log.info("Herder stopped");
    }

    @Override
    public int generation() {
        return 0;
    }

    @Override
    public synchronized void connectors(Callback<Collection<String>> callback) {
        callback.onCompletion(null, connectors());
    }
    
    @Override
    public synchronized void connectorInfo(String connName, Callback<ConnectorInfo> callback) {
        ConnectorInfo connectorInfo = connectorInfo(connName);
        if (connectorInfo == null) {
            callback.onCompletion(new NotFoundException("Connector " + connName + " not found"), null);
            return;
        }
        callback.onCompletion(null, connectorInfo);
    }

    private synchronized ConnectorInfo createConnectorInfo(String connector) {
        if (!configState.contains(connector))
            return null;
        Map<String, String> config = configState.rawConnectorConfig(connector);
        return new ConnectorInfo(connector, config, configState.tasks(connector),
            connectorTypeForClass(config.get(ConnectorConfig.CONNECTOR_CLASS_CONFIG)));
    }

    @Override
    protected synchronized Map<String, String> rawConfig(String connName) {
        return configState.rawConnectorConfig(connName);
    }

    @Override
    public synchronized void deleteConnectorConfig(String connName, Callback<Created<ConnectorInfo>> callback) {
        try {
            if (!configState.contains(connName)) {
                // Deletion, must already exist
                callback.onCompletion(new NotFoundException("Connector " + connName + " not found", null), null);
                return;
            }

            removeConnectorTasks(connName);
            worker.stopAndAwaitConnector(connName);
            configBackingStore.removeConnectorConfig(connName);
            onDeletion(connName);
            callback.onCompletion(null, new Created<>(false, null));
        } catch (ConnectException e) {
            callback.onCompletion(e, null);
        }

    }

    @Override
    public synchronized void putConnectorConfig(String connName,
                                                final Map<String, String> config,
                                                boolean allowReplace,
                                                final Callback<Created<ConnectorInfo>> callback) {
        try {
            validateConnectorConfig(config, (error, configInfos) -> {
                if (error != null) {
                    callback.onCompletion(error, null);
                    return;
                }

                requestExecutorService.submit(
                    () -> putConnectorConfig(connName, config, allowReplace, callback, configInfos)
                );
            });
        } catch (Throwable t) {
            callback.onCompletion(t, null);
        }
    }

    private synchronized void putConnectorConfig(String connName,
                                                 final Map<String, String> config,
                                                 boolean allowReplace,
                                                 final Callback<Created<ConnectorInfo>> callback,
                                                 ConfigInfos configInfos) {
        try {
            if (maybeAddConfigErrors(configInfos, callback)) {
                return;
            }

            final boolean created;
            if (configState.contains(connName)) {
                if (!allowReplace) {
                    callback.onCompletion(new AlreadyExistsException("Connector " + connName + " already exists"), null);
                    return;
                }
                worker.stopAndAwaitConnector(connName);
                created = false;
            } else {
                created = true;
            }

            configBackingStore.putConnectorConfig(connName, config);

            startConnector(connName, (error, result) -> {
                if (error != null) {
                    callback.onCompletion(error, null);
                    return;
                }

                requestExecutorService.submit(() -> {
                    updateConnectorTasks(connName);
                    callback.onCompletion(null, new Created<>(created, createConnectorInfo(connName)));
                });
            });
        } catch (Throwable t) {
            callback.onCompletion(t, null);
        }
    }

    @Override
    public synchronized void requestTaskReconfiguration(String connName) {
        if (!worker.connectorNames().contains(connName)) {
            log.error("Task that requested reconfiguration does not exist: {}", connName);
            return;
        }
        updateConnectorTasks(connName);
    }

    @Override
    public synchronized void taskConfigs(String connName, Callback<List<TaskInfo>> callback) {
        if (!configState.contains(connName)) {
            callback.onCompletion(new NotFoundException("Connector " + connName + " not found", null), null);
            return;
        }

        List<TaskInfo> result = new ArrayList<>();
        for (ConnectorTaskId taskId : configState.tasks(connName))
            result.add(new TaskInfo(taskId, configState.rawTaskConfig(taskId)));
        callback.onCompletion(null, result);
    }

    @Override
    public void putTaskConfigs(String connName, List<Map<String, String>> configs, Callback<Void> callback, InternalRequestSignature requestSignature) {
        throw new UnsupportedOperationException("Kafka Connect in standalone mode does not support externally setting task configurations.");
    }

    @Override
    public synchronized void restartTask(ConnectorTaskId taskId, Callback<Void> cb) {
        if (!configState.contains(taskId.connector()))
            cb.onCompletion(new NotFoundException("Connector " + taskId.connector() + " not found", null), null);

        Map<String, String> taskConfigProps = configState.taskConfig(taskId);
        if (taskConfigProps == null)
            cb.onCompletion(new NotFoundException("Task " + taskId + " not found", null), null);
        Map<String, String> connConfigProps = configState.connectorConfig(taskId.connector());

        TargetState targetState = configState.targetState(taskId.connector());
        worker.stopAndAwaitTask(taskId);
        if (worker.startTask(taskId, configState, connConfigProps, taskConfigProps, this, targetState))
            cb.onCompletion(null, null);
        else
            cb.onCompletion(new ConnectException("Failed to start task: " + taskId), null);
    }

    @Override
    public synchronized void restartConnector(String connName, Callback<Void> cb) {
        if (!configState.contains(connName))
            cb.onCompletion(new NotFoundException("Connector " + connName + " not found", null), null);

        worker.stopAndAwaitConnector(connName);

        startConnector(connName, (error, result) -> cb.onCompletion(error, null));
    }

    @Override
    public synchronized HerderRequest restartConnector(long delayMs, final String connName, final Callback<Void> cb) {
        ScheduledFuture<?> future = requestExecutorService.schedule(
            () -> restartConnector(connName, cb), delayMs, TimeUnit.MILLISECONDS);

        return new StandaloneHerderRequest(requestSeqNum.incrementAndGet(), future);
    }

    @Override
    public synchronized void restartConnectorAndTasks(RestartRequest request, Callback<ConnectorStateInfo> cb) {
        // Ensure the connector exists
        String connectorName = request.connectorName();
        if (!configState.contains(connectorName)) {
            cb.onCompletion(new NotFoundException("Unknown connector: " + connectorName, null), null);
            return;
        }

        Optional<RestartPlan> maybePlan = buildRestartPlan(request);
        if (!maybePlan.isPresent()) {
            cb.onCompletion(new NotFoundException("Status for connector " + connectorName + " not found", null), null);
            return;
        }
        RestartPlan plan = maybePlan.get();

        // If requested, stop the connector and any tasks, marking each as restarting
        log.info("Received {}", plan);
        if (plan.shouldRestartConnector()) {
            worker.stopAndAwaitConnector(connectorName);
            onRestart(connectorName);
        }
        if (plan.shouldRestartTasks()) {
            // Stop the tasks and mark as restarting
            worker.stopAndAwaitTasks(plan.taskIdsToRestart());
            plan.taskIdsToRestart().forEach(this::onRestart);
        }

        // Now restart the connector and tasks
        if (plan.shouldRestartConnector()) {
            log.debug("Restarting connector '{}'", connectorName);
            startConnector(connectorName, (error, targetState) -> {
                if (error == null) {
                    log.info("Connector '{}' restart successful", connectorName);
                } else {
                    log.error("Connector '{}' restart failed", connectorName, error);
                }
            });
        }
        if (plan.shouldRestartTasks()) {
            log.debug("Restarting {} of {} tasks for {}", plan.restartTaskCount(), plan.totalTaskCount(), request);
            createConnectorTasks(connectorName, plan.taskIdsToRestart());
            log.debug("Restarted {} of {} tasks for {} as requested", plan.restartTaskCount(), plan.totalTaskCount(), request);
        }
        // Complete the restart request
        log.info("Completed {}", plan);
        cb.onCompletion(null, plan.restartConnectorStateInfo());
    }

    private void startConnector(String connName, Callback<TargetState> onStart) {
        Map<String, String> connConfigs = configState.connectorConfig(connName);
        TargetState targetState = configState.targetState(connName);
        worker.startConnector(connName, connConfigs, new HerderConnectorContext(this, connName), this, targetState, onStart);
    }

    private List<Map<String, String>> recomputeTaskConfigs(String connName) {
        Map<String, String> config = configState.connectorConfig(connName);

        ConnectorConfig connConfig = worker.isSinkConnector(connName) ?
            new SinkConnectorConfig(plugins(), config) :
            new SourceConnectorConfig(plugins(), config, worker.isTopicCreationEnabled());

        return worker.connectorTaskConfigs(connName, connConfig);
    }

    private void createConnectorTasks(String connName) {
        List<ConnectorTaskId> taskIds = configState.tasks(connName);
        createConnectorTasks(connName, taskIds);
    }

    private void createConnectorTasks(String connName, Collection<ConnectorTaskId> taskIds) {
        TargetState initialState = configState.targetState(connName);
        Map<String, String> connConfigs = configState.connectorConfig(connName);
        for (ConnectorTaskId taskId : taskIds) {
            Map<String, String> taskConfigMap = configState.taskConfig(taskId);
            worker.startTask(taskId, configState, connConfigs, taskConfigMap, this, initialState);
        }
    }

    private void removeConnectorTasks(String connName) {
        Collection<ConnectorTaskId> tasks = configState.tasks(connName);
        if (!tasks.isEmpty()) {
            worker.stopAndAwaitTasks(tasks);
            configBackingStore.removeTaskConfigs(connName);
            tasks.forEach(this::onDeletion);
        }
    }

    private void updateConnectorTasks(String connName) {
        if (!worker.isRunning(connName)) {
            log.info("Skipping update of connector {} since it is not running", connName);
            return;
        }

        List<Map<String, String>> newTaskConfigs = recomputeTaskConfigs(connName);
        List<Map<String, String>> oldTaskConfigs = configState.allTaskConfigs(connName);

        if (!newTaskConfigs.equals(oldTaskConfigs)) {
            removeConnectorTasks(connName);
            List<Map<String, String>> rawTaskConfigs = reverseTransform(connName, configState, newTaskConfigs);
            configBackingStore.putTaskConfigs(connName, rawTaskConfigs);
            createConnectorTasks(connName);
        }
    }

    // This update listener assumes synchronous updates the ConfigBackingStore, which only works
    // with the MemoryConfigBackingStore. This allows us to write a change (e.g. through
    // ConfigBackingStore.putConnectorConfig()) and then immediately read it back from an updated
    // snapshot.
    // TODO: To get any real benefit from the backing store abstraction, we should move some of
    // the handling into the callbacks in this listener.
    private class ConfigUpdateListener implements ConfigBackingStore.UpdateListener {

        @Override
        public void onConnectorConfigRemove(String connector) {
            synchronized (StandaloneHerder.this) {
                configState = configBackingStore.snapshot();
            }
        }

        @Override
        public void onConnectorConfigUpdate(String connector) {
            // TODO: move connector configuration update handling here to be consistent with
            //       the semantics of the config backing store

            synchronized (StandaloneHerder.this) {
                configState = configBackingStore.snapshot();
            }
        }

        @Override
        public void onTaskConfigUpdate(Collection<ConnectorTaskId> tasks) {
            synchronized (StandaloneHerder.this) {
                configState = configBackingStore.snapshot();
            }
        }

        @Override
        public void onConnectorTargetStateChange(String connector) {
            synchronized (StandaloneHerder.this) {
                configState = configBackingStore.snapshot();
                TargetState targetState = configState.targetState(connector);
                worker.setTargetState(connector, targetState, (error, newState) -> {
                    if (error != null) {
                        log.error("Failed to transition connector {} to target state {}", connector, targetState, error);
                        return;
                    }

                    if (newState == TargetState.STARTED) {
                        requestExecutorService.submit(() -> updateConnectorTasks(connector));
                    }
                });
            }
        }

        @Override
        public void onSessionKeyUpdate(SessionKey sessionKey) {
            // no-op
        }

        @Override
        public void onRestartRequest(RestartRequest restartRequest) {
            // no-op
        }
    }

    static class StandaloneHerderRequest implements HerderRequest {
        private final long seq;
        private final ScheduledFuture<?> future;

        public StandaloneHerderRequest(long seq, ScheduledFuture<?> future) {
            this.seq = seq;
            this.future = future;
        }

        @Override
        public void cancel() {
            future.cancel(false);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof StandaloneHerderRequest))
                return false;
            StandaloneHerderRequest other = (StandaloneHerderRequest) o;
            return seq == other.seq;
        }

        @Override
        public int hashCode() {
            return Objects.hash(seq);
        }
    }

    @Override
    public void tasksConfig(String connName, Callback<Map<ConnectorTaskId, Map<String, String>>> callback) {
        Map<ConnectorTaskId, Map<String, String>> tasksConfig = buildTasksConfig(connName);
        if (tasksConfig.isEmpty()) {
            callback.onCompletion(new NotFoundException("Connector " + connName + " not found"), tasksConfig);
            return;
        }
        callback.onCompletion(null, tasksConfig);
    }

}
