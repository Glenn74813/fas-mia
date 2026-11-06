package com.miaa.cas.financialreporting;

import com.miaa.cas.financialreporting.export.Letterhead;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Reads the letterhead for exported reports from the organisation record, so a change there shows on the next export. */
@Component
public class ReportLetterhead {

    private final JdbcTemplate jdbc;

    public ReportLetterhead(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Letterhead current() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT name, address, zip_code, tin FROM organizations ORDER BY (status = 'ACTIVE') DESC, code LIMIT 1""");
        if (rows.isEmpty()) {
            return Letterhead.DEFAULT;
        }
        Map<String, Object> o = rows.get(0);
        String address = (String) o.get("address");
        String zip = (String) o.get("zip_code");
        if (address != null && !address.isBlank() && zip != null && !zip.isBlank()) {
            address = address.trim() + " " + zip.trim();
        }
        return new Letterhead((String) o.get("name"), address, (String) o.get("tin"));
    }
}
