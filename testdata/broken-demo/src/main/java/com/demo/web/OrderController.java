package com.demo.web;

import com.demo.service.OrderService;

/** 订单接口层：把 HTTP 请求转成服务调用。 */
public class OrderController {

    private final OrderService service = new OrderService();

    public String create(String userId, String amount) {
        return service.placeOrder(userId, amount);
    }

    public int count() {
        return service.orderCount();
    }
}
