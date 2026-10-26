package com.miaa.cas.treasury.statements;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * ISO 20022 camt.053 bank-to-customer statement (any version; elements are matched by local name).
 * One Stmt per file. Only booked entries are taken. DOCTYPE declarations are refused (XXE protection).
 */
public final class Camt053Parser {

    private Camt053Parser() {
    }

    public static ParsedStatement parse(byte[] content) {
        Document doc = read(content);
        NodeList stmts = doc.getElementsByTagNameNS("*", "Stmt");
        if (stmts.getLength() != 1) {
            throw new StatementFormatException("Expected exactly one Stmt in the file, found " + stmts.getLength() + ".");
        }
        Element stmt = (Element) stmts.item(0);
        String reference = text(child(stmt, "Id"));
        Element acct = child(stmt, "Acct");
        String account = null;
        String currency = null;
        if (acct != null) {
            Element id = child(acct, "Id");
            account = id == null ? null : text(child(id, "IBAN")) != null ? text(child(id, "IBAN")) : text(path(id, "Othr", "Id"));
            currency = text(child(acct, "Ccy"));
        }
        BigDecimal opening = null;
        BigDecimal closing = null;
        LocalDate openingDate = null;
        LocalDate closingDate = null;
        for (Element bal : children(stmt, "Bal")) {
            String code = text(path(bal, "Tp", "CdOrPrtry", "Cd"));
            Element amt = child(bal, "Amt");
            BigDecimal value = signed(amt, text(child(bal, "CdtDbtInd")));
            currency = checkCurrency(currency, amt);
            LocalDate date = date(child(bal, "Dt"));
            if ("OPBD".equals(code) || "PRCD".equals(code)) {
                opening = value;
                openingDate = date;
            } else if ("CLBD".equals(code)) {
                closing = value;
                closingDate = date;
            }
        }
        if (opening == null || closing == null) {
            throw new StatementFormatException("Opening (OPBD/PRCD) and closing (CLBD) balances are required.");
        }
        List<ParsedStatement.Line> lines = new ArrayList<>();
        int n = 0;
        for (Element ntry : children(stmt, "Ntry")) {
            n++;
            Element sts = child(ntry, "Sts");
            String status = sts == null ? null : child(sts, "Cd") != null ? text(child(sts, "Cd")) : text(sts);
            if (status != null && !"BOOK".equals(status)) {
                continue;
            }
            Element amt = child(ntry, "Amt");
            if (amt == null) {
                throw new StatementFormatException("Entry " + n + " has no amount.");
            }
            currency = checkCurrency(currency, amt);
            BigDecimal value = signed(amt, text(child(ntry, "CdtDbtInd")));
            LocalDate valueDate = date(child(ntry, "ValDt"));
            LocalDate booking = date(child(ntry, "BookgDt"));
            if (valueDate == null) {
                valueDate = booking;
            }
            if (valueDate == null) {
                throw new StatementFormatException("Entry " + n + " has no booking or value date.");
            }
            String ref = text(child(ntry, "AcctSvcrRef"));
            Element tx = path(ntry, "NtryDtls", "TxDtls");
            String endToEnd = tx == null ? null : text(path(tx, "Refs", "EndToEndId"));
            String ustrd = tx == null ? null : text(path(tx, "RmtInf", "Ustrd"));
            String info = text(child(ntry, "AddtlNtryInf"));
            String description = String.join(" ", nz(info), nz(ustrd)).trim();
            String reference2 = endToEnd != null && !"NOTPROVIDED".equals(endToEnd) ? endToEnd : ref;
            lines.add(new ParsedStatement.Line(valueDate, booking, value, Mt940Parser.cut(description.isEmpty() ? "(no description)"
                    : description, 500), reference2 == null ? null : Mt940Parser.cut(reference2, 100)));
        }
        LocalDate start = date(path(stmt, "FrToDt", "FrDtTm"));
        LocalDate end = date(path(stmt, "FrToDt", "ToDtTm"));
        if (start == null) {
            start = lines.stream().map(ParsedStatement.Line::valueDate).min(LocalDate::compareTo).orElse(openingDate);
        }
        if (end == null) {
            end = closingDate != null ? closingDate
                    : lines.stream().map(ParsedStatement.Line::valueDate).max(LocalDate::compareTo).orElse(start);
        }
        if (start == null || end == null) {
            throw new StatementFormatException("The statement period cannot be determined.");
        }
        return new ParsedStatement(account, currency, reference, start, end, opening, closing, lines);
    }

    private static Document read(byte[] content) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            DocumentBuilder b = f.newDocumentBuilder();
            b.setErrorHandler(null);
            return b.parse(new ByteArrayInputStream(content));
        } catch (Exception e) {
            throw new StatementFormatException("The file is not a readable camt.053 XML document.");
        }
    }

    private static String checkCurrency(String currency, Element amt) {
        String ccy = amt == null ? null : amt.getAttribute("Ccy");
        if (ccy == null || ccy.isEmpty()) {
            return currency;
        }
        if (currency != null && !currency.equals(ccy)) {
            throw new StatementFormatException("Mixed currencies in the statement (" + currency + ", " + ccy + ").");
        }
        return ccy;
    }

    private static BigDecimal signed(Element amt, String indicator) {
        if (amt == null || indicator == null) {
            throw new StatementFormatException("An amount or its credit/debit indicator is missing.");
        }
        BigDecimal v;
        try {
            v = new BigDecimal(amt.getTextContent().trim());
        } catch (NumberFormatException e) {
            throw new StatementFormatException("Unreadable amount '" + amt.getTextContent().trim() + "'.");
        }
        if (v.scale() > 2 || v.signum() < 0) {
            throw new StatementFormatException("Amount '" + v.toPlainString() + "' is not a positive amount with two decimals.");
        }
        v = v.setScale(2);
        return switch (indicator) {
            case "CRDT" -> v;
            case "DBIT" -> v.negate();
            default -> throw new StatementFormatException("Unknown CdtDbtInd '" + indicator + "'.");
        };
    }

    /** Dt/Dt, Dt/DtTm, or a direct date/datetime element. */
    private static LocalDate date(Element e) {
        if (e == null) {
            return null;
        }
        String t = text(child(e, "Dt"));
        if (t == null) {
            t = text(child(e, "DtTm"));
        }
        if (t == null) {
            t = text(e);
        }
        if (t == null || t.length() < 10) {
            return null;
        }
        try {
            return LocalDate.parse(t.substring(0, 10));
        } catch (java.time.DateTimeException ex) {
            throw new StatementFormatException("Unreadable date '" + t + "'.");
        }
    }

    private static Element child(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && name.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && name.equals(el.getLocalName())) {
                out.add(el);
            }
        }
        return out;
    }

    private static Element path(Element start, String... names) {
        Element e = start;
        for (String n : names) {
            e = child(e, n);
            if (e == null) {
                return null;
            }
        }
        return e;
    }

    private static String text(Element e) {
        if (e == null) {
            return null;
        }
        // Only leaf text counts; container elements are not text.
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                return null;
            }
        }
        String t = e.getTextContent().trim();
        return t.isEmpty() ? null : t;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
