package com.example.expense;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class ExpenseTrackerApplication {
    public static void main(String[] args) {
        new java.io.File("./data").mkdirs();
        SpringApplication.run(ExpenseTrackerApplication.class, args);
    }

    @Bean
    CommandLineRunner seed(JdbcTemplate jdbc) {
        return args -> {
            if (jdbc.queryForObject("SELECT COUNT(*) FROM budgets", Integer.class) == 0) {
                jdbc.update("INSERT INTO budgets(category, monthly_limit) VALUES (?,?)", "Food", 5000);
                jdbc.update("INSERT INTO budgets(category, monthly_limit) VALUES (?,?)", "Transport", 2000);
                jdbc.update("INSERT INTO budgets(category, monthly_limit) VALUES (?,?)", "Shopping", 3000);
                jdbc.update("INSERT INTO budgets(category, monthly_limit) VALUES (?,?)", "Bills", 4000);
            }
            if (jdbc.queryForObject("SELECT COUNT(*) FROM savings_goals", Integer.class) == 0) {
                jdbc.update("INSERT INTO savings_goals(name,target_amount,saved_amount) VALUES (?,?,?)", "Emergency Fund", 25000, 5000);
                jdbc.update("INSERT INTO savings_goals(name,target_amount,saved_amount) VALUES (?,?,?)", "New Laptop", 60000, 12000);
            }
        };
    }
}
