package com.sih26036.lmverify.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.sih26036.lmverify.entity.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
public class CertificatePdfService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd MMM yyyy, HH:mm");
    // Palette for print: navy frame, gold accents, ink text on a light page.
    private static final Color NAVY = new Color(0x13, 0x1F, 0x38);
    private static final Color GOLD = new Color(0xB9, 0x80, 0x2B);
    private static final Color INK = new Color(0x1B, 0x24, 0x36);
    private static final Color SOFT = new Color(0xF7, 0xF3, 0xEA);
    private static final Color LINE = new Color(0xE2, 0xD9, 0xC6);
    private static final Color RED = new Color(0xB8, 0x4A, 0x2E);

    private final CertificateService certificateService;
    private final QrService qrService;

    /** Loads (with access check) and renders in one transaction so lazy relations resolve. */
    @Transactional(readOnly = true)
    public byte[] render(String certNo, User user) {
        Certificate cert = certificateService.getForUser(certNo, user);
        Instrument i = cert.getInstrument();
        Business b = i.getBusiness();
        User owner = b.getOwner();
        String validFrom = cert.getIssuedAt().atZone(CertificateService.ZONE).toLocalDate().format(DATE);
        String validTo = cert.getValidUntil().format(DATE);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 54, 54, 54, 54);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            doc.open();
            drawBorder(writer);

            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 22, NAVY);
            Font sub = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, GOLD);
            Font label = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font value = FontFactory.getFont(FontFactory.HELVETICA, 10);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 11, INK);
            Font bodyBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, INK);

            Paragraph dept = new Paragraph("LEGAL METROLOGY DEPARTMENT, " + b.getJurisdiction().getState().toUpperCase(), sub);
            dept.setAlignment(Element.ALIGN_CENTER);
            doc.add(dept);
            Paragraph h = new Paragraph("Certificate of Verification", title);
            h.setAlignment(Element.ALIGN_CENTER);
            h.setSpacingBefore(4);
            doc.add(h);
            Paragraph act = new Paragraph("Issued under the Legal Metrology Act, 2009  ·  Certificate No. " + cert.getCertNo(), small);
            act.setAlignment(Element.ALIGN_CENTER);
            act.setSpacingAfter(14);
            doc.add(act);

            if (cert.getStatus() != Certificate.Status.VALID) {
                Paragraph st = new Paragraph("STATUS: " + cert.getStatus() + (cert.getRevokedReason() == null ? "" : " (" + cert.getRevokedReason() + ")"),
                        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, RED));
                st.setAlignment(Element.ALIGN_CENTER);
                st.setSpacingAfter(10);
                doc.add(st);
            }

            // Certification statement naming the owner and the shop.
            Paragraph stmt = new Paragraph();
            stmt.setLeading(17);
            stmt.add(new Chunk("This is to certify that the ", body));
            stmt.add(new Chunk(i.getType().getName(), bodyBold));
            stmt.add(new Chunk(" bearing serial number ", body));
            stmt.add(new Chunk(i.getSerialNo(), bodyBold));
            stmt.add(new Chunk(", owned by ", body));
            stmt.add(new Chunk(owner.getName(), bodyBold));
            stmt.add(new Chunk(" and used at ", body));
            stmt.add(new Chunk(b.getName(), bodyBold));
            stmt.add(new Chunk(", " + nz(b.getAddress()) + ", " + b.getJurisdiction().getDistrict()
                    + ", has been verified and found to be within the permissible limits of error.", body));
            stmt.setAlignment(Element.ALIGN_JUSTIFIED);
            stmt.setSpacingAfter(14);
            doc.add(stmt);

            // "Certified to" box and validity band.
            PdfPTable holder = new PdfPTable(new float[]{1, 1});
            holder.setWidthPercentage(100);
            holder.addCell(infoBox("CERTIFIED TO", new String[][]{
                    {"Owner name", owner.getName()},
                    {"Business / store", b.getName()},
                    {"Address", nz(b.getAddress()) + ", " + b.getJurisdiction().getDistrict() + ", " + b.getJurisdiction().getState()},
            }, label, value, SOFT));
            holder.addCell(validityBox(validFrom, validTo, i.getType().getValidityMonths()));
            holder.setSpacingAfter(14);
            doc.add(holder);

            PdfPTable top = new PdfPTable(new float[]{3, 1.3f});
            top.setWidthPercentage(100);
            PdfPTable details = new PdfPTable(new float[]{1.2f, 2});
            details.setWidthPercentage(100);
            row(details, "Instrument", i.getType().getName(), label, value);
            row(details, "Make / Model", nz(i.getMake()) + " / " + nz(i.getModel()), label, value);
            row(details, "Serial No.", i.getSerialNo(), label, value);
            row(details, "Capacity", fmtCap(i), label, value);
            row(details, "Model Approval No.", nz(i.getModelApprovalNo()), label, value);
            row(details, "Issued on", cert.getIssuedAt().atZone(CertificateService.ZONE).format(DATE_TIME), label, value);
            if (cert.getInspection() != null) {
                row(details, "Verified by", cert.getInspection().getOfficer().getName()
                        + " (" + cert.getInspection().getOfficer().getRole() + ")", label, value);
            }
            PdfPCell left = new PdfPCell(details);
            left.setBorder(Rectangle.NO_BORDER);
            top.addCell(left);

            Image qr = Image.getInstance(qrService.png(certificateService.verifyUrl(cert), 300));
            qr.scaleToFit(125, 125);
            PdfPCell qrCell = new PdfPCell();
            qrCell.setBorder(Rectangle.NO_BORDER);
            qrCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            qrCell.addElement(qr);
            Paragraph scan = new Paragraph("Scan to verify", small);
            scan.setAlignment(Element.ALIGN_CENTER);
            qrCell.addElement(scan);
            top.addCell(qrCell);
            doc.add(top);

            Inspection insp = cert.getInspection();
            if (insp != null && !insp.getObservations().isEmpty()) {
                Paragraph oh = new Paragraph("Test observations", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, NAVY));
                oh.setSpacingBefore(16);
                oh.setSpacingAfter(6);
                doc.add(oh);
                String unit = nz(i.getType().getUnit());
                PdfPTable obs = new PdfPTable(5);
                obs.setWidthPercentage(100);
                for (String c : new String[]{"Test load (" + unit + ")", "Indicated (" + unit + ")", "Error", "Permissible (±)", "Result"}) {
                    PdfPCell cell = new PdfPCell(new Phrase(c, label));
                    cell.setBackgroundColor(SOFT);
                    obs.addCell(cell);
                }
                for (Observation o : insp.getObservations()) {
                    obs.addCell(new Phrase(num(o.getTestLoad()), value));
                    obs.addCell(new Phrase(num(o.getIndicatedValue()), value));
                    obs.addCell(new Phrase(num(o.getError()), value));
                    obs.addCell(new Phrase(num(o.getPermissibleError()), value));
                    obs.addCell(new Phrase(o.isWithinLimit() ? "Within limit" : "Out of limit", value));
                }
                doc.add(obs);
            }

            Paragraph sig = new Paragraph("\nThis certificate is digitally signed (ECDSA P-256). Its authenticity and live status "
                    + "can be checked by anyone by scanning the QR code, or at " + certificateService.verifyUrl(cert).split("\\?")[0]
                    + " using the certificate number. No physical signature is required.\nSignature: " + cert.getSignature(), small);
            sig.setSpacingBefore(20);
            doc.add(sig);
            doc.close();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("PDF generation failed", e);
        }
        return out.toByteArray();
    }

    /** Double gold/navy frame around the page, like a printed certificate. */
    private static void drawBorder(PdfWriter writer) {
        PdfContentByte cb = writer.getDirectContentUnder();
        Rectangle page = PageSize.A4;
        cb.setColorStroke(NAVY);
        cb.setLineWidth(2.2f);
        cb.rectangle(24, 24, page.getWidth() - 48, page.getHeight() - 48);
        cb.stroke();
        cb.setColorStroke(GOLD);
        cb.setLineWidth(.8f);
        cb.rectangle(30, 30, page.getWidth() - 60, page.getHeight() - 60);
        cb.stroke();
    }

    private static PdfPCell infoBox(String heading, String[][] rows, Font kf, Font vf, Color bg) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(bg);
        cell.setBorderColor(LINE);
        cell.setPadding(10);
        Paragraph hd = new Paragraph(heading, FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, GOLD));
        hd.setSpacingAfter(4);
        cell.addElement(hd);
        for (String[] r : rows) {
            Paragraph k = new Paragraph(r[0], FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY));
            Paragraph v = new Paragraph(r[1], FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, INK));
            v.setSpacingAfter(4);
            cell.addElement(k);
            cell.addElement(v);
        }
        return cell;
    }

    private static PdfPCell validityBox(String from, String to, int months) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(NAVY);
        cell.setBorderColor(NAVY);
        cell.setPadding(12);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Font k = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, GOLD);
        Font v = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, Color.WHITE);
        Paragraph hd = new Paragraph("VALIDITY PERIOD", k);
        hd.setSpacingAfter(6);
        cell.addElement(hd);
        cell.addElement(new Paragraph("From", FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(0xC9, 0xD2, 0xE3))));
        cell.addElement(new Paragraph(from, v));
        Paragraph toLbl = new Paragraph("To", FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(0xC9, 0xD2, 0xE3)));
        toLbl.setSpacingBefore(4);
        cell.addElement(toLbl);
        cell.addElement(new Paragraph(to, v));
        Paragraph m = new Paragraph("(" + months + " months)", FontFactory.getFont(FontFactory.HELVETICA, 9, new Color(0xC9, 0xD2, 0xE3)));
        m.setSpacingBefore(4);
        cell.addElement(m);
        return cell;
    }

    private static void row(PdfPTable t, String k, String v, Font kf, Font vf) {
        PdfPCell a = new PdfPCell(new Phrase(k, kf));
        PdfPCell b = new PdfPCell(new Phrase(v, vf));
        a.setBorder(Rectangle.BOTTOM);
        b.setBorder(Rectangle.BOTTOM);
        a.setBorderColor(Color.LIGHT_GRAY);
        b.setBorderColor(Color.LIGHT_GRAY);
        a.setPadding(4);
        b.setPadding(4);
        t.addCell(a);
        t.addCell(b);
    }

    private static String fmtCap(Instrument i) {
        String u = nz(i.getType().getUnit());
        String s = (i.getCapacityMin() == null ? "" : "Min " + num(i.getCapacityMin()) + " " + u + ", ")
                + (i.getCapacityMax() == null ? "" : "Max " + num(i.getCapacityMax()) + " " + u);
        if (i.getEValue() != null) {
            s += ", e = " + num(i.getEValue()) + " " + u;
        }
        return s.isEmpty() ? "-" : s;
    }

    private static String num(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
