package com.example;

import org.apache.catalina.Context;
import org.apache.catalina.LifecycleException;
import org.apache.catalina.startup.Tomcat;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class TestTomcat {
    public static void main(String[] args) {
        Tomcat tomcat = new Tomcat();
        tomcat.setPort(8080);

        // 设置 baseDir（工作目录）
        tomcat.setBaseDir(".");

        // 添加一个 webapp 上下文
        // addContext 方法中会创建 Server - Service - Engine - Host - Context 并建立关联
        Context context = tomcat.addContext("", null); // 无实际 docBase，用 null 占位

        // 添加一个 servlet,实际是通过 context.addChild 实现
        Tomcat.addServlet(context, "helloServlet", new HttpServlet() {
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) {
                try {
                    resp.getWriter().write("Hello from embedded Tomcat!");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });

        // 映射 servlet 路由
        context.addServletMappingDecoded("/", "helloServlet");

        // 启动
        try {
            // 建立 server - service - connector 关系, 并启动
            tomcat.start();
        } catch (LifecycleException e) {
            throw new RuntimeException(e);
        }
        tomcat.getServer().await();  // 阻塞等待
    }
}
