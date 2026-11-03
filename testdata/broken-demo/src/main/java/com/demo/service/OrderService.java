package com.demo.service;

import com.demo.repo.OrderRepo;

/** 订单服务：接收下单请求，落库，返回订单号。 */
public class OrderService {

    private final OrderRepo repo = new OrderRepo();

    public String placeOrder(String userId, String amount) {
        String orderId = "ORD-" + System.currentTimeMillis();
        String payload = userId + ":" + amount;
        repo.save(orderId, payload);
        return orderId;
    }

    public int orderCount() {
        return repo.size();
    }
}
