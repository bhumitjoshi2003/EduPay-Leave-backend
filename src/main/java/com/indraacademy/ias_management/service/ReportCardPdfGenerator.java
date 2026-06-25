package com.indraacademy.ias_management.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Document-first PDF report card generator.
 *
 * Design principles (matching the Angular report-card component):
 *   - Times-Roman throughout — official, serif, document-grade
 *   - White page background; no colored cell fills on data rows
 *   - Thin colored top stripe on school header; bordered "REPORT CARD" box
 *   - All table borders: solid #999 gray (collapsed, document style)
 *   - Grade coloring: text color only — no pill backgrounds
 *   - Attendance: plain 4-column table (Working / Present / Absent / %)
 *   - Result (Pass/Fail): plain bold text, no colored badge
 *   - Section title bars: solid primary-color bar with white text (appropriate in print)
 *   - Uses OpenPDF 1.3.11 (com.github.librepdf:openpdf) — colors are java.awt.Color
 */
@Service
public class ReportCardPdfGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportCardPdfGenerator.class);

    /** Base URL for QR verification links, e.g. https://edunexify.co.in */
    @Value("${frontend.url:https://edunexify.co.in}")
    private String frontendUrl;

    // ── Document palette ───────────────────────────────────────────────────
    private static final Color WHITE       = Color.WHITE;
    private static final Color BLACK       = Color.BLACK;
    private static final Color TEXT_DARK   = new Color(15,  23,  42);   // #0f172a
    private static final Color TEXT_MID    = new Color(100, 116, 139);  // #64748b
    private static final Color BORDER_GRAY = new Color(153, 153, 153);  // #999
    private static final Color ROW_ALT     = new Color(248, 248, 248);  // very subtle zebra
    private static final Color TOTALS_BG   = new Color(241, 245, 249);  // #f1f5f9 — totals row only

    // Grade text colors (text-only, no backgrounds)
    private static final Color GRADE_A_COLOR = new Color(20,  90,  20);   // dark green
    private static final Color GRADE_B_COLOR = new Color(12,  84,  96);   // teal-dark
    private static final Color GRADE_C_COLOR = new Color(120, 80,   4);   // amber-dark
    private static final Color GRADE_F_COLOR = new Color(114, 28,  36);   // red-dark

    // Pass / Fail text colors
    private static final Color PASS_COLOR  = new Color(20,  90,  20);
    private static final Color FAIL_COLOR  = new Color(114, 28,  36);

    // ── Fonts (Times-Roman throughout) ─────────────────────────────────────
    // School header
    private static final Font F_SCHOOL_NAME = FontFactory.getFont(FontFactory.TIMES_BOLD,   16, TEXT_DARK);
    private static final Font F_SCHOOL_DET  = FontFactory.getFont(FontFactory.TIMES_ROMAN,   8, TEXT_MID);
    private static final Font F_RC_LABEL    = FontFactory.getFont(FontFactory.TIMES_BOLD,   11, TEXT_DARK);  // colored at build time
    private static final Font F_SESSION     = FontFactory.getFont(FontFactory.TIMES_ROMAN,   8, TEXT_MID);

    // Section / table
    private static final Font F_SEC_TITLE   = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, WHITE);
    private static final Font F_TH          = FontFactory.getFont(FontFactory.TIMES_BOLD,    8, WHITE);
    private static final Font F_SI_LABEL    = FontFactory.getFont(FontFactory.TIMES_BOLD,    7, TEXT_MID);  // student-info label
    private static final Font F_SI_VALUE    = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, TEXT_DARK); // student-info value
    private static final Font F_BODY        = FontFactory.getFont(FontFactory.TIMES_ROMAN,   9, TEXT_DARK);
    private static final Font F_BODY_SM     = FontFactory.getFont(FontFactory.TIMES_ROMAN,   8, TEXT_MID);
    private static final Font F_BODY_BOLD   = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, TEXT_DARK);
    private static final Font F_SUMMARY_VAL = FontFactory.getFont(FontFactory.TIMES_BOLD,   12, TEXT_DARK);
    private static final Font F_SIGN_LABEL  = FontFactory.getFont(FontFactory.TIMES_ROMAN,   8, TEXT_MID);
    private static final Font F_REMARKS     = FontFactory.getFont(FontFactory.TIMES_ITALIC,  9, TEXT_DARK);

    // Grade fonts (text-only)
    private static final Font F_GRADE_A     = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, GRADE_A_COLOR);
    private static final Font F_GRADE_B     = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, GRADE_B_COLOR);
    private static final Font F_GRADE_C     = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, GRADE_C_COLOR);
    private static final Font F_GRADE_F     = FontFactory.getFont(FontFactory.TIMES_BOLD,    9, GRADE_F_COLOR);

    private static final DecimalFormat DF1 = new DecimalFormat("0.0");
    private static final DecimalFormat DF0 = new DecimalFormat("0");

    // ── Branding config ────────────────────────────────────────────────────

    private static class BrandingConfig {
        Color   primary         = new Color(21, 101, 192);  // default blue
        boolean showWatermark   = false;
        String  watermarkText   = "";
        String  footerText      = "";
        boolean showCgpa        = true;
        boolean showGradePoints = false;
    }

    private BrandingConfig parseBranding(ReportCardTemplateDTO template) {
        BrandingConfig cfg = new BrandingConfig();
        if (template == null || template.getBrandingJson() == null
                || template.getBrandingJson().isBlank()) return cfg;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = om.readValue(template.getBrandingJson(), Map.class);

            String pc = (String) map.get("primaryColor");
            if (pc != null) cfg.primary = hexToColor(pc);

            cfg.showWatermark   = Boolean.TRUE.equals(map.get("showWatermark"));
            cfg.watermarkText   = (String) map.getOrDefault("watermarkText", "");
            cfg.footerText      = (String) map.getOrDefault("footerText", "");

            Object sc  = map.get("showCgpa");
            if (sc  instanceof Boolean) cfg.showCgpa        = (Boolean) sc;
            Object sgp = map.get("showGradePoints");
            if (sgp instanceof Boolean) cfg.showGradePoints = (Boolean) sgp;
        } catch (Exception ignored) {}
        return cfg;
    }

    private static Color hexToColor(String hex) {
        try {
            String h = hex.replace("#", "");
            if (h.length() == 3) {
                h = "" + h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
            }
            return new Color(
                Integer.parseInt(h.substring(0, 2), 16),
                Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16));
        } catch (Exception e) { return new Color(21, 101, 192); }
    }

    // ── Public API ────────────────────────────────────────────────────────

    public byte[] generate(ReportCardDataDTO data) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // A4, margins 14mm left/right, 12mm top/bottom (matches @page CSS)
        Document document = new Document(PageSize.A4, 39.7f, 39.7f, 34f, 34f);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, baos);

            BrandingConfig branding = parseBranding(data.getTemplate());
            currentVerificationToken = data.getVerificationToken();

            if (branding.showWatermark) {
                String wm = (branding.watermarkText != null && !branding.watermarkText.isBlank())
                        ? branding.watermarkText : safe(data.getSchoolName(), "CONFIDENTIAL");
                writer.setPageEvent(new WatermarkEvent(wm));
            }

            document.open();

            List<ReportCardTemplateDTO.SectionDTO> sections = getSortedEnabledSections(data.getTemplate());
            for (ReportCardTemplateDTO.SectionDTO section : sections) {
                renderSection(document, data, section, branding);
            }

        } catch (Exception e) {
            log.error("PDF generation failed for student {}: {}", data.getStudentId(), e.getMessage(), e);
            throw new RuntimeException("PDF generation failed: " + e.getMessage(), e);
        } finally {
            if (document.isOpen()) document.close();
        }
        return baos.toByteArray();
    }

    // ── Watermark ─────────────────────────────────────────────────────────

    private static class WatermarkEvent extends PdfPageEventHelper {
        private final String text;
        WatermarkEvent(String text) { this.text = text; }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            try {
                PdfContentByte cb = writer.getDirectContentUnder();
                cb.saveState();
                PdfGState gs = new PdfGState();
                gs.setFillOpacity(0.07f);
                cb.setGState(gs);
                cb.beginText();
                cb.setFontAndSize(BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED), 52);
                cb.setColorFill(new Color(180, 180, 180));
                cb.showTextAligned(Element.ALIGN_CENTER, text,
                        document.getPageSize().getWidth()  / 2f,
                        document.getPageSize().getHeight() / 2f,
                        45f);
                cb.endText();
                cb.restoreState();
            } catch (Exception ignored) {}
        }
    }

    // ── Section dispatch ──────────────────────────────────────────────────

    // Store the current data's verificationToken for use in addSignatures
    private String currentVerificationToken;

    private void renderSection(Document doc, ReportCardDataDTO data,
                                ReportCardTemplateDTO.SectionDTO section,
                                BrandingConfig branding) throws DocumentException {
        switch (section.getSectionType()) {
            case "SCHOOL_HEADER"      -> addSchoolHeader(doc, data, branding);
            case "STUDENT_INFO"       -> addStudentInfo(doc, data, branding);
            case "MARKS_TABLE"        -> addMarksTable(doc, data, branding);
            case "ASSESSMENT_SUMMARY" -> addAssessmentSummary(doc, data, branding);
            case "ATTENDANCE"         -> { if (data.getAttendance() != null) addAttendance(doc, data, branding); }
            case "CO_SCHOLASTIC"      -> addCoScholastic(doc, data, section, branding);
            case "TEACHER_REMARKS"    -> addRemarks(doc, "Class Teacher's Remarks", data.getTeacherRemarks(), branding);
            case "PRINCIPAL_REMARKS"  -> addRemarks(doc, "Principal's Remarks", data.getPrincipalRemarks(), branding);
            case "PROMOTION_STATUS"   -> addPromotionStatus(doc, data, branding);
            case "SIGNATURES"         -> addSignatures(doc, branding);
            default -> {}
        }
    }

    // ── SCHOOL_HEADER ─────────────────────────────────────────────────────
    // Layout: [Logo] | [School name + details] | [REPORT CARD box]
    // Logo column is omitted when no logo URL is present.
    // Mirrors Angular: .rc-header-logo | .rc-header-center | rc-report-title-box

    private void addSchoolHeader(Document doc, ReportCardDataDTO data,
                                  BrandingConfig branding) throws DocumentException {
        // 1. Thin colored top stripe
        PdfPTable stripe = new PdfPTable(1);
        stripe.setWidthPercentage(100);
        stripe.setSpacingAfter(0);
        PdfPCell stripeCell = new PdfPCell(new Phrase(" "));
        stripeCell.setBackgroundColor(branding.primary);
        stripeCell.setFixedHeight(5f);
        stripeCell.setBorder(Rectangle.NO_BORDER);
        stripe.addCell(stripeCell);
        doc.add(stripe);

        // 2. Try to load the school logo
        Image logoImage = loadLogoImage(data.getSchoolLogoUrl());

        // 3. Header table — 3 cols with logo, or 2 cols without
        PdfPTable header;
        if (logoImage != null) {
            header = new PdfPTable(new float[]{0.8f, 3f, 1.4f});
        } else {
            header = new PdfPTable(new float[]{3f, 1.4f});
        }
        header.setWidthPercentage(100);
        header.setSpacingAfter(10);

        // Logo cell (only when logo loaded)
        if (logoImage != null) {
            logoImage.scaleToFit(64, 64);
            PdfPCell logoCell = new PdfPCell(logoImage, false);
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            logoCell.setPaddingTop(8);
            logoCell.setPaddingBottom(8);
            logoCell.setPaddingRight(8);
            header.addCell(logoCell);
        }

        // Center: school name (centered, prominent) + address + affiliation + contact
        PdfPCell center = new PdfPCell();
        center.setBackgroundColor(WHITE);
        center.setBorder(Rectangle.NO_BORDER);
        center.setPaddingTop(8);
        center.setPaddingBottom(8);
        center.setHorizontalAlignment(Element.ALIGN_CENTER);

        Font schoolNameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 18, branding.primary);
        Paragraph schoolName = new Paragraph(safe(data.getSchoolName(), "School Name").toUpperCase(), schoolNameFont);
        schoolName.setAlignment(Element.ALIGN_CENTER);
        schoolName.setSpacingAfter(3);
        center.addElement(schoolName);

        // School type / affiliation line — shown even when no affiliation number
        String affLine = "";
        if (data.getAffiliationNumber() != null && !data.getAffiliationNumber().isBlank()) {
            affLine = "Affiliation No: " + data.getAffiliationNumber();
        }
        if (!affLine.isBlank()) {
            Font affFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 8, TEXT_MID);
            Paragraph affPara = new Paragraph(affLine, affFont);
            affPara.setAlignment(Element.ALIGN_CENTER);
            center.addElement(affPara);
        }

        if (data.getSchoolAddress() != null && !data.getSchoolAddress().isBlank()) {
            Paragraph addr = new Paragraph(data.getSchoolAddress(), F_SCHOOL_DET);
            addr.setAlignment(Element.ALIGN_CENTER);
            center.addElement(addr);
        }
        String contact = buildContact(data);
        if (!contact.isBlank()) {
            Paragraph contactPara = new Paragraph(contact, F_SCHOOL_DET);
            contactPara.setAlignment(Element.ALIGN_CENTER);
            center.addElement(contactPara);
        }
        header.addCell(center);

        // Right: bordered "REPORT CARD" + session label
        PdfPCell right = new PdfPCell();
        right.setBackgroundColor(WHITE);
        right.setBorder(Rectangle.BOX);
        right.setBorderColor(branding.primary);
        right.setBorderWidth(1.5f);
        right.setPadding(10);
        right.setHorizontalAlignment(Element.ALIGN_CENTER);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Font rcFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 11, branding.primary);
        Paragraph rcLabel = new Paragraph("REPORT CARD", rcFont);
        rcLabel.setAlignment(Element.ALIGN_CENTER);
        rcLabel.setSpacingAfter(3);
        right.addElement(rcLabel);

        if (data.getSession() != null) {
            Paragraph sess = new Paragraph(data.getSession(), F_SESSION);
            sess.setAlignment(Element.ALIGN_CENTER);
            right.addElement(sess);
        }
        header.addCell(right);
        doc.add(header);
    }

    /**
     * Attempts to load an image from a URL or file path.
     * Returns null silently if the logo cannot be loaded (missing, network error, unsupported format).
     */
    private Image loadLogoImage(String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank()) return null;
        try {
            if (logoUrl.startsWith("http://") || logoUrl.startsWith("https://")) {
                java.net.URL url = new java.net.URL(logoUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    byte[] bytes = conn.getInputStream().readAllBytes();
                    return Image.getInstance(bytes);
                }
            } else {
                return Image.getInstance(logoUrl);
            }
        } catch (Exception e) {
            log.debug("School logo could not be loaded ({}): {}", logoUrl, e.getMessage());
        }
        return null;
    }

    // ── STUDENT_INFO ──────────────────────────────────────────────────────
    // Label/value pair table — label is small gray bold, value is black body.

    private void addStudentInfo(Document doc, ReportCardDataDTO data,
                                 BrandingConfig branding) throws DocumentException {
        doc.add(sectionTitleBar("STUDENT INFORMATION", branding.primary));

        // 4-column table: label | value | label | value
        PdfPTable table = new PdfPTable(new float[]{1.2f, 2f, 1.2f, 2f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);

        addSiRow(table, "Name",         safe(data.getStudentName(), "—"),
                         "Student ID",   safe(data.getStudentId(),   "—"));
        addSiRow(table, "Class",         safe(data.getClassName(),   "—"),
                         "Roll No.",      safe(data.getRollNumber(), "—"));
        addSiRow(table, "Date of Birth", safe(data.getDateOfBirth(), "—"),
                         "Session",       safe(data.getSession(),    "—"));
        addSiRow(table, "Father's Name", safe(data.getFatherName(),  "—"),
                         "Mother's Name", safe(data.getMotherName(), "—"));
        doc.add(table);
    }

    private void addSiRow(PdfPTable table,
                           String label1, String value1,
                           String label2, String value2) {
        table.addCell(siLabelCell(label1));
        table.addCell(siValueCell(value1));
        table.addCell(siLabelCell(label2));
        table.addCell(siValueCell(value2));
    }

    private PdfPCell siLabelCell(String text) {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(BORDER_GRAY);
        c.setPadding(6);
        c.setBackgroundColor(ROW_ALT);
        c.addElement(new Paragraph(text.toUpperCase(), F_SI_LABEL));
        return c;
    }

    private PdfPCell siValueCell(String text) {
        PdfPCell c = new PdfPCell(new Phrase(text, F_SI_VALUE));
        c.setBorderColor(BORDER_GRAY);
        c.setPadding(6);
        c.setBackgroundColor(WHITE);
        return c;
    }

    // ── MARKS_TABLE ───────────────────────────────────────────────────────
    // Plain bordered table. Grade column: text-only colored. No row fills except totals.

    private void addMarksTable(Document doc, ReportCardDataDTO data,
                                BrandingConfig branding) throws DocumentException {
        WeightedGroupResultDTO wr = data.getWeightedResult();
        if (wr == null || wr.getMarksTable() == null) return;

        WeightedGroupResultDTO.MarksTableDTO mt = wr.getMarksTable();
        int examCount = mt.getExamColumns().size();
        if (examCount == 0) return;

        doc.add(sectionTitleBar("MARKS TABLE", branding.primary));

        int extraCols = branding.showGradePoints ? 2 : 1;
        float[] colWidths = new float[examCount + 1 + extraCols];
        colWidths[0] = 3f;
        for (int i = 1; i <= examCount; i++) colWidths[i] = 1.4f;
        colWidths[examCount + 1] = 1.2f;
        if (branding.showGradePoints) colWidths[examCount + 2] = 1.0f;

        PdfPTable table = new PdfPTable(colWidths);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);

        // Header row
        table.addCell(thCell("Subject", branding.primary));
        for (WeightedGroupResultDTO.MarksTableDTO.ExamColumnDTO col : mt.getExamColumns()) {
            String label = col.getExamName() + "\n(" + DF0.format(col.getWeightage() * 100) + "%)";
            table.addCell(thCellCenter(label, branding.primary));
        }
        table.addCell(thCellCenter("Grade", branding.primary));
        if (branding.showGradePoints) {
            table.addCell(thCellCenter("GP", branding.primary));
        }

        // Data rows — no alternating background (document style)
        for (WeightedGroupResultDTO.MarksTableDTO.SubjectRowDTO row : mt.getSubjectRows()) {
            table.addCell(tdCell(row.getSubjectName(), false));

            List<WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO> marks = row.getExamMarks();
            for (int i = 0; i < examCount; i++) {
                WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO mark =
                        (marks != null && i < marks.size()) ? marks.get(i) : null;
                if (mark == null || mark.getObtained() == null) {
                    table.addCell(tdCellCenter("Ab", true));
                } else {
                    String txt = DF0.format(mark.getObtained()) + "/" + DF0.format(mark.getMax());
                    table.addCell(tdCellCenter(txt, false));
                }
            }

            String subGrade = gradeFromPct(row.getWeightedPercentage(), data.getGradingSystem());
            table.addCell(tdGradeCenter(subGrade, row.getWeightedPercentage()));

            if (branding.showGradePoints) {
                double gp = cbseGradePoint(subGrade);
                table.addCell(tdCellCenter(DF1.format(gp), false));
            }
        }

        // Totals row — subtle gray bg
        if (mt.getExamTotals() != null && !mt.getExamTotals().isEmpty()) {
            PdfPCell totalLabel = new PdfPCell(new Phrase("TOTAL", F_BODY_BOLD));
            totalLabel.setBackgroundColor(TOTALS_BG);
            totalLabel.setBorderColor(BORDER_GRAY);
            totalLabel.setPadding(5);
            table.addCell(totalLabel);

            for (WeightedGroupResultDTO.MarksTableDTO.ExamTotalDTO total : mt.getExamTotals()) {
                PdfPCell tc = new PdfPCell(new Phrase(
                        DF0.format(total.getObtained()) + "/" + DF0.format(total.getMax()), F_BODY_BOLD));
                tc.setBackgroundColor(TOTALS_BG);
                tc.setHorizontalAlignment(Element.ALIGN_CENTER);
                tc.setBorderColor(BORDER_GRAY);
                tc.setPadding(5);
                table.addCell(tc);
            }

            String overallGrade = gradeFromPct(wr.getWeightedPercentage(), data.getGradingSystem());
            PdfPCell og = new PdfPCell(new Phrase(overallGrade, gradeFont(wr.getWeightedPercentage())));
            og.setBackgroundColor(TOTALS_BG);
            og.setHorizontalAlignment(Element.ALIGN_CENTER);
            og.setBorderColor(BORDER_GRAY);
            og.setPadding(5);
            table.addCell(og);

            if (branding.showGradePoints) {
                double cgpVal = data.getCgpa() != null ? data.getCgpa() : cbseGradePoint(overallGrade);
                Font cgpFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 9, GRADE_B_COLOR);
                PdfPCell gpCell = new PdfPCell(new Phrase(DF1.format(cgpVal), cgpFont));
                gpCell.setBackgroundColor(TOTALS_BG);
                gpCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                gpCell.setBorderColor(BORDER_GRAY);
                gpCell.setPadding(5);
                table.addCell(gpCell);
            }
        }
        doc.add(table);
    }

    // ── ASSESSMENT_SUMMARY ────────────────────────────────────────────────
    // Compact bordered summary table — no colored KPI blocks.

    private void addAssessmentSummary(Document doc, ReportCardDataDTO data,
                                       BrandingConfig branding) throws DocumentException {
        WeightedGroupResultDTO wr = data.getWeightedResult();
        if (wr == null) return;

        doc.add(sectionTitleBar("ASSESSMENT SUMMARY", branding.primary));

        boolean showCgpa = branding.showCgpa && data.getCgpa() != null;
        float[] widths = showCgpa ? new float[]{2f, 1f, 1f, 1f} : new float[]{2f, 1f, 1f};

        PdfPTable summaryTable = new PdfPTable(widths);
        summaryTable.setWidthPercentage(100);
        summaryTable.setSpacingAfter(4);

        // Header row
        summaryTable.addCell(thCell("Overall Percentage", branding.primary));
        summaryTable.addCell(thCellCenter("Grade", branding.primary));
        summaryTable.addCell(thCellCenter("Class Rank", branding.primary));
        if (showCgpa) summaryTable.addCell(thCellCenter("CGPA", branding.primary));

        // Data row
        String pctStr = DF1.format(wr.getWeightedPercentage()) + "%";
        PdfPCell pctCell = new PdfPCell(
            new Phrase(pctStr, FontFactory.getFont(FontFactory.TIMES_BOLD, 12, branding.primary)));
        pctCell.setBorderColor(BORDER_GRAY);
        pctCell.setPadding(8);
        summaryTable.addCell(pctCell);

        String grade = gradeFromPct(wr.getWeightedPercentage(), data.getGradingSystem());
        PdfPCell gradeCell = new PdfPCell(new Phrase(grade, gradeFont(wr.getWeightedPercentage())));
        gradeCell.setBorderColor(BORDER_GRAY);
        gradeCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        gradeCell.setPadding(8);
        summaryTable.addCell(gradeCell);

        String rankStr = wr.getRank() > 0 ? String.valueOf(wr.getRank()) : "—";
        summaryTable.addCell(tdCellCenter(rankStr, false));

        if (showCgpa) {
            PdfPCell cgpaCell = new PdfPCell(
                new Phrase(DF1.format(data.getCgpa()), F_BODY_BOLD));
            cgpaCell.setBorderColor(BORDER_GRAY);
            cgpaCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cgpaCell.setPadding(8);
            summaryTable.addCell(cgpaCell);
        }
        doc.add(summaryTable);
    }

    // ── ATTENDANCE ────────────────────────────────────────────────────────
    // Plain 4-column table: Working Days | Days Present | Days Absent | Attendance %
    // Mirrors Angular's 4-column <table> replacing the old KPI blocks.

    private void addAttendance(Document doc, ReportCardDataDTO data,
                                BrandingConfig branding) throws DocumentException {
        ReportCardDataDTO.AttendanceBlock att = data.getAttendance();
        doc.add(sectionTitleBar("ATTENDANCE", branding.primary));

        PdfPTable table = new PdfPTable(4);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);

        table.addCell(thCellCenter("Working Days", branding.primary));
        table.addCell(thCellCenter("Days Present", branding.primary));
        table.addCell(thCellCenter("Days Absent", branding.primary));
        table.addCell(thCellCenter("Attendance %", branding.primary));

        int absent = att.getWorkingDays() - att.getPresentDays();
        table.addCell(tdCellCenter(String.valueOf(att.getWorkingDays()), false));
        table.addCell(tdCellCenter(String.valueOf(att.getPresentDays()), false));
        table.addCell(tdCellCenter(String.valueOf(absent), false));

        PdfPCell pctCell = new PdfPCell(
            new Phrase(DF1.format(att.getPercentage()) + "%", F_BODY_BOLD));
        pctCell.setBorderColor(BORDER_GRAY);
        pctCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        pctCell.setPadding(6);
        table.addCell(pctCell);

        doc.add(table);
    }

    // ── CO_SCHOLASTIC ─────────────────────────────────────────────────────
    // Activity / Grade table — grade uses text-only primary color (no PURPLE_BG).

    private void addCoScholastic(Document doc, ReportCardDataDTO data,
                                  ReportCardTemplateDTO.SectionDTO section,
                                  BrandingConfig branding) throws DocumentException {
        doc.add(sectionTitleBar("CO-SCHOLASTIC ACTIVITIES", branding.primary));

        List<ReportCardDataDTO.CoScholasticGrade> grades = data.getCoScholasticGrades();

        if (grades != null && !grades.isEmpty()) {
            PdfPTable table = new PdfPTable(new float[]{3f, 1f});
            table.setWidthPercentage(60);
            table.setSpacingAfter(8);

            table.addCell(thCell("Activity", branding.primary));
            table.addCell(thCellCenter("Grade", branding.primary));

            for (ReportCardDataDTO.CoScholasticGrade g : grades) {
                table.addCell(tdCell(g.getActivity(), false));
                if (g.getGrade() != null && !g.getGrade().isBlank()) {
                    Font gradeFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 9, branding.primary);
                    PdfPCell gc = new PdfPCell(new Phrase(g.getGrade(), gradeFont));
                    gc.setBackgroundColor(WHITE);
                    gc.setHorizontalAlignment(Element.ALIGN_CENTER);
                    gc.setBorderColor(BORDER_GRAY);
                    gc.setPadding(5);
                    table.addCell(gc);
                } else {
                    table.addCell(tdCellCenter("—", true));
                }
            }
            doc.add(table);
        } else {
            List<String> activities  = parseActivities(section.getConfigJson());
            List<String> gradeScale  = parseGradeScale(section.getConfigJson());

            PdfPTable table = new PdfPTable(buildCoWidths(gradeScale.size()));
            table.setWidthPercentage(80);
            table.setSpacingAfter(8);

            table.addCell(thCell("Activity", branding.primary));
            for (String g : gradeScale) table.addCell(thCellCenter(g, branding.primary));
            table.addCell(thCell("Remarks", branding.primary));

            for (String act : activities) {
                table.addCell(tdCell(act, false));
                for (int i = 0; i < gradeScale.size(); i++) {
                    PdfPCell box = new PdfPCell();
                    box.setFixedHeight(18f);
                    box.setBorderColor(BORDER_GRAY);
                    box.setHorizontalAlignment(Element.ALIGN_CENTER);
                    table.addCell(box);
                }
                PdfPCell rem = new PdfPCell();
                rem.setFixedHeight(18f);
                rem.setBorderColor(BORDER_GRAY);
                table.addCell(rem);
            }
            doc.add(table);
        }
    }

    // ── REMARKS ───────────────────────────────────────────────────────────

    private void addRemarks(Document doc, String title, String text,
                             BrandingConfig branding) throws DocumentException {
        doc.add(sectionTitleBar(title.toUpperCase(), branding.primary));

        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingAfter(4);

        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(BORDER_GRAY);
        cell.setPadding(10);
        cell.setMinimumHeight(40f);
        cell.setBackgroundColor(WHITE);

        if (text != null && !text.isBlank()) {
            cell.addElement(new Paragraph(text, F_REMARKS));
        }
        table.addCell(cell);
        doc.add(table);

        // Signature line on the right
        PdfPTable sig = new PdfPTable(new float[]{3f, 1f});
        sig.setWidthPercentage(100);
        sig.setSpacingAfter(8);

        PdfPCell blank = new PdfPCell(new Phrase(" "));
        blank.setBorder(Rectangle.NO_BORDER);
        sig.addCell(blank);

        PdfPCell signCell = new PdfPCell();
        signCell.setBorder(Rectangle.TOP);
        signCell.setBorderColor(BORDER_GRAY);
        signCell.setPaddingTop(4);
        signCell.setBorderWidth(0.8f);
        String sigLabel = title.contains("Teacher") ? "Class Teacher's Signature" : "Principal's Signature";
        Paragraph signLabel = new Paragraph(sigLabel, F_SIGN_LABEL);
        signLabel.setAlignment(Element.ALIGN_CENTER);
        signCell.addElement(signLabel);
        sig.addCell(signCell);
        doc.add(sig);
    }

    // ── PROMOTION_STATUS ──────────────────────────────────────────────────
    // Plain text row: "RESULT: PASS" or "RESULT: FAIL" — no colored badge.
    // Mirrors Angular's .rc-result-row with .rc-result-pass / .rc-result-fail text colors.

    private void addPromotionStatus(Document doc, ReportCardDataDTO data,
                                     BrandingConfig branding) throws DocumentException {
        WeightedGroupResultDTO wr = data.getWeightedResult();
        if (wr == null) return;

        doc.add(sectionTitleBar("RESULT", branding.primary));

        boolean pass = wr.getWeightedPercentage() >= 33.0;

        PdfPTable table = new PdfPTable(new float[]{1f, 2f, 1f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);

        table.addCell(emptyCell());

        PdfPCell resultCell = new PdfPCell();
        resultCell.setBackgroundColor(WHITE);
        resultCell.setBorderColor(BORDER_GRAY);
        resultCell.setPadding(12);
        resultCell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Font labelFont  = FontFactory.getFont(FontFactory.TIMES_ROMAN, 10, TEXT_MID);
        Font resultFont = FontFactory.getFont(FontFactory.TIMES_BOLD,   14, pass ? PASS_COLOR : FAIL_COLOR);

        Paragraph resultPara = new Paragraph();
        resultPara.setAlignment(Element.ALIGN_CENTER);
        resultPara.add(new Chunk("Result:  ", labelFont));
        resultPara.add(new Chunk(pass ? "PASS" : "FAIL", resultFont));
        resultCell.addElement(resultPara);
        table.addCell(resultCell);

        table.addCell(emptyCell());
        doc.add(table);
    }

    // ── SIGNATURES ────────────────────────────────────────────────────────

    private void addSignatures(Document doc, BrandingConfig branding) throws DocumentException {
        if (branding.footerText != null && !branding.footerText.isBlank()) {
            Paragraph p = new Paragraph(branding.footerText, F_BODY_SM);
            p.setAlignment(Element.ALIGN_CENTER);
            p.setSpacingBefore(12);
            p.setSpacingAfter(8);
            doc.add(p);
        } else {
            Paragraph spacer = new Paragraph(" ");
            spacer.setSpacingBefore(16);
            doc.add(spacer);
        }

        // Signature lines
        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);

        for (String label : new String[]{"Class Teacher", "Principal", "Parent / Guardian"}) {
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.TOP);
            cell.setBorderColor(BORDER_GRAY);
            cell.setBorderWidth(0.8f);
            cell.setPadding(6);
            Paragraph p = new Paragraph(label, F_SIGN_LABEL);
            p.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(p);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
        doc.add(table);

        // Footer — small, gray, centered
        Font footerFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 7, new Color(180, 180, 180));
        Paragraph footer = new Paragraph("Powered by Edunexify\u00ae", footerFont);
        footer.setAlignment(Element.ALIGN_CENTER);
        footer.setSpacingBefore(10);
        doc.add(footer);

        // QR verification code — only when published (token present)
        if (currentVerificationToken != null && !currentVerificationToken.isBlank()) {
            addQrVerification(doc, currentVerificationToken, branding);
        }
    }

    private void addQrVerification(Document doc, String token, BrandingConfig branding)
            throws DocumentException {
        String verifyUrl = frontendUrl.replaceAll("/$", "") + "/verify-rc?token=" + token;

        // Generate QR code as a byte array image
        byte[] qrBytes = generateQrBytes(verifyUrl, 120);
        if (qrBytes == null) return;

        try {
            Image qrImage = Image.getInstance(qrBytes);
            qrImage.scaleToFit(60, 60);

            // Layout: QR on right + "Verify authenticity" label
            PdfPTable verifyTable = new PdfPTable(new float[]{3f, 1f});
            verifyTable.setWidthPercentage(100);
            verifyTable.setSpacingBefore(10);

            // Left cell: verify text
            PdfPCell textCell = new PdfPCell();
            textCell.setBorder(Rectangle.NO_BORDER);
            textCell.setVerticalAlignment(Element.ALIGN_BOTTOM);
            Font verifyFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 7, TEXT_MID);
            Paragraph vp = new Paragraph("Scan to verify authenticity of this report card.\n" + verifyUrl, verifyFont);
            textCell.addElement(vp);
            verifyTable.addCell(textCell);

            // Right cell: QR image
            PdfPCell qrCell = new PdfPCell(qrImage, true);
            qrCell.setBorder(Rectangle.BOX);
            qrCell.setBorderColor(BORDER_GRAY);
            qrCell.setBorderWidth(0.5f);
            qrCell.setPadding(3);
            qrCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
            verifyTable.addCell(qrCell);

            doc.add(verifyTable);
        } catch (Exception e) {
            log.warn("Failed to embed QR code in PDF: {}", e.getMessage());
        }
    }

    private byte[] generateQrBytes(String content, int size) {
        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new java.util.EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.MARGIN, 1);
            hints.put(EncodeHintType.ERROR_CORRECTION,
                      com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.M);
            BitMatrix matrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage image = MatrixToImageWriter.toBufferedImage(matrix);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(image, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception e) {
            log.warn("QR generation failed: {}", e.getMessage());
            return null;
        }
    }

    // ── Grade helpers ─────────────────────────────────────────────────────

    String gradeFromPct(double pct, String gradingSystem) {
        switch (gradingSystem != null ? gradingSystem : "CBSE") {
            case "PERCENTAGE" -> { return DF0.format(pct) + "%"; }
            case "LETTER" -> {
                if (pct >= 90) return "A+";
                if (pct >= 80) return "A";
                if (pct >= 70) return "B+";
                if (pct >= 60) return "B";
                if (pct >= 50) return "C+";
                if (pct >= 40) return "C";
                if (pct >= 33) return "D";
                return "F";
            }
            default -> { // CBSE
                if (pct >= 91) return "A1";
                if (pct >= 81) return "A2";
                if (pct >= 71) return "B1";
                if (pct >= 61) return "B2";
                if (pct >= 51) return "C1";
                if (pct >= 41) return "C2";
                if (pct >= 33) return "D";
                return "E";
            }
        }
    }

    private double cbseGradePoint(String grade) {
        return switch (grade) {
            case "A1" -> 10.0;
            case "A2" ->  9.0;
            case "B1" ->  8.0;
            case "B2" ->  7.0;
            case "C1" ->  6.0;
            case "C2" ->  5.0;
            case "D"  ->  4.0;
            default   ->  0.0;
        };
    }

    private Font gradeFont(double pct) {
        if (pct >= 75) return F_GRADE_A;
        if (pct >= 55) return F_GRADE_B;
        if (pct >= 33) return F_GRADE_C;
        return F_GRADE_F;
    }

    // ── Cell / table builders ─────────────────────────────────────────────

    /**
     * Elegant section separator — 3pt left accent bar in primary color, light tinted
     * background, title text in primary color. Replaces the old solid-filled blue bar.
     * Mirrors Angular's .rc-section-title with border-left + tinted background.
     */
    private PdfPTable sectionTitleBar(String title, Color color) throws DocumentException {
        // Two-column approach: narrow colored left bar | label text
        PdfPTable t = new PdfPTable(new float[]{0.025f, 1f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(10);
        t.setSpacingAfter(3);

        // Left accent bar — solid primary color, no border
        PdfPCell accent = new PdfPCell(new Phrase(" "));
        accent.setBackgroundColor(color);
        accent.setBorder(Rectangle.NO_BORDER);
        t.addCell(accent);

        // Very light tint: 8% primary + 92% white
        Color lightTint = new Color(
            (int)(color.getRed()   * 0.08 + 247),
            (int)(color.getGreen() * 0.08 + 247),
            (int)(color.getBlue()  * 0.08 + 247)
        );
        Font titleFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 8, color);
        PdfPCell label = new PdfPCell(new Phrase(title, titleFont));
        label.setBackgroundColor(lightTint);
        label.setBorderColor(new Color(210, 210, 210));
        label.setPaddingLeft(8);
        label.setPaddingTop(5);
        label.setPaddingBottom(5);
        t.addCell(label);

        return t;
    }

    /** Table header cell: solid primary background, white Times-Bold, left-aligned. */
    private PdfPCell thCell(String text, Color color) {
        PdfPCell c = new PdfPCell(new Phrase(text, F_TH));
        c.setBackgroundColor(color);
        c.setBorderColor(color);
        c.setPadding(6);
        return c;
    }

    /** Table header cell: solid primary background, white Times-Bold, center-aligned. */
    private PdfPCell thCellCenter(String text, Color color) {
        PdfPCell c = thCell(text, color);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    /** Data cell: white bg, gray border, left-aligned. Muted = smaller gray font. */
    private PdfPCell tdCell(String text, boolean muted) {
        PdfPCell c = new PdfPCell(new Phrase(text, muted ? F_BODY_SM : F_BODY));
        c.setBackgroundColor(WHITE);
        c.setBorderColor(BORDER_GRAY);
        c.setPadding(5);
        return c;
    }

    /** Data cell: white bg, gray border, center-aligned. */
    private PdfPCell tdCellCenter(String text, boolean muted) {
        PdfPCell c = tdCell(text, muted);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    /** Grade cell: white bg, colored text only (no background fill), center-aligned. */
    private PdfPCell tdGradeCenter(String grade, double pct) {
        PdfPCell c = new PdfPCell(new Phrase(grade, gradeFont(pct)));
        c.setBackgroundColor(WHITE);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setBorderColor(BORDER_GRAY);
        c.setPadding(5);
        return c;
    }

    private PdfPCell emptyCell() {
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    // ── Config JSON helpers ───────────────────────────────────────────────

    private List<String> parseActivities(String configJson) {
        return parseStringList(configJson, "activities",
                List.of("Discipline", "Sports", "Co-Curricular"));
    }

    private List<String> parseGradeScale(String configJson) {
        return parseStringList(configJson, "gradeScale", List.of("A", "B", "C", "D"));
    }

    @SuppressWarnings("unchecked")
    private List<String> parseStringList(String json, String key, List<String> defaults) {
        if (json == null || json.isBlank()) return defaults;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> map = om.readValue(json, Map.class);
            Object val = map.get(key);
            if (val instanceof List) return (List<String>) val;
        } catch (Exception ignored) {}
        return defaults;
    }

    private float[] buildCoWidths(int gradeCount) {
        float[] w = new float[gradeCount + 2];
        w[0] = 3f;
        for (int i = 1; i <= gradeCount; i++) w[i] = 0.6f;
        w[gradeCount + 1] = 2f;
        return w;
    }

    // ── Misc ──────────────────────────────────────────────────────────────

    private List<ReportCardTemplateDTO.SectionDTO> getSortedEnabledSections(
            ReportCardTemplateDTO template) {
        if (template == null || template.getSections() == null) return List.of();
        return template.getSections().stream()
                .filter(s -> Boolean.TRUE.equals(s.getEnabled()))
                .sorted(Comparator.comparingInt(s -> s.getDisplayOrder() != null ? s.getDisplayOrder() : 0))
                .toList();
    }

    private String safe(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private String buildContact(ReportCardDataDTO data) {
        List<String> parts = new ArrayList<>();
        if (data.getSchoolPhone() != null && !data.getSchoolPhone().isBlank())
            parts.add(data.getSchoolPhone());
        if (data.getSchoolEmail() != null && !data.getSchoolEmail().isBlank())
            parts.add(data.getSchoolEmail());
        return String.join("  |  ", parts);
    }
}
