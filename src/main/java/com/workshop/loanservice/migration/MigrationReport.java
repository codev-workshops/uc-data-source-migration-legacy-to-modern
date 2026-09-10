package com.workshop.loanservice.migration;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Outcome of one migration run: per-table counts, quarantined rows and amount reconciliation.
 */
public class MigrationReport {

    public record TableStats(String table, long sourceRows, long migrated, long skippedDuplicates,
                             long quarantined, long targetRows,
                             BigDecimal sourceAmountSum, BigDecimal targetAmountSum) {
        public boolean reconciles() {
            return sourceRows == targetRows
                    && quarantined == 0
                    && skippedDuplicates == 0
                    && sourceAmountSum.compareTo(targetAmountSum) == 0;
        }
    }

    public record QuarantinedRow(String table, String key, String reason) {}

    private final List<TableStats> tables = new ArrayList<>();
    private final List<QuarantinedRow> quarantined = new ArrayList<>();
    private boolean skipped;

    void addTable(TableStats stats) { tables.add(stats); }
    void addQuarantined(String table, String key, String reason) {
        quarantined.add(new QuarantinedRow(table, key, reason));
    }
    void markSkipped() { skipped = true; }

    public List<TableStats> getTables() { return Collections.unmodifiableList(tables); }
    public List<QuarantinedRow> getQuarantined() { return Collections.unmodifiableList(quarantined); }
    public boolean isSkipped() { return skipped; }

    public boolean reconciles() {
        return !skipped && quarantined.isEmpty() && tables.stream().allMatch(TableStats::reconciles);
    }

    public TableStats table(String name) {
        return tables.stream().filter(t -> t.table().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No stats for table " + name));
    }

    @Override
    public String toString() {
        if (skipped) return "MigrationReport[skipped: modern tables already populated]";
        StringBuilder sb = new StringBuilder("MigrationReport[reconciles=").append(reconciles()).append("]\n");
        for (TableStats t : tables) {
            sb.append(String.format("  %-14s source=%d migrated=%d dupes=%d quarantined=%d target=%d amountSrc=%s amountTgt=%s%n",
                    t.table(), t.sourceRows(), t.migrated(), t.skippedDuplicates(), t.quarantined(), t.targetRows(),
                    t.sourceAmountSum(), t.targetAmountSum()));
        }
        for (QuarantinedRow q : quarantined) {
            sb.append(String.format("  QUARANTINED %s[%s]: %s%n", q.table(), q.key(), q.reason()));
        }
        return sb.toString();
    }
}
