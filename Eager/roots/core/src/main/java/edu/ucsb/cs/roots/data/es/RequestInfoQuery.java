package edu.ucsb.cs.roots.data.es;

import com.google.common.base.Strings;

import static com.google.common.base.Preconditions.checkArgument;

public final class RequestInfoQuery extends Query {

    private static final String REQUEST_INFO_QUERY = Query.loadTemplate(
            "request_info_query.json");

    private final String apiCallRequestTimestampField;
    private final String apiCallSequenceNumberField;
    private final long start;
    private final long end;

    private RequestInfoQuery(Builder builder) {
        checkArgument(!Strings.isNullOrEmpty(builder.apiCallRequestTimestampField));
        checkArgument(!Strings.isNullOrEmpty(builder.apiCallSequenceNumberField));
        checkArgument(builder.start <= builder.end);
        this.apiCallRequestTimestampField = builder.apiCallRequestTimestampField;
        this.apiCallSequenceNumberField = builder.apiCallSequenceNumberField;
        this.start = builder.start;
        this.end = builder.end;
    }

    @Override
    public String getJsonString() {
        return String.format(REQUEST_INFO_QUERY, apiCallRequestTimestampField, start, end,
                apiCallRequestTimestampField, apiCallSequenceNumberField);
    }

    public static Builder newBuilder() {
        return new Builder();
    }

    public static class Builder {

        private String apiCallRequestTimestampField;
        private String apiCallSequenceNumberField;
        private long start;
        private long end;

        private Builder() {
        }

        public Builder setApiCallRequestTimestampField(String apiCallRequestTimestampField) {
            this.apiCallRequestTimestampField = apiCallRequestTimestampField;
            return this;
        }

        public Builder setApiCallSequenceNumberField(String apiCallSequenceNumberField) {
            this.apiCallSequenceNumberField = apiCallSequenceNumberField;
            return this;
        }

        public Builder setStart(long start) {
            this.start = start;
            return this;
        }

        public Builder setEnd(long end) {
            this.end = end;
            return this;
        }

        public String buildJsonString() {
            return new RequestInfoQuery(this).getJsonString();
        }
    }
}