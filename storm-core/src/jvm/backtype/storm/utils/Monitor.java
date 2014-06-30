/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package backtype.storm.utils;

import backtype.storm.generated.*;
import java.util.Map;

public class Monitor {
    private static final String WATCH_TRANSFERRED = "transferred";
    private static final String WATCH_EMITTED = "emitted";

    private int _interval = 4;
    private String _topology;
    private String _component;
    private String _stream;
    private String _watch;

    private static class MetricsState {
        long lastStatted = 0;
        long lastTime = 0;
    }

    private static class Poller {
        long startTime = 0;
        long pollMs = 0;

        public long nextPoll() {
            long now = System.currentTimeMillis();
            long cycle = (now - startTime) / pollMs;
            long wakeupTime = startTime + (pollMs * (cycle + 1));
            long sleepTime = wakeupTime - now;
            if (sleepTime > 0) {
                try {
                    Thread.sleep(sleepTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            now = System.currentTimeMillis();
            return now;
        }
    }

    public void metrics(Nimbus.Client client) throws Exception {
        if (_interval <= 0) {
            throw new IllegalArgumentException("poll interval must be positive");
        }

        if (_topology == null || _topology.isEmpty()) {
            throw new IllegalArgumentException("topology name must be something");
        }

        if (_component == null || _component.isEmpty()) {
            throw new IllegalArgumentException("component name must be something");
        }

        if (_stream == null || _stream.isEmpty()) {
            throw new IllegalArgumentException("stream name must be something");
        }

        if ( !WATCH_TRANSFERRED.equals(_watch) && !WATCH_EMITTED.equals(_watch)) {
            throw new IllegalArgumentException("watch item must either be transferred or emitted");
        }
        System.out.println("topology\tslots\texecutors\texecutorsWithMetrics\tcomponent\tstream\ttime-diff ms\t" + _watch + "\tthroughput (Kt/s)");

        long pollMs = _interval * 1000;

        MetricsState state = new MetricsState();
        Poller poller = new Poller();
        long now = System.currentTimeMillis();
        state.lastTime = now;
        state.lastStatted = 0;
        poller.startTime = now;
        poller.pollMs = pollMs;

        do {
            metrics(client, now, state);
            now = poller.nextPoll();
        } while (true);
    }

    public void metrics(Nimbus.Client client, long now, MetricsState state) throws Exception {
        long totalStatted = 0;

        boolean topologyFound = false;
        boolean componentFound = false;
        boolean streamFound = false;
        int slotsUsed = 0;
        int executors = 0;
        int executorsWithMetrics = 0;
        ClusterSummary summary = client.getClusterInfo();
        for (TopologySummary ts: summary.get_topologies()) {
            if (_topology.equals(ts.get_name())) {
                topologyFound = true;
                slotsUsed = ts.get_num_workers();
                String id = ts.get_id();
                TopologyInfo info = client.getTopologyInfo(id);
                for (ExecutorSummary es: info.get_executors()) {
                    if (_component.equals(es.get_component_id())) {
                        componentFound = true;
                        executors ++;
                        ExecutorStats stats = es.get_stats();
                        if (stats != null) {
                            Map<String,Map<String,Long>> statted =
                                    WATCH_EMITTED.equals(_watch) ? stats.get_emitted() : stats.get_transferred();
                            if ( statted != null) {
                                Map<String, Long> e2 = statted.get(":all-time");
                                if (e2 != null) {
                                    Long stream = e2.get(_stream);
                                    if (stream != null){
                                        streamFound = true;
                                        executorsWithMetrics ++;
                                        totalStatted += stream;
                                    }
                                }
                            }
                        }
                    }


                }
            }
        }

        if (!topologyFound) {
            throw new IllegalArgumentException("topology: " + _topology + " not found");
        }

        if (!componentFound) {
            throw new IllegalArgumentException("component: " + _component + " not fouond");
        }

        if (!streamFound) {
            throw new IllegalArgumentException("stream: " + _stream + " not fouond");
        }
        long timeDelta = now - state.lastTime;
        long stattedDelta = totalStatted - state.lastStatted;
        state.lastTime = now;
        state.lastStatted = totalStatted;
        double throughput = (stattedDelta == 0 || timeDelta == 0) ? 0.0 : ((double)stattedDelta/(double)timeDelta);
        System.out.println(_topology+"\t"+slotsUsed+"\t"+executors+"\t"+executorsWithMetrics+"\t"+_component+"\t"+_stream+"\t"+timeDelta+"\t"+stattedDelta+"\t"+throughput);
    }

    public void set_interval(int _interval) {
        this._interval = _interval;
    }

    public void set_topology(String _topology) {
        this._topology = _topology;
    }

    public void set_component(String _component) {
        this._component = _component;
    }

    public void set_stream(String _stream) {
        this._stream = _stream;
    }

    public void set_watch(String _watch) {
        this._watch = _watch;
    }
}
