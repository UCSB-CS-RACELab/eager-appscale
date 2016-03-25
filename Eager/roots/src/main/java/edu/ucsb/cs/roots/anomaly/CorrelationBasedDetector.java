package edu.ucsb.cs.roots.anomaly;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import edu.ucsb.cs.roots.RootsEnvironment;
import edu.ucsb.cs.roots.data.DataStore;
import edu.ucsb.cs.roots.data.DataStoreException;
import edu.ucsb.cs.roots.data.ResponseTimeSummary;
import edu.ucsb.cs.roots.utils.ImmutableCollectors;
import org.rosuda.REngine.REXP;
import org.rosuda.REngine.Rserve.RConnection;

import java.io.File;
import java.util.*;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

public final class CorrelationBasedDetector extends AnomalyDetector {

    private final int historyLengthInSeconds;
    private final Map<String,List<ResponseTimeSummary>> history;
    private final File scriptDirectory;
    private final double correlationThreshold;
    private final double dtwIncreaseThreshold;

    private long end = -1L;
    private Map<String,Double> prevDtw = new HashMap<>();

    private CorrelationBasedDetector(RootsEnvironment environment, Builder builder) {
        super(environment, builder.application, builder.periodInSeconds, builder.dataStore);
        checkArgument(builder.historyLengthInSeconds > 0, "History length must be positive");
        checkNotNull(builder.scriptDirectory, "Script directory path must not be null");
        checkArgument(builder.correlationThreshold >= -1 && builder.correlationThreshold <= 1,
                "Correlation threshold must be in the interval [-1,1]");
        checkArgument(builder.dtwIncreaseThreshold > 0,
                "DTW increase percentage threshold must be positive");
        this.historyLengthInSeconds = builder.historyLengthInSeconds;
        this.history = new HashMap<>();
        this.scriptDirectory = new File(builder.scriptDirectory);
        this.correlationThreshold = builder.correlationThreshold;
        this.dtwIncreaseThreshold = builder.dtwIncreaseThreshold;
        checkArgument(scriptDirectory.exists(), "Script directory path does not exist: %s",
                scriptDirectory.getAbsolutePath());
        checkArgument(scriptDirectory.isDirectory(), "%s is not a directory",
                scriptDirectory.getAbsolutePath());
    }

    @Override
    public void run(long now) {
        Collection<String> requestTypes;
        try {
            long tempStart, tempEnd;
            if (end < 0) {
                tempEnd = now - 60 * 1000 - periodInSeconds * 1000;
                tempStart = tempEnd - historyLengthInSeconds * 1000;
                initFullHistory(tempStart, tempEnd);
                end = tempEnd;
            }
            tempStart = end;
            tempEnd = end + periodInSeconds * 1000;
            requestTypes = updateHistory(tempStart, tempEnd);
            end = tempEnd;
        } catch (DataStoreException e) {
            String msg = "Error while retrieving data";
            log.error(msg, e);
            throw new RuntimeException(msg, e);
        }

        long cutoff = end - historyLengthInSeconds * 1000;
        history.values().forEach(v -> cleanupOldData(cutoff, v));
        history.entrySet().stream()
                .filter(e -> requestTypes.contains(e.getKey()) && e.getValue().size() > 2)
                .map(e -> computeCorrelation(e.getKey(), e.getValue()))
                .filter(Objects::nonNull)
                .forEach(c -> checkForAnomalies(end, c));
    }

    private void initFullHistory(long windowStart, long windowEnd) throws DataStoreException {
        checkArgument(windowStart < windowEnd, "Start time must precede end time");
        DataStore ds = environment.getDataStoreService().get(this.dataStore);
        ImmutableMap<String,ImmutableList<ResponseTimeSummary>> summaries =
                ds.getResponseTimeHistory(application, windowStart, windowEnd,
                        periodInSeconds * 1000);
        summaries.forEach((k,v) -> history.put(k, new ArrayList<>(v)));
        history.entrySet().stream()
                .filter(e -> e.getValue().size() > 2)
                .map(e -> computeCorrelation(e.getKey(), e.getValue()))
                .filter(Objects::nonNull)
                .forEach(c -> prevDtw.put(c.key, c.dtw));
    }

