package com.miaa.cas.journal.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Template that generates DRAFT recurring vouchers; each generated voucher still needs full approval. */
@Entity
@Table(name = "recurring_journals")
public class RecurringJournal {

    @Id
    private UUID id;
    @Column(nullable = false, length = 200)
    private String name;
    @Column(nullable = false, length = 1000)
    private String description;
    @Column(nullable = false, length = 10)
    private String frequency;
    @Column(name = "day_of_month", nullable = false)
    private short dayOfMonth;
    @Column(name = "next_run_date", nullable = false)
    private LocalDate nextRunDate;
    @Column(name = "end_date")
    private LocalDate endDate;
    @Column(nullable = false, length = 10)
    private String status;
    @Column(name = "owner_id", nullable = false, updatable = false)
    private UUID ownerId;
    @Column(name = "owner_username", nullable = false, updatable = false, length = 64)
    private String ownerUsername;
    @OneToMany(mappedBy = "recurringJournal", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo")
    private List<RecurringJournalLine> lines = new ArrayList<>();
    @Version
    @Column(name = "version", nullable = false)
    private Long rowVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected RecurringJournal() {
    }

    public RecurringJournal(UUID id, String name, String description, String frequency, int dayOfMonth, LocalDate firstRun,
            LocalDate endDate, UUID ownerId, String ownerUsername, List<LineData> lineData, Instant now) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.frequency = frequency;
        this.dayOfMonth = (short) dayOfMonth;
        this.nextRunDate = firstRun;
        this.endDate = endDate;
        this.status = "ACTIVE";
        this.ownerId = ownerId;
        this.ownerUsername = ownerUsername;
        this.createdAt = now;
        int n = 1;
        for (LineData l : lineData) {
            lines.add(new RecurringJournalLine(this, n++, l));
        }
    }

    public void advance() {
        LocalDate next = switch (frequency) {
            case "QUARTERLY" -> nextRunDate.plusMonths(3);
            case "ANNUALLY" -> nextRunDate.plusYears(1);
            default -> nextRunDate.plusMonths(1);
        };
        nextRunDate = next.withDayOfMonth(dayOfMonth);
        if (endDate != null && nextRunDate.isAfter(endDate)) {
            status = "ENDED";
        }
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<LineData> lineData() {
        return lines.stream().map(RecurringJournalLine::data).toList();
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getFrequency() { return frequency; }
    public int getDayOfMonth() { return dayOfMonth; }
    public LocalDate getNextRunDate() { return nextRunDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getStatus() { return status; }
    public UUID getOwnerId() { return ownerId; }
    public String getOwnerUsername() { return ownerUsername; }
    public long getVersion() { return rowVersion == null ? 0 : rowVersion; }
}
