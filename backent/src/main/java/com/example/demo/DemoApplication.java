package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.lang.reflect.Array;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        Map<String, String> map = new HashMap<>();
////        map.put("1", "1");
////        map.put("2", "2");
////        map.put("3", "3");
////        map.get("1");
////        map.remove("1");
////        map.size();
//        List<String> list = Arrays.asList("1", "2", "3");
////        list.add("4");
//        List<String> list1 = new ArrayList<>();
//        ThreadLocal threadLocal = new ThreadLocal();
//        threadLocal.set("1");
//        threadLocal.get();
//
//
//        Map<String, String> mao = new ConcurrentHashMap<>();
        SpringApplication.run(DemoApplication.class, args);
//        Object[] a = new Object[10];
//        Student student = new Student();
//        student.setName("1");
//        a[0] = student;
//        Print print = new Print();
//        print.print(a[0]);
        Lock lock = new ReentrantLock();

      //  System.out.println(System.getenv("DEEPSEEK_API_KEY"));




    }

}