    private Collection<String> updateHistory(long windowStart,
                                             long windowEnd) throws DataStoreException {
        checkArgument(windowStart < windowEnd, "Start time must precede end time");
        DataStore ds = environment.getDataStoreService().get(this.dataStore);
        ImmutableMap<String,ResponseTimeSummary> summaries = ds.getResponseTimeSummary(
                application, windowStart, windowEnd);
        for (Map.Entry<String,ResponseTimeSummary> entry : summaries.entrySet()) {
            List<ResponseTimeSummary> record = history.get(entry.getKey());
            if (record == null) {
                record = new ArrayList<>();
                history.put(entry.getKey(), record);
            }
            record.add(entry.getValue());
        }
        return ImmutableList.copyOf(summaries.keySet());
    }

    private void cleanupOldData(long cutoff, List<ResponseTimeSummary> summaries) {
        ImmutableList<ResponseTimeSummary> oldData = summaries.stream()
                .filter(s -> s.getTimestamp() < cutoff)
                .collect(ImmutableCollectors.toList());
        oldData.forEach(summaries::remove);
    }

    private Correlation computeCorrelation(String key, List<ResponseTimeSummary> summaries) {
        double[] requests = new double[summaries.size()];
        double[] responseTime = new double[summaries.size()];
        for (int i = 0; i < summaries.size(); i++) {
            ResponseTimeSummary s = summaries.get(i);
            requests[i] = s.getRequestCount();
            responseTime[i] = s.getMeanResponseTime();
        }

        RConnection r = null;
        try {
            r = environment.getR();
            r.assign("x", requests);
            r.assign("y", responseTime);
            REXP correlation = r.eval("cor(x, y, method='pearson')");
            r.eval("time_warp <- dtw(x, y)");
            REXP distance = r.eval("time_warp$distance");
            String line = correlation.asDouble() + " " + distance.asDouble() + " " + requests.length;
            r.eval("rm(x)");
            r.eval("rm(y)");
            r.eval("rm(time_warp)");
            log.info("Correlation analysis output [{}]: {}", key, line);
            return new Correlation(key, line);
        } catch (Exception e) {
            log.error("Error computing the correlation statistics", e);
            return null;
        } finally {
            environment.releaseR(r);
        }
    }

    private void checkForAnomalies(long timestamp, Correlation correlation) {
        double lastDtw = prevDtw.getOrDefault(correlation.key, -1.0);
        if (correlation.rValue < correlationThreshold && lastDtw >= 0) {
            // If the correlation has dropped and the DTW distance has increased, we
            // might be looking at a performance anomaly.
            double dtwIncrease = (correlation.dtw - lastDtw)*100.0/lastDtw;
            if (dtwIncrease > dtwIncreaseThreshold) {
                reportAnomaly(timestamp, correlation.key, String.format(
                        "Correlation: %.4f; DTW-Increase: %.4f%%", correlation.rValue, dtwIncrease));
            }
        }
        prevDtw.put(correlation.key, correlation.dtw);
    }

    private static class Correlation {

        private final String key;
        private final double rValue;
        private final double dtw;

        private Correlation(String key, String line) {
            this.key = key;
            String[] segments = line.split(" ");
            this.rValue = Double.parseDouble(segments[0]);
            this.dtw = Double.parseDouble(segments[1]);
        }
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder extends AnomalyDetectorBuilder<CorrelationBasedDetector,Builder> {

        private int historyLengthInSeconds = 60 * 60;
        private String scriptDirectory = "r";
        private double correlationThreshold = 0.5;
        private double dtwIncreaseThreshold = 20.0;

        private Builder() {
        }

        public Builder setHistoryLengthInSeconds(int historyLengthInSeconds) {
            this.historyLengthInSeconds = historyLengthInSeconds;
            return this;
        }

        public Builder setScriptDirectory(String scriptDirectory) {
            this.scriptDirectory = scriptDirectory;
            return this;
        }

        public Builder setCorrelationThreshold(double correlationThreshold) {
            this.correlationThreshold = correlationThreshold;
            return this;
        }

        public Builder setDtwIncreaseThreshold(double dtwIncreaseThreshold) {
            this.dtwIncreaseThreshold = dtwIncreaseThreshold;
            return this;
        }

        public CorrelationBasedDetector build(RootsEnvironment environment) {
            return new CorrelationBasedDetector(environment, this);
        }

        @Override
        protected Builder getThisObj() {
            return this;
        }
    }

}
