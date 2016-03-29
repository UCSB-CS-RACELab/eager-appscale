package edu.ucsb.cs.roots.anomaly;

import com.google.common.collect.ImmutableList;
import edu.ucsb.cs.roots.RootsEnvironment;
import edu.ucsb.cs.roots.data.ApplicationRequest;
import edu.ucsb.cs.roots.data.DataStore;
import edu.ucsb.cs.roots.data.DataStoreException;
import org.apache.commons.math3.stat.descriptive.SummaryStatistics;

import java.util.*;
import java.util.stream.Collectors;

public final class PathAnomalyDetector extends AnomalyDetector {

    private final Map<String,Map<String,List<PathRatio>>> history = new HashMap<>();
    private long end = -1L;

    private PathAnomalyDetector(RootsEnvironment environment, Builder builder) {
        super(environment, builder.application, builder.periodInSeconds,
                builder.historyLengthInSeconds, builder.dataStore, builder.properties);
    }

    @Override
    void run(long now) {
        long tempStart, tempEnd;
        if (end < 0) {
            tempEnd = now - 60 * 1000;
            tempStart = tempEnd - historyLengthInSeconds * 1000;
            // TODO: Get historical path distribution records
            end = tempEnd;
        }
        tempStart = end;
        tempEnd = end + periodInSeconds * 1000;

        try {
            updateHistory(tempStart, tempEnd);
            end = tempEnd;

            long cutoff = end - historyLengthInSeconds * 1000;
            history.values().forEach(opHistory -> opHistory.values().forEach(
                    pathHistory -> pathHistory.removeIf(s -> s.timestamp < cutoff)));
            history.forEach(this::analyzePathDistributions);
        } catch (DataStoreException e) {
            String msg = "Error while retrieving data";
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }
    }

    private void analyzePathDistributions(String op, Map<String,List<PathRatio>> pathData) {
        pathData.forEach((path,values) -> {
            SummaryStatistics statistics = new SummaryStatistics();
            values.forEach(v -> statistics.addValue(v.ratio));
            log.info("{} {} - Mean: {}, Std.Dev: {}, Count: {}", op, path, statistics.getMean(),
                    statistics.getStandardDeviation(), values.size());
        });
    }

    private void updateHistory(long windowStart, long windowEnd) throws DataStoreException {
        DataStore ds = environment.getDataStoreService().get(this.dataStore);
        Map<String,ImmutableList<ApplicationRequest>> requests = ds.getRequestInfo(
                application, windowStart, windowEnd);
        requests.forEach((k,v) -> updateOperationHistory(k, v, windowStart));

        history.keySet().stream().filter(k -> !requests.containsKey(k))
                .forEach(k -> history.get(k).values()
                        .forEach(v -> v.add(PathRatio.zero(windowStart))));
    }

    private void updateOperationHistory(String op, ImmutableList<ApplicationRequest> perOpRequests,
                                        long windowStart) {
        Map<String,List<PathRatio>> opHistory;
        if (history.containsKey(op)) {
            opHistory = history.get(op);
        } else {
            opHistory = new HashMap<>();
            history.put(op, opHistory);
        }

        List<PathRatio> longestPathHistory = opHistory.values().stream().reduce((v1, v2) -> {
            if (v1.size() > v2.size()) {
                return v1;
            }
            return v2;
        }).orElse(ImmutableList.of());

        Map<String,Integer> pathRequests = perOpRequests.stream()
                .collect(Collectors.groupingBy(ApplicationRequest::getPathAsString))
                .entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().size()));
        long totalRequests = pathRequests.values().stream()
                .mapToInt(Integer::intValue).sum();
        pathRequests.forEach((path,count) -> {
            List<PathRatio> pathHistory;
            if (opHistory.containsKey(path)) {
                pathHistory = opHistory.get(path);
            } else {
                log.info("New path detected. Application: {}; Operation: {}, Path: {}",
                        application, op, path);
                pathHistory = new ArrayList<>();
                longestPathHistory.forEach(p -> pathHistory.add(PathRatio.zero(p.timestamp)));
                opHistory.put(path, pathHistory);
            }
            pathHistory.add(new PathRatio(windowStart, count, totalRequests));
        });
        opHistory.keySet().stream()
                .filter(k -> !pathRequests.containsKey(k))
                .forEach(k -> opHistory.get(k).add(PathRatio.zero(windowStart)));
    }

    private final static class PathRatio {
        private final long timestamp;
        private final double ratio;

        PathRatio(long timestamp, long count, long total) {
            this.timestamp = timestamp;
            this.ratio = (count * 100.0)/total;
        }

        static PathRatio zero(long timestamp) {
            return new PathRatio(timestamp, 0, 1);
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder extends AnomalyDetectorBuilder<PathAnomalyDetector,Builder> {

        private Builder() {
        }

        @Override
        public PathAnomalyDetector build(RootsEnvironment environment) {
            return new PathAnomalyDetector(environment, this);
        }

        @Override
        protected Builder getThisObj() {
            return this;
        }
    }
}