package com.indraacademy.ias_management.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import com.indraacademy.ias_management.dto.ReportCardDataDTO;
import com.indraacademy.ias_management.dto.ReportCardTemplateDTO;
import com.indraacademy.ias_management.dto.WeightedGroupResultDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.DecimalFormat;
import java.util.*;
import java.util.List;

/**
 * Phase 5: Generates a branded, CBSE-compliant PDF report card.
 * Uses OpenPDF 1.3.11 (com.github.librepdf:openpdf) — colors are java.awt.Color.
 *
 * Branding features applied via template brandingJson:
 *   - Custom primary colour (header bars, accent cells)
 *   - Optional diagonal watermark
 *   - CGPA display (CBSE mode)
 *   - Grade-point column in marks table
 *   - Custom footer / signature text
 */
@Service
public class ReportCardPdfGenerator {

    private static final Logger log = LoggerFactory.getLogger(ReportCardPdfGenerator.class);

    // ── Static palette ─────────────────────────────────────────────────────
    private static final Color NAVY        = new Color(15,  23,  42);
    private static final Color BLUE        = new Color(21,  101, 192);
    private static final Color TEAL        = new Color(79,  189, 189);
    private static final Color LIGHT_BLUE  = new Color(232, 244, 248);
    private static final Color LIGHT_GRAY  = new Color(248, 249, 250);
    private static final Color MID_GRAY    = new Color(214, 227, 235);
    private static final Color TEXT_DARK   = new Color(30,  30,  50);
    private static final Color TEXT_MID    = new Color(80,  80,  100);
    private static final Color PASS_GREEN  = new Color(212, 237, 218);
    private static final Color PASS_TEXT   = new Color(21,  87,  36);
    private static final Color FAIL_RED    = new Color(248, 215, 218);
    private static final Color FAIL_TEXT   = new Color(114, 28,  36);
    private static final Color PURPLE_BG   = new Color(237, 233, 254);
    private static final Color PURPLE_TEXT = new Color(67,  56,  202);
    private static final Color CGPA_BG     = new Color(224, 242, 254);
    private static final Color CGPA_TEXT   = new Color(7,   89, 133);

    // ── Fonts ─────────────────────────────────────────────────────────────
    private static final Font F_SCHOOL_NAME  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  16, Color.WHITE);
    private static final Font F_SCHOOL_DET   = FontFactory.getFont(FontFactory.HELVETICA,         8, new Color(200, 220, 240));
    private static final Font F_RC_LABEL     = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  11, Color.WHITE);
    private static final Font F_SESSION      = FontFactory.getFont(FontFactory.HELVETICA,          8, new Color(180, 210, 240));
    private static final Font F_SEC_TITLE    = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    9, Color.WHITE);
    private static final Font F_TH           = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    8, Color.WHITE);
    private static final Font F_LABEL        = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    7, TEAL);
    private static final Font F_VALUE        = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    9, TEXT_DARK);
    private static final Font F_BODY         = FontFactory.getFont(FontFactory.HELVETICA,          9, TEXT_DARK);
    private static final Font F_BODY_SM      = FontFactory.getFont(FontFactory.HELVETICA,          8, TEXT_MID);
    private static final Font F_GRADE_A      = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    8, new Color(21,  87,  36));
    private static final Font F_GRADE_B      = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    8, new Color(12,  84,  96));
    private static final Font F_GRADE_C      = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    8, new Color(133, 100,  4));
    private static final Font F_GRADE_F      = FontFactory.getFont(FontFactory.HELVETICA_BOLD,    8, new Color(114,  28, 36));
    private static final Font F_SUMMARY_GRD  = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  13, TEXT_DARK);
    private static final Font F_CGPA         = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  14, CGPA_TEXT);
    private static final Font F_PROMO_PASS   = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  16, PASS_TEXT);
    private static final Font F_PROMO_FAIL   = FontFactory.getFont(FontFactory.HELVETICA_BOLD,  16, FAIL_TEXT);
    private static final Font F_SIGN_LABEL   = FontFactory.getFont(FontFactory.HELVETICA,          8, TEXT_MID);

    private static final DecimalFormat DF1 = new DecimalFormat("0.0");
    private static final DecimalFormat DF0 = new DecimalFormat("0");

    // ── Branding config ────────────────────────────────────────────────────

    private static class BrandingConfig {
        Color primary     = BLUE;
        Color primaryDark = NAVY;
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
            if (pc != null) {
                cfg.primary     = hexToColor(pc);
                cfg.primaryDark = darken(cfg.primary, 0.55f);
            }
            cfg.showWatermark = Boolean.TRUE.equals(map.get("showWatermark"));
            cfg.watermarkText = (String) map.getOrDefault("watermarkText", "");
            cfg.footerText    = (String) map.getOrDefault("footerText", "");
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
            int r = Integer.parseInt(h.substring(0, 2), 16);
            int g = Integer.parseInt(h.substring(2, 4), 16);
            int b = Integer.parseInt(h.substring(4, 6), 16);
            return new Color(r, g, b);
        } catch (Exception e) {
            return BLUE;
        }
    }

    private static Color darken(Color c, float factor) {
        float f = 1f - factor;
        return new Color(
                Math.round(c.getRed()   * f),
                Math.round(c.getGreen() * f),
                Math.round(c.getBlue()  * f));
    }

    // ── Public API ────────────────────────────────────────────────────────

    public byte[] generate(ReportCardDataDTO data) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, baos);

            BrandingConfig branding = parseBranding(data.getTemplate());

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

    private void addSchoolHeader(Document doc, ReportCardDataDTO data,
                                  BrandingConfig branding) throws DocumentException {
        PdfPTable table = new PdfPTable(new float[]{3f, 1f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(10);

        PdfPCell left = new PdfPCell();
        left.setBackgroundColor(branding.primaryDark);
        left.setPadding(12);
        left.setBorder(Rectangle.NO_BORDER);

        Paragraph schoolName = new Paragraph(safe(data.getSchoolName(), "School Name"), F_SCHOOL_NAME);
        schoolName.setSpacingAfter(3);
        left.addElement(schoolName);

        if (data.getSchoolAddress() != null && !data.getSchoolAddress().isBlank()) {
            left.addElement(new Paragraph(data.getSchoolAddress(), F_SCHOOL_DET));
        }
        if (data.getAffiliationNumber() != null && !data.getAffiliationNumber().isBlank()) {
            left.addElement(new Paragraph("Affiliation No: " + data.getAffiliationNumber(), F_SCHOOL_DET));
        }
        String contact = buildContact(data);
        if (!contact.isBlank()) {
            left.addElement(new Paragraph(contact, F_SCHOOL_DET));
        }
        table.addCell(left);

        PdfPCell right = new PdfPCell();
        right.setBackgroundColor(branding.primary);
        right.setPadding(12);
        right.setBorder(Rectangle.NO_BORDER);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        right.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph rcLabel = new Paragraph("REPORT CARD", F_RC_LABEL);
        rcLabel.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(rcLabel);

        if (data.getSession() != null) {
            Paragraph sess = new Paragraph("Session: " + data.getSession(), F_SESSION);
            sess.setAlignment(Element.ALIGN_RIGHT);
            right.addElement(sess);
        }
        table.addCell(right);
        doc.add(table);
    }

    // ── STUDENT_INFO ──────────────────────────────────────────────────────

    private void addStudentInfo(Document doc, ReportCardDataDTO data,
                                 BrandingConfig branding) throws DocumentException {
        doc.add(sectionTitleBar("STUDENT INFORMATION", branding.primary));

        PdfPTable table = new PdfPTable(new float[]{1f, 1f, 1f, 1f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);

        addInfoCell(table, "Student Name",  safe(data.getStudentName(), "—"));
        addInfoCell(table, "Student ID",    safe(data.getStudentId(), "—"));
        addInfoCell(table, "Class",         safe(data.getClassName(), "—"));
        addInfoCell(table, "Date of Birth", safe(data.getDateOfBirth(), "—"));
        addInfoCell(table, "Father's Name", safe(data.getFatherName(), "—"));
        addInfoCell(table, "Mother's Name", safe(data.getMotherName(), "—"));
        doc.add(table);
    }

    // ── MARKS_TABLE ───────────────────────────────────────────────────────

    private void addMarksTable(Document doc, ReportCardDataDTO data,
                                BrandingConfig branding) throws DocumentException {
        WeightedGroupResultDTO wr = data.getWeightedResult();
        if (wr == null || wr.getMarksTable() == null) return;

        WeightedGroupResultDTO.MarksTableDTO mt = wr.getMarksTable();
        int examCount = mt.getExamColumns().size();
        if (examCount == 0) return;

        doc.add(sectionTitleBar("MARKS TABLE", branding.primary));

        // Columns: Subject + N exams + Grade + (GP if enabled)
        int extraCols = branding.showGradePoints ? 2 : 1;
        float[] colWidths = new float[examCount + 1 + extraCols];
        colWidths[0] = 3f;
        for (int i = 1; i <= examCount; i++) colWidths[i] = 1.4f;
        colWidths[examCount + 1] = 1.2f;
        if (branding.showGradePoints) colWidths[examCount + 2] = 1.0f;

        PdfPTable table = new PdfPTable(colWidths);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);

        table.addCell(headerCell("Subject", branding.primary));
        for (WeightedGroupResultDTO.MarksTableDTO.ExamColumnDTO col : mt.getExamColumns()) {
            String label = col.getExamName() + "\n(" + DF0.format(col.getWeightage() * 100) + "%)";
            table.addCell(headerCellCenter(label, branding.primary));
        }
        table.addCell(headerCellCenter("Grade", branding.primary));
        if (branding.showGradePoints) {
            table.addCell(headerCellCenter("GP", branding.primary));
        }

        boolean alt = false;
        for (WeightedGroupResultDTO.MarksTableDTO.SubjectRowDTO row : mt.getSubjectRows()) {
            Color rowBg = alt ? LIGHT_GRAY : Color.WHITE;
            alt = !alt;

            table.addCell(dataCell(row.getSubjectName(), rowBg, false));
            List<WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO> marks = row.getExamMarks();
            for (int i = 0; i < examCount; i++) {
                WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO mark =
                        (marks != null && i < marks.size()) ? marks.get(i) : null;
                if (mark == null || mark.getObtained() == null) {
                    table.addCell(dataCellCenter("Ab", rowBg, true));
                } else {
                    String txt = DF0.format(mark.getObtained()) + "/" + DF0.format(mark.getMax())
                            + "\n" + gradeFromPct(mark.getPercentage(), data.getGradingSystem());
                    table.addCell(dataCellCenter(txt, rowBg, false));
                }
            }
            String subGrade = gradeFromPct(row.getWeightedPercentage(), data.getGradingSystem());
            table.addCell(gradeCellCenter(subGrade, row.getWeightedPercentage()));
            if (branding.showGradePoints) {
                double gp = cbseGradePoint(subGrade);
                table.addCell(dataCellCenter(DF1.format(gp), rowBg, false));
            }
        }

        // Totals row
        if (mt.getExamTotals() != null && !mt.getExamTotals().isEmpty()) {
            PdfPCell totalLabel = new PdfPCell(new Phrase("TOTAL", F_TH));
            totalLabel.setBackgroundColor(LIGHT_BLUE);
            totalLabel.setPadding(5);
            totalLabel.setBorderColor(TEAL);
            table.addCell(totalLabel);

            for (WeightedGroupResultDTO.MarksTableDTO.ExamTotalDTO total : mt.getExamTotals()) {
                PdfPCell tc = new PdfPCell(new Phrase(
                        DF0.format(total.getObtained()) + "/" + DF0.format(total.getMax()), F_VALUE));
                tc.setBackgroundColor(LIGHT_BLUE);
                tc.setHorizontalAlignment(Element.ALIGN_CENTER);
                tc.setPadding(5);
                tc.setBorderColor(TEAL);
                table.addCell(tc);
            }
            String overallGrade = gradeFromPct(wr.getWeightedPercentage(), data.getGradingSystem());
            PdfPCell og = new PdfPCell(new Phrase(overallGrade, gradeFont(wr.getWeightedPercentage())));
            og.setBackgroundColor(LIGHT_BLUE);
            og.setHorizontalAlignment(Element.ALIGN_CENTER);
            og.setPadding(5);
            og.setBorderColor(TEAL);
            table.addCell(og);

            if (branding.showGradePoints) {
                double cgp = data.getCgpa() != null ? data.getCgpa() : cbseGradePoint(overallGrade);
                Font cgpFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, CGPA_TEXT);
                PdfPCell gpCell = new PdfPCell(new Phrase(DF1.format(cgp), cgpFont));
                gpCell.setBackgroundColor(CGPA_BG);
                gpCell.setHorizontalAlignment(Element.ALIGN_CENTER);
                gpCell.setPadding(5);
                gpCell.setBorderColor(TEAL);
                table.addCell(gpCell);
            }
        }
        doc.add(table);
    }

    // ── ASSESSMENT_SUMMARY ────────────────────────────────────────────────

    private void addAssessmentSummary(Document doc, ReportCardDataDTO data,
                                       BrandingConfig branding) throws DocumentException {
        WeightedGroupResultDTO wr = data.getWeightedResult();
        if (wr == null) return;

        doc.add(sectionTitleBar("ASSESSMENT SUMMARY", branding.primary));

        boolean showCgpa = branding.showCgpa && data.getCgpa() != null;
        float[] topWidths = showCgpa
                ? new float[]{2f, 1f, 1f, 1.2f}
                : new float[]{2f, 1f, 1f};

        PdfPTable top = new PdfPTable(topWidths);
        top.setWidthPercentage(100);
        top.setSpacingAfter(4);

        // Percentage
        PdfPCell pctCell = new PdfPCell();
        pctCell.setBackgroundColor(LIGHT_BLUE);
        pctCell.setBorderColor(TEAL);
        pctCell.setPadding(10);
        Font pctFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18, branding.primary);
        Paragraph pctPara = new Paragraph(DF1.format(wr.getWeightedPercentage()) + "%", pctFont);
        pctPara.setSpacingAfter(2);
        pctCell.addElement(pctPara);
        pctCell.addElement(new Paragraph("Overall Weighted Score", F_BODY_SM));
        top.addCell(pctCell);

        // Grade
        PdfPCell gradeCell = new PdfPCell();
        gradeCell.setBackgroundColor(LIGHT_BLUE);
        gradeCell.setBorderColor(TEAL);
        gradeCell.setPadding(10);
        gradeCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        gradeCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        String grade = gradeFromPct(wr.getWeightedPercentage(), data.getGradingSystem());
        Paragraph gradePara = new Paragraph(grade, F_SUMMARY_GRD);
        gradePara.setAlignment(Element.ALIGN_CENTER);
        gradeCell.addElement(gradePara);
        gradeCell.addElement(centeredParagraph("Grade", F_BODY_SM));
        top.addCell(gradeCell);

        // Rank
        PdfPCell rankCell = new PdfPCell();
        rankCell.setBackgroundColor(LIGHT_BLUE);
        rankCell.setBorderColor(TEAL);
        rankCell.setPadding(10);
        rankCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        rankCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        String rankStr = wr.getRank() > 0 ? String.valueOf(wr.getRank()) : "—";
        Paragraph rankPara = new Paragraph(rankStr, F_SUMMARY_GRD);
        rankPara.setAlignment(Element.ALIGN_CENTER);
        rankCell.addElement(rankPara);
        rankCell.addElement(centeredParagraph("Class Rank", F_BODY_SM));
        top.addCell(rankCell);

        // CGPA
        if (showCgpa) {
            PdfPCell cgpaCell = new PdfPCell();
            cgpaCell.setBackgroundColor(CGPA_BG);
            cgpaCell.setBorderColor(TEAL);
            cgpaCell.setPadding(10);
            cgpaCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cgpaCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            Paragraph cgpaPara = new Paragraph(DF1.format(data.getCgpa()), F_CGPA);
            cgpaPara.setAlignment(Element.ALIGN_CENTER);
            cgpaCell.addElement(cgpaPara);
            cgpaCell.addElement(centeredParagraph("CGPA", F_BODY_SM));
            top.addCell(cgpaCell);
        }
        doc.add(top);

        // Exam breakdown rows
        if (wr.getExamBreakdowns() != null && !wr.getExamBreakdowns().isEmpty()) {
            PdfPTable bd = new PdfPTable(new float[]{2f, 1f, 1f, 1f, 1f});
            bd.setWidthPercentage(100);
            bd.setSpacingAfter(8);

            bd.addCell(headerCell("Exam", branding.primary));
            bd.addCell(headerCellCenter("Obtained/Max", branding.primary));
            bd.addCell(headerCellCenter("Percentage", branding.primary));
            bd.addCell(headerCellCenter("Weightage", branding.primary));
            bd.addCell(headerCellCenter("Contribution", branding.primary));

            boolean alt = false;
            for (WeightedGroupResultDTO.ExamBreakdownDTO ex : wr.getExamBreakdowns()) {
                Color bg = alt ? LIGHT_GRAY : Color.WHITE; alt = !alt;
                bd.addCell(dataCell(ex.getExamName(), bg, false));
                bd.addCell(dataCellCenter(DF0.format(ex.getObtained()) + "/" + DF0.format(ex.getMax()), bg, false));
                bd.addCell(dataCellCenter(DF1.format(ex.getPercentage()) + "%", bg, false));
                bd.addCell(dataCellCenter(DF0.format(ex.getWeightage() * 100) + "%", bg, false));
                bd.addCell(dataCellCenter(DF1.format(ex.getContribution()) + " pts", bg, false));
            }
            doc.add(bd);
        }

        // Group breakdown rows
        if (wr.getGroupBreakdowns() != null && !wr.getGroupBreakdowns().isEmpty()) {
            PdfPTable bd = new PdfPTable(new float[]{2f, 1f, 1f, 1f});
            bd.setWidthPercentage(100);
            bd.setSpacingAfter(8);

            bd.addCell(headerCell("Component", branding.primary));
            bd.addCell(headerCellCenter("Percentage", branding.primary));
            bd.addCell(headerCellCenter("Weightage", branding.primary));
            bd.addCell(headerCellCenter("Contribution", branding.primary));

            boolean alt = false;
            for (WeightedGroupResultDTO.GroupBreakdownDTO grp : wr.getGroupBreakdowns()) {
                Color bg = alt ? LIGHT_GRAY : Color.WHITE; alt = !alt;
                bd.addCell(dataCell(grp.getGroupName(), bg, false));
                bd.addCell(dataCellCenter(DF1.format(grp.getPercentage()) + "%", bg, false));
                bd.addCell(dataCellCenter(DF0.format(grp.getWeightage() * 100) + "%", bg, false));
                bd.addCell(dataCellCenter(DF1.format(grp.getContribution()) + " pts", bg, false));
            }
            doc.add(bd);
        }
    }

    // ── ATTENDANCE ────────────────────────────────────────────────────────

    private void addAttendance(Document doc, ReportCardDataDTO data,
                                BrandingConfig branding) throws DocumentException {
        ReportCardDataDTO.AttendanceBlock att = data.getAttendance();
        doc.add(sectionTitleBar("ATTENDANCE", branding.primary));

        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);

        table.addCell(attCell("Working Days", String.valueOf(att.getWorkingDays()), branding.primary));
        table.addCell(attCell("Days Present",  String.valueOf(att.getPresentDays()), branding.primary));
        table.addCell(attCell("Attendance",    DF1.format(att.getPercentage()) + "%", branding.primary));
        doc.add(table);
    }

    // ── CO_SCHOLASTIC ─────────────────────────────────────────────────────

    private void addCoScholastic(Document doc, ReportCardDataDTO data,
                                  ReportCardTemplateDTO.SectionDTO section,
                                  BrandingConfig branding) throws DocumentException {
        doc.add(sectionTitleBar("CO-SCHOLASTIC ACTIVITIES", branding.primary));

        List<ReportCardDataDTO.CoScholasticGrade> grades = data.getCoScholasticGrades();

        if (grades != null && !grades.isEmpty()) {
            PdfPTable table = new PdfPTable(new float[]{3f, 1f});
            table.setWidthPercentage(60);
            table.setSpacingAfter(8);

            table.addCell(headerCell("Activity", branding.primary));
            table.addCell(headerCellCenter("Grade", branding.primary));

            boolean alt = false;
            for (ReportCardDataDTO.CoScholasticGrade g : grades) {
                Color bg = alt ? LIGHT_GRAY : Color.WHITE; alt = !alt;
                table.addCell(dataCell(g.getActivity(), bg, false));
                if (g.getGrade() != null && !g.getGrade().isBlank()) {
                    Font pf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, PURPLE_TEXT);
                    PdfPCell gc = new PdfPCell(new Phrase(g.getGrade(), pf));
                    gc.setBackgroundColor(PURPLE_BG);
                    gc.setHorizontalAlignment(Element.ALIGN_CENTER);
                    gc.setPadding(5);
                    gc.setBorderColor(MID_GRAY);
                    table.addCell(gc);
                } else {
                    table.addCell(dataCellCenter("—", bg, true));
                }
            }
            doc.add(table);
        } else {
            List<String> activities = parseActivities(section.getConfigJson());
            List<String> gradeScale = parseGradeScale(section.getConfigJson());

            PdfPTable table = new PdfPTable(buildCoWidths(gradeScale.size()));
            table.setWidthPercentage(80);
            table.setSpacingAfter(8);

            table.addCell(headerCell("Activity", branding.primary));
            for (String g : gradeScale) table.addCell(headerCellCenter(g, branding.primary));
            table.addCell(headerCell("Remarks", branding.primary));

            for (String act : activities) {
                table.addCell(dataCell(act, Color.WHITE, false));
                for (String ignored : gradeScale) {
                    PdfPCell box = new PdfPCell();
                    box.setFixedHeight(18f);
                    box.setBorderColor(MID_GRAY);
                    box.setHorizontalAlignment(Element.ALIGN_CENTER);
                    table.addCell(box);
                }
                PdfPCell rem = new PdfPCell();
                rem.setFixedHeight(18f);
                rem.setBorderColor(MID_GRAY);
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
        cell.setBorderColor(TEAL);
        cell.setPadding(10);
        cell.setMinimumHeight(40f);

        if (text != null && !text.isBlank()) {
            cell.setBackgroundColor(LIGHT_BLUE);
            Font italicFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, TEXT_DARK);
            cell.addElement(new Paragraph(text, italicFont));
        } else {
            cell.setBackgroundColor(Color.WHITE);
        }
        table.addCell(cell);
        doc.add(table);

        PdfPTable sig = new PdfPTable(new float[]{3f, 1f});
        sig.setWidthPercentage(100);
        sig.setSpacingAfter(8);

        PdfPCell blank = new PdfPCell(new Phrase(" "));
        blank.setBorder(Rectangle.NO_BORDER);
        sig.addCell(blank);

        PdfPCell signCell = new PdfPCell();
        signCell.setBorder(Rectangle.TOP);
        signCell.setBorderColor(TEXT_MID);
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

        PdfPCell badge = new PdfPCell();
        badge.setBackgroundColor(pass ? PASS_GREEN : FAIL_RED);
        badge.setBorderColor(pass ? new Color(195, 230, 203) : new Color(245, 198, 203));
        badge.setPadding(14);
        badge.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph result = new Paragraph(pass ? "PASS" : "FAIL", pass ? F_PROMO_PASS : F_PROMO_FAIL);
        result.setAlignment(Element.ALIGN_CENTER);
        badge.addElement(result);
        table.addCell(badge);

        table.addCell(emptyCell());
        doc.add(table);
    }

    // ── SIGNATURES ────────────────────────────────────────────────────────

    private void addSignatures(Document doc, BrandingConfig branding) throws DocumentException {
        if (branding.footerText != null && !branding.footerText.isBlank()) {
            Font ft = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, TEXT_MID);
            Paragraph p = new Paragraph(branding.footerText, ft);
            p.setAlignment(Element.ALIGN_CENTER);
            p.setSpacingBefore(12);
            p.setSpacingAfter(8);
            doc.add(p);
        } else {
            Paragraph spacer = new Paragraph(" ");
            spacer.setSpacingBefore(16);
            doc.add(spacer);
        }

        PdfPTable table = new PdfPTable(3);
        table.setWidthPercentage(100);

        for (String label : new String[]{"Class Teacher", "Principal", "Parent / Guardian"}) {
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.TOP);
            cell.setBorderColor(TEXT_MID);
            cell.setBorderWidth(0.8f);
            cell.setPadding(6);
            Paragraph p = new Paragraph(label, F_SIGN_LABEL);
            p.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(p);
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            table.addCell(cell);
        }
        doc.add(table);
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

    // ── Cell / Table builders ─────────────────────────────────────────────

    private PdfPTable sectionTitleBar(String title, Color color) throws DocumentException {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(8);
        t.setSpacingAfter(4);
        PdfPCell cell = new PdfPCell(new Phrase(title, F_SEC_TITLE));
        cell.setBackgroundColor(color);
        cell.setPadding(6);
        cell.setBorderColor(color);
        t.addCell(cell);
        return t;
    }

    private PdfPCell headerCell(String text, Color color) {
        PdfPCell c = new PdfPCell(new Phrase(text, F_TH));
        c.setBackgroundColor(color);
        c.setPadding(6);
        c.setBorderColor(color);
        return c;
    }

    private PdfPCell headerCellCenter(String text, Color color) {
        PdfPCell c = headerCell(text, color);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    private void addInfoCell(PdfPTable table, String label, String value) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(MID_GRAY);
        cell.setPadding(7);
        cell.addElement(new Paragraph(label.toUpperCase(), F_LABEL));
        cell.addElement(new Paragraph(value, F_VALUE));
        table.addCell(cell);
    }

    private PdfPCell dataCell(String text, Color bg, boolean muted) {
        Font f = muted ? F_BODY_SM : F_BODY;
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(bg);
        c.setPadding(5);
        c.setBorderColor(MID_GRAY);
        return c;
    }

    private PdfPCell dataCellCenter(String text, Color bg, boolean muted) {
        PdfPCell c = dataCell(text, bg, muted);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        return c;
    }

    private PdfPCell gradeCellCenter(String grade, double pct) {
        PdfPCell c = new PdfPCell(new Phrase(grade, gradeFont(pct)));
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setPadding(5);
        c.setBorderColor(MID_GRAY);
        return c;
    }

    private PdfPCell attCell(String label, String value, Color accent) {
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(LIGHT_BLUE);
        c.setBorderColor(TEAL);
        c.setPadding(10);
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        Font valFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, accent);
        Paragraph val = new Paragraph(value, valFont);
        val.setAlignment(Element.ALIGN_CENTER);
        c.addElement(val);
        Paragraph lbl = new Paragraph(label, F_BODY_SM);
        lbl.setAlignment(Element.ALIGN_CENTER);
        c.addElement(lbl);
        return c;
    }

    private PdfPCell emptyCell() {
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(Rectangle.NO_BORDER);
        return c;
    }

    private Paragraph centeredParagraph(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_CENTER);
        return p;
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
