package com.sih26036.lmverify.service;

import com.lowagie.text.*;
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
    private static final Color NAVY = new Color(0x1F, 0x3A, 0x68);

    private final CertificateService certificateService;
    private final QrService qrService;

    /** Loads (with access check) and renders in one transaction so lazy relations resolve. */
    @Transactional(readOnly = true)
    public byte[] render(String certNo, User user) {
        Certificate cert = certificateService.getForUser(certNo, user);
        Instrument i = cert.getInstrument();
        Business b = i.getBusiness();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 48, 48);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, NAVY);
            Font sub = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.DARK_GRAY);
            Font label = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10);
            Font value = FontFactory.getFont(FontFactory.HELVETICA, 10);
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.GRAY);

            Paragraph dept = new Paragraph("Legal Metrology Department, " + b.getJurisdiction().getState(), sub);
            dept.setAlignment(Element.ALIGN_CENTER);
            doc.add(dept);
            Paragraph h = new Paragraph("Certificate of Verification", title);
            h.setAlignment(Element.ALIGN_CENTER);
            h.setSpacingAfter(4);
            doc.add(h);
            Paragraph act = new Paragraph("Issued under the Legal Metrology Act, 2009", small);
            act.setAlignment(Element.ALIGN_CENTER);
            act.setSpacingAfter(16);
            doc.add(act);

            if (cert.getStatus() != Certificate.Status.VALID) {
                Paragraph st = new Paragraph("STATUS: " + cert.getStatus(),
                        FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.RED));
                st.setAlignment(Element.ALIGN_CENTER);
                st.setSpacingAfter(10);
                doc.add(st);
            }

            PdfPTable top = new PdfPTable(new float[]{3, 1.3f});
            top.setWidthPercentage(100);
            PdfPTable details = new PdfPTable(new float[]{1.2f, 2});
            details.setWidthPercentage(100);
            row(details, "Certificate No.", cert.getCertNo(), label, value);
            row(details, "Instrument", i.getType().getName(), label, value);
            row(details, "Make / Model", nz(i.getMake()) + " / " + nz(i.getModel()), label, value);
            row(details, "Serial No.", i.getSerialNo(), label, value);
            row(details, "Capacity", fmtCap(i), label, value);
            row(details, "Model Approval No.", nz(i.getModelApprovalNo()), label, value);
            row(details, "Business", b.getName(), label, value);
            row(details, "Address", nz(b.getAddress()) + ", " + b.getJurisdiction().getDistrict(), label, value);
            row(details, "Issued on", cert.getIssuedAt().atZone(CertificateService.ZONE).format(DATE_TIME), label, value);
            row(details, "Valid until", cert.getValidUntil().format(DATE), label, value);
            if (cert.getInspection() != null) {
                row(details, "Verified by", cert.getInspection().getOfficer().getName()
                        + " (" + cert.getInspection().getOfficer().getRole() + ")", label, value);
            }
            PdfPCell left = new PdfPCell(details);
            left.setBorder(Rectangle.NO_BORDER);
            top.addCell(left);

            Image qr = Image.getInstance(qrService.png(certificateService.verifyUrl(cert), 300));
            qr.scaleToFit(130, 130);
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
                    cell.setBackgroundColor(new Color(0xE8, 0xEE, 0xF7));
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
