package com.metamorphia;

public class Models {
    public static class Subscription {
        public long userId;
        public long startTs;
        public long endTs;
        public String status;
        public String lastPaymentId;
        public long endTs() { return endTs; }
    }
}