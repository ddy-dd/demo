package com.example.demo.ThreadPool;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * 线程池使用示例和测试类
 */
public class ThreadPoolDemo {
    
    public static void main(String[] args) throws InterruptedException {
        System.out.println("========== 自定义线程池演示 ==========\n");
        
        // 创建线程池：核心线程2个，最大线程4个，队列容量10
        CustomThreadPool pool = new CustomThreadPool(
            2,                              // 核心线程数
            4,                              // 最大线程数
            60000,                          // 非核心线程空闲存活时间60秒
            new LinkedBlockingQueue<>(10),  // 工作队列
            new CustomThreadPool.DefaultThreadFactory(),
            new CustomThreadPool.AbortPolicy()
        );
        
        // 测试1：提交少量任务（不超过核心线程数）
        testBasicExecution(pool);
        
        // 等待任务完成
        Thread.sleep(2000);
        printPoolStatus(pool);
        
        // 测试2：提交大量任务（触发队列和拒绝策略）
        testHighLoad(pool);
        
        // 关闭线程池
        pool.shutdown();
        System.out.println("\n线程池已关闭");
    }
    
    /**
     * 测试基本执行
     */
    private static void testBasicExecution(CustomThreadPool pool) {
        System.out.println("【测试1】提交3个任务（核心线程数=2）");
        System.out.println("预期：前2个任务由核心线程执行，第3个任务进入队列\n");
        
        for (int i = 1; i <= 3; i++) {
            int taskId = i;
            pool.execute(() -> {
                System.out.println("任务 " + taskId + " 开始执行 - 线程: " + 
                    Thread.currentThread().getName());
                try {
                    Thread.sleep(1000); // 模拟耗时操作
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.out.println("任务 " + taskId + " 执行完成");
            });
        }
    }
    
    /**
     * 测试高负载情况
     */
    private static void testHighLoad(CustomThreadPool pool) {
        System.out.println("\n【测试2】提交20个任务（超过队列容量+最大线程数）");
        System.out.println("预期：部分任务会被拒绝策略处理\n");
        
        int totalTasks = 20;
        int rejectedCount = 0;
        
        for (int i = 1; i <= totalTasks; i++) {
            int taskId = i;
            try {
                pool.execute(() -> {
                    System.out.println("任务 " + taskId + " 开始执行 - 线程: " + 
                        Thread.currentThread().getName());
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    System.out.println("任务 " + taskId + " 执行完成");
                });
            } catch (CustomThreadPool.RejectedExecutionException e) {
                rejectedCount++;
                System.out.println("❌ 任务 " + taskId + " 被拒绝: " + e.getMessage());
            }
        }
        
        System.out.println("\n统计：总共提交 " + totalTasks + " 个任务，被拒绝 " + rejectedCount + " 个");
        
        // 等待所有任务完成
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        printPoolStatus(pool);
    }
    
    /**
     * 打印线程池状态
     */
    private static void printPoolStatus(CustomThreadPool pool) {
        System.out.println("\n========== 线程池状态 ==========");
        System.out.println("线程池大小: " + pool.getPoolSize());
        System.out.println("活跃线程数: " + pool.getActiveCount());
        System.out.println("已完成任务数: " + pool.getCompletedTaskCount());
        System.out.println("是否关闭: " + pool.isShutdown());
        System.out.println("================================\n");
    }
}
