package edu.ucsb.cs.roots.data;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import edu.ucsb.cs.roots.utils.ImmutableCollectors;

import java.util.*;
import java.util.stream.Collectors;

public class TestDataStore extends DataStore {

    private static final Random RAND = new Random();

    @Override
    public ImmutableMap<String, ResponseTimeSummary> getResponseTimeSummary(
            String application, long start, long end) {
        List<AccessLogEntry> logEntries = getAccessLogEntries(application, start, end);
        Map<String,List<AccessLogEntry>> groupedEntries = logEntries.stream()
                .collect(Collectors.groupingBy(AccessLogEntry::getRequestType));
        return groupedEntries.entrySet().stream().collect(ImmutableCollectors.toMap(
                        Map.Entry::getKey, e -> new ResponseTimeSummary(start, e.getValue())));
    }

    @Override
    public ImmutableMap<String,List<AccessLogEntry>> getBenchmarkResults(
            String application, long start, long end) {
        List<AccessLogEntry> logEntries = getAccessLogEntries(application, start, end, 2);
        Map<String,List<AccessLogEntry>> groupedEntries = logEntries.stream()
                .collect(Collectors.groupingBy(AccessLogEntry::getRequestType));
        ImmutableMap.Builder<String,List<AccessLogEntry>> builder = ImmutableMap.builder();
        groupedEntries.forEach((k,v) -> builder.put(k, ImmutableList.copyOf(v)));
        return builder.build();
    }

    private List<AccessLogEntry> getAccessLogEntries(
            String application, long start, long end) {
        return getAccessLogEntries(application, start, end, RAND.nextInt(100));
    }

    private List<AccessLogEntry> getAccessLogEntries(
            String application, long start, long end, int recordCount) {
        ImmutableList.Builder<AccessLogEntry> builder = ImmutableList.builder();
        for (int i = 0; i < recordCount; i++) {
            long offset = RAND.nextInt((int) (end - start));
            String method;
            if (i % 2 == 0) {
                method = "GET";
            } else {
                method = "POST";
            }
            AccessLogEntry record = new AccessLogEntry(start + offset, application,
                    method, "/", RAND.nextInt(50));
            builder.add(record);
        }
        return builder.build();
    }
}