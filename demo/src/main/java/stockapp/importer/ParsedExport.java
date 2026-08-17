package stockapp.importer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The result of reading a broker export.
 *
 * @param reportedTotalNok the total the file states about itself, when it
 *                         states one. DNB's report carries a Total sheet, which
 *                         lets the parser prove it read every row before the
 *                         import is allowed to proceed.
 */
public record ParsedExport(String broker,
                           LocalDate asOf,
                           String sourceFile,
                           List<ParsedHolding> holdings,
                           BigDecimal reportedTotalNok,
                           BigDecimal reportedCostBasisNok) {

    /** For a broker that states no portfolio-level cost basis. */
    public ParsedExport(String broker, LocalDate asOf, String sourceFile,
                        List<ParsedHolding> holdings, BigDecimal reportedTotalNok) {
        this(broker, asOf, sourceFile, holdings, reportedTotalNok, null);
    }

    /** Sum of the parsed rows, for reconciliation against the stated total. */
    public BigDecimal computedTotalNok() {
        return holdings.stream()
                .map(ParsedHolding::valueNok)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
