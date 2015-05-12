/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *   * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package edu.ucsb.cs.eager.sa.kitty.qbets;

import edu.ucsb.cs.eager.sa.kitty.APICall;
import edu.ucsb.cs.eager.sa.kitty.Identifiable;
import edu.ucsb.cs.eager.sa.kitty.MethodInfo;
import edu.ucsb.cs.eager.sa.kitty.Path;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class CacheUpdater {

    private TimeSeriesDataCache cache;
    private QBETSConfig config;
    private ExecutorService exec;

    public CacheUpdater(TimeSeriesDataCache cache, QBETSConfig config) {
        this.cache = cache;
        this.config = config;
        int workers = Runtime.getRuntime().availableProcessors() - 1;
        System.out.println("Using pool with " + workers + " threads for quantile computation");
        this.exec = Executors.newFixedThreadPool(workers);
    }

    public void close() {
        exec.shutdownNow();
    }

    public void updateCacheForAPICalls(Path path, MethodInfo method, int pathIndex,
                                       double quantile, int dataPoints) throws IOException {
        List<Future> futures = new ArrayList<>();
        for (APICall call : path.calls()) {
            if (!cache.containsQuantiles(call, path.size())) {
                CacheUpdateWorker worker = new CacheUpdateWorker(call, path.size(), method,
                        pathIndex, quantile, dataPoints, cache.getTimeSeries(call));
                futures.add(exec.submit(worker));
            }
        }

        for (Future f : futures) {
            try {
                f.get();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    public void updateCacheForPath(Path path, MethodInfo method, int pathIndex,
                                   double quantile, int dataPoints, TimeSeries aggr) throws IOException {

        if (!cache.containsQuantiles(path, path.size())) {
            CacheUpdateWorker worker = new CacheUpdateWorker(path, path.size(), method, pathIndex,
                    quantile, dataPoints, aggr);
            Future f = exec.submit(worker);
            try {
                f.get();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
    }

    private class CacheUpdateWorker implements Runnable {

        private Identifiable obj;
        private int size;
        private MethodInfo method;
        private int pathIndex;
        private double quantile;
        private int dataPoints;
        private TimeSeries timeSeries;

        public CacheUpdateWorker(Identifiable obj, int size, MethodInfo method, int pathIndex,
                                 double quantile, int dataPoints, TimeSeries timeSeries) {
            this.obj = obj;
            this.size = size;
            this.method = method;
            this.pathIndex = pathIndex;
            this.quantile = quantile;
            this.dataPoints = dataPoints;
            this.timeSeries = timeSeries;
        }

        @Override
        public void run() {
            String key = obj.getId() + "_" + size;
            synchronized (key.intern()) {
                if (!cache.containsQuantiles(obj, size)) {
                    log(method, pathIndex, "Calculating quantiles for: " + obj.getId() +
                            " (q = " + quantile + "; c = " + config.getConfidence() + ")");
                    try {
                        TimeSeries quantilePredictions = getQuantilePredictions(timeSeries,
                                obj.getId(), dataPoints, quantile);
                        cache.putQuantiles(obj, size, quantilePredictions);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }
    }

    /**
     * Obtain a trace of quantiles for a time series
     *
     * @param ts Time series to be analyzed
     * @param name A human readable name for the time series
     * @param len Length of the quantile trace to be returned
     * @param quantile Quantile to be calculated (e.g. 0.95)
     * @return A time series of predictions
     * @throws IOException on error
     */
    private TimeSeries getQuantilePredictions(TimeSeries ts, String name, int len,
                                              double quantile) throws IOException {
        JSONObject msg = new JSONObject();
        msg.put("quantile", quantile);
        msg.put("confidence", config.getConfidence());
        msg.put("data", ts.toJSON());
        msg.put("name", name);

        JSONObject resp = HttpUtils.doPost(config.getBenchmarkDataSvc() + "/cpredict", msg);
        JSONArray array = resp.getJSONArray("Predictions");
        // Just return the last len elements of the resulting array
        return new TimeSeries(array, len);
    }

    private void log(MethodInfo method, int pathIndex, String msg) {
        String thread = Thread.currentThread().getName();
        System.out.printf("log: {%s}{%s}{%d} %s\n", thread, method.getName(), pathIndex, msg);
    }

}
