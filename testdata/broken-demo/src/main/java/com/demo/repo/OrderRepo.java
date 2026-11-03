package com.demo.repo;

import java.util.HashMap;
import java.util.Map;

/** 订单仓储：内存版，故意写得很简单，方便演示模块关系。 */
public class OrderRepo {

    private final Map<String, String> store = new HashMap<>();

    public void save(String orderId, String payload) {
        store.put(orderId, payload);
    }

    public String find(String orderId) {
        return store.get(orderId);
    }

    public int size() {
        return store.size();
    }
}
