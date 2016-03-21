package edu.ucsb.cs.roots.data;

import com.google.common.collect.ImmutableMap;

import java.util.List;

public abstract class DataStore {

    /**
     * Retrieve the response time statistics for the specified application by analyzing
     * the request traffic within the specified interval. Returns a map of request types
     * and response time data corresponding to each request type.
     *
     * @param application Name of the application
     * @param start Start time of the interval (inclusive)
     * @param end End time of the interval (exclusive)
     * @return A Map of request types (String) and response time data (ResponseTimeSummary)
     */
    public abstract ImmutableMap<String,ResponseTimeSummary> getResponseTimeSummary(
            String application, long start, long end);

    /**
     * Retrieve the HTTP API benchmark results for the specified application by analyzing the
     * data gathered during the specified interval. Returns a map of request types and benchmarking
     * results for each request type.
     *
     * @param application Name of the application
     * @param start Start time of the interval (inclusive)
     * @param end End time of the interval (exclusive)
     * @return A Map of request types (String) and benchmark results for each type
     */
    public abstract ImmutableMap<String,List<AccessLogEntry>> getBenchmarkResults(
            String application, long start, long end);

}
