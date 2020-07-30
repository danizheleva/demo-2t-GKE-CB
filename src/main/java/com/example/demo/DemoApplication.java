package com.example.demo;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@RestController
@RequestMapping("/")
@SpringBootApplication
public class DemoApplication {

    @Autowired
    private DemoTableRepository demoTableRepository;

	public static void main(String[] args) {
		SpringApplication.run(DemoApplication.class, args);
	}

    @GetMapping("hello")
    public String hello() {
      return "Hello world";
    }

    @GetMapping("hello/{id}")
    public String helloFromDatabase(@PathVariable(name="id") Long id){
        DemoTable table = demoTableRepository.findById(id).get();
        String message = table.getMessage();
        return "Hello " + message;
    }

}
