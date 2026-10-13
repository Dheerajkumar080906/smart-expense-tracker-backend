package com.example.expense;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class ExpenseController {
    private final JdbcTemplate jdbc;
    public ExpenseController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/health")
    public Map<String,Object> health() {
        return Map.of(
            "status", "UP",
            "timestamp", Instant.now().toString(),
            "service", "smart-expense-tracker-backend"
        );
    }

    @GetMapping("/transactions")
    public List<Map<String,Object>> transactions() {
        return jdbc.queryForList("SELECT id,title,amount,type,category,txn_date AS date FROM transactions ORDER BY txn_date DESC,id DESC");
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addTransaction(@RequestBody TxnRequest r) {
        if (r.title == null || r.title.isBlank() || r.category == null || r.category.isBlank()
            || r.amount == null || r.amount.signum() <= 0 || !List.of("INCOME","EXPENSE").contains(r.type)
            || r.date == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide title, positive amount, type, category and date.");
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                "INSERT INTO transactions(title,amount,type,category,txn_date) VALUES (?,?,?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, r.title.trim());
            ps.setBigDecimal(2, r.amount);
            ps.setString(3, r.type);
            ps.setString(4, r.category.trim());
            ps.setDate(5, Date.valueOf(r.date));
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        return jdbc.queryForMap("SELECT id,title,amount,type,category,txn_date AS date FROM transactions WHERE id=?", generatedId.longValue());
    }

    @DeleteMapping("/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTransaction(@PathVariable long id) {
        int changed = jdbc.update("DELETE FROM transactions WHERE id=?", id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found");
    }

    @GetMapping("/budgets")
    public List<Map<String,Object>> budgets() {
        return jdbc.queryForList("SELECT id,category,monthly_limit AS monthlyLimit FROM budgets ORDER BY category");
    }

    @PostMapping("/budgets")
    public Map<String,Object> saveBudget(@RequestBody BudgetRequest r) {
        if (r.category == null || r.category.isBlank() || r.monthlyLimit == null || r.monthlyLimit.signum() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category and positive monthly limit are required.");
        
        // Standard SQL upsert compatible with H2, PostgreSQL, MySQL, etc.
        int updated = jdbc.update("UPDATE budgets SET monthly_limit=? WHERE category=?", r.monthlyLimit, r.category.trim());
        if (updated == 0) {
            jdbc.update("INSERT INTO budgets(category,monthly_limit) VALUES (?,?)", r.category.trim(), r.monthlyLimit);
        }
        return jdbc.queryForMap("SELECT id,category,monthly_limit AS monthlyLimit FROM budgets WHERE category=?", r.category.trim());
    }

    @GetMapping("/goals")
    public List<Map<String,Object>> goals() {
        return jdbc.queryForList("SELECT id,name,target_amount AS targetAmount,saved_amount AS savedAmount FROM savings_goals ORDER BY id");
    }

    @PostMapping("/goals")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> addGoal(@RequestBody GoalRequest r) {
        if (r.name == null || r.name.isBlank() || r.targetAmount == null || r.targetAmount.signum() <= 0)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Goal name and positive target amount are required.");
        org.springframework.jdbc.support.GeneratedKeyHolder keyHolder = new org.springframework.jdbc.support.GeneratedKeyHolder();
        jdbc.update(connection -> {
            var ps = connection.prepareStatement(
                "INSERT INTO savings_goals(name,target_amount,saved_amount) VALUES (?,?,?)",
                java.sql.Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, r.name.trim());
            ps.setBigDecimal(2, r.targetAmount);
            ps.setBigDecimal(3, BigDecimal.ZERO);
            return ps;
        }, keyHolder);
        Number generatedId = keyHolder.getKey();
        return jdbc.queryForMap("SELECT id,name,target_amount AS targetAmount,saved_amount AS savedAmount FROM savings_goals WHERE id=?", generatedId.longValue());
    }

    @PatchMapping("/goals/{id}/contribute")
    public Map<String,Object> contribute(@PathVariable long id, @RequestParam BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Contribution must be positive.");
        int changed = jdbc.update("UPDATE savings_goals SET saved_amount=saved_amount+? WHERE id=?", amount, id);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Savings goal not found");
        return jdbc.queryForMap("SELECT id,name,target_amount AS targetAmount,saved_amount AS savedAmount FROM savings_goals WHERE id=?", id);
    }

    @GetMapping("/dashboard")
    public Map<String,Object> dashboard() {
        String start = YearMonth.now().atDay(1).toString();
        String end = YearMonth.now().atEndOfMonth().toString();
        BigDecimal income = jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type='INCOME' AND txn_date BETWEEN ? AND ?",
            BigDecimal.class, Date.valueOf(start), Date.valueOf(end));
        BigDecimal expenses = jdbc.queryForObject(
            "SELECT COALESCE(SUM(amount),0) FROM transactions WHERE type='EXPENSE' AND txn_date BETWEEN ? AND ?",
            BigDecimal.class, Date.valueOf(start), Date.valueOf(end));
        List<Map<String,Object>> categorySpend = jdbc.queryForList(
            "SELECT category, SUM(amount) AS spent FROM transactions WHERE type='EXPENSE' AND txn_date BETWEEN ? AND ? GROUP BY category ORDER BY spent DESC",
            Date.valueOf(start), Date.valueOf(end));
        List<Map<String,Object>> budgetRows = budgets();
        for (Map<String,Object> budget : budgetRows) {
            Object bCategoryObj = budget.get("category");
            if (bCategoryObj == null) bCategoryObj = budget.get("CATEGORY");
            String bCategory = bCategoryObj != null ? bCategoryObj.toString() : "";

            BigDecimal spent = categorySpend.stream()
                .filter(row -> {
                    Object rCategoryObj = row.get("category");
                    if (rCategoryObj == null) rCategoryObj = row.get("CATEGORY");
                    String rCategory = rCategoryObj != null ? rCategoryObj.toString() : "";
                    return bCategory.equalsIgnoreCase(rCategory);
                })
                .map(row -> {
                    Object s = row.get("spent");
                    if (s == null) s = row.get("SPENT");
                    return toBigDecimal(s);
                })
                .findFirst()
                .orElse(BigDecimal.ZERO);

            Object limitObj = budget.get("monthlyLimit");
            if (limitObj == null) limitObj = budget.get("MONTHLYLIMIT");
            BigDecimal limit = toBigDecimal(limitObj);

            budget.put("spent", spent);
            budget.put("overBudget", spent.compareTo(limit) > 0);
        }
        return Map.of(
            "month", start.substring(0,7),
            "income", income != null ? income : BigDecimal.ZERO,
            "expenses", expenses != null ? expenses : BigDecimal.ZERO,
            "balance", (income != null ? income : BigDecimal.ZERO).subtract(expenses != null ? expenses : BigDecimal.ZERO),
            "categorySpend", categorySpend,
            "budgets", budgetRows
        );
    }

    private BigDecimal toBigDecimal(Object value) {
        if (value == null) return BigDecimal.ZERO;
        if (value instanceof BigDecimal bd) return bd;
        if (value instanceof Number n) return new BigDecimal(n.toString());
        try {
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    public static class TxnRequest {
        public String title;
        public BigDecimal amount;
        public String type;
        public String category;
        public LocalDate date;
    }
    public static class BudgetRequest {
        public String category;
        public BigDecimal monthlyLimit;
    }
    public static class GoalRequest {
        public String name;
        public BigDecimal targetAmount;
    }
}
