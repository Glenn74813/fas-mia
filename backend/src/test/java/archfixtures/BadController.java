package archfixtures;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Outside com.miaa.cas so component scanning never loads it. Deliberately breaks three architecture rules, so LayeringArchitectureTest can show that they catch violations. */
@RestController
public class BadController {

    private final JdbcTemplate jdbc;

    public BadController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/bad")
    public List<Map<String, Object>> unguarded() {
        return jdbc.queryForList("SELECT 1");
    }

    public BigDecimal inexact() {
        return new BigDecimal(0.1);
    }
}
