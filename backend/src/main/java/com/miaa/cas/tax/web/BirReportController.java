package com.miaa.cas.tax.web;

import com.miaa.cas.tax.BirAlphalistService;
import com.miaa.cas.tax.BirAlphalistService.Alphalist;
import com.miaa.cas.tax.BirAlphalistService.Download;
import com.miaa.cas.tax.BirAlphalistService.Sawt;
import com.miaa.cas.tax.BirAlphalistService.VatListing;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** BIR lists of withholding tax and VAT: QAP (1601-EQ), the 1604-E alphalist, SAWT and SLSP. */
@RestController
@RequestMapping("/api/v1/bir")
@Tag(name = "BIR reports")
public class BirReportController {

    private static final MediaType XLSX = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final BirAlphalistService bir;

    public BirReportController(BirAlphalistService bir) {
        this.bir = bir;
    }

    @GetMapping("/qap")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    @Operation(summary = "Quarterly Alphalist of Payees (BIR Form 1601-EQ), with what each payee still needs")
    public Alphalist qap(@RequestParam int year, @RequestParam int quarter) {
        return bir.qap(year, quarter);
    }

    @GetMapping("/qap.dat")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    @Operation(summary = "QAP as the DAT file of RMC 7-2021 Annex A; refused while details are missing")
    public ResponseEntity<byte[]> qapDat(@RequestParam int year, @RequestParam int quarter) {
        return download(bir.qapDat(year, quarter), MediaType.TEXT_PLAIN);
    }

    @GetMapping("/qap.xlsx")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public ResponseEntity<byte[]> qapXlsx(@RequestParam int year, @RequestParam int quarter) {
        return download(bir.alphalistWorkbook(bir.qap(year, quarter)), XLSX);
    }

    @GetMapping("/1604e")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    @Operation(summary = "Annual alphalist of payees subject to expanded withholding (BIR Form 1604-E, Schedule 3)")
    public Alphalist annual(@RequestParam int year) {
        return bir.annual(year);
    }

    @GetMapping("/1604e.dat")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public ResponseEntity<byte[]> annualDat(@RequestParam int year) {
        return download(bir.annualDat(year), MediaType.TEXT_PLAIN);
    }

    @GetMapping("/1604e.xlsx")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public ResponseEntity<byte[]> annualXlsx(@RequestParam int year) {
        return download(bir.alphalistWorkbook(bir.annual(year)), XLSX);
    }

    @GetMapping("/sawt")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    @Operation(summary = "Summary Alphalist of Withholding Taxes: tax customers withheld, from the certificates recorded on receipts")
    public Sawt sawt(@RequestParam int year, @RequestParam int quarter) {
        return bir.sawt(year, quarter);
    }

    @GetMapping("/sawt.xlsx")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    public ResponseEntity<byte[]> sawtXlsx(@RequestParam int year, @RequestParam int quarter) {
        return download(bir.sawtWorkbook(year, quarter), XLSX);
    }

    @GetMapping("/sls")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    @Operation(summary = "Summary list of sales per customer for a quarter")
    public VatListing sales(@RequestParam int year, @RequestParam int quarter) {
        return bir.sales(year, quarter);
    }

    @GetMapping("/sls.xlsx")
    @PreAuthorize("hasAuthority('PERM_AR_VIEW')")
    public ResponseEntity<byte[]> salesXlsx(@RequestParam int year, @RequestParam int quarter) {
        return download(bir.vatWorkbook(bir.sales(year, quarter)), XLSX);
    }

    @GetMapping("/slp")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    @Operation(summary = "Summary list of purchases per supplier for a quarter")
    public VatListing purchases(@RequestParam int year, @RequestParam int quarter) {
        return bir.purchases(year, quarter);
    }

    @GetMapping("/slp.xlsx")
    @PreAuthorize("hasAuthority('PERM_AP_VIEW')")
    public ResponseEntity<byte[]> purchasesXlsx(@RequestParam int year, @RequestParam int quarter) {
        return download(bir.vatWorkbook(bir.purchases(year, quarter)), XLSX);
    }

    private static ResponseEntity<byte[]> download(Download d, MediaType type) {
        return ResponseEntity.ok().contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(d.fileName()).build().toString())
                .body(d.content());
    }
}
