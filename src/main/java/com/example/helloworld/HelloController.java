package com.example.helloworld;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {

    @Value("${greeting.name}")
    private String name;

    @GetMapping("/api/hello")
    public String hello() {
        return "Hello " + name;
    }
}
