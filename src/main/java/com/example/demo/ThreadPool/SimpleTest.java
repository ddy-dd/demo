package com.example.demo.ThreadPool;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * 简单测试类 - 验证线程池基本功能
 */
public class SimpleTest {
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("开始测试自定义线程池...\n");
        
        // 创建线程池
        CustomThreadPool pool = new CustomThreadPool(
            2,  // 核心线程数
            4,  // 最大线程数
            60000,  // 存活时间
            new LinkedBlockingQueue<>(10)  // 队列容量
        );
        
        // 提交5个任务
        for (int i = 1; i <= 5; i++) {
            final int taskId = i;
            pool.execute(() -> {
                System.out.println("任务 " + taskId + " 正在执行 - 线程: " + 
                    Thread.currentThread().getName());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("任务 " + taskId + " 完成");
            });
        }
        
        System.out.println("\n所有任务已提交，等待执行完成...\n");
        
        // 等待任务执行
        Thread.sleep(3000);
        
        // 打印状态
        System.out.println("========== 线程池状态 ==========");
        System.out.println("线程池大小: " + pool.getPoolSize());
        System.out.println("活跃线程数: " + pool.getActiveCount());
        System.out.println("已完成任务数: " + pool.getCompletedTaskCount());
        System.out.println("================================\n");
        
        // 关闭线程池
        pool.shutdown();
        System.out.println("线程池已关闭，程序结束。");
    }
}
