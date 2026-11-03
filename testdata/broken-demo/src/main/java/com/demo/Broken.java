package com.demo;

import com.demo.service.OrderService;

/**
 * 故意写坏的文件：下面三处都会产生 PSI 语法错误，
 * 用来验证「模块有报错时，卡片显示修改意见」这条链路。
 */
public class Broken {

    // 1) 缺分号
    int a = 1

    // 2) 括号不匹配
    public void method( {
        System.out.println("hello");
    }

    // 3) 缺右括号
    public int broken() {
        return 42;
    
}
