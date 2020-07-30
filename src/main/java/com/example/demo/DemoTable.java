package com.example.demo;

import lombok.Data;
import javax.persistence.*;

@Data
@Entity
@Table(name = "test_message")
public class DemoTable {
    
    @Id
    private Long id;
    private String message;
}