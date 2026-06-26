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

    /**
     * Same directory SchoolService writes logos to.
     * Relative paths like /uploads/school-logos/1.png are resolved against this.
     */
    @Value("${school.logo.directory:./uploads/school-logos}")
    private String logoDirectory;

    @Value("${school.report-card-header.directory:./uploads/report-card-headers}")
    private String headerDirectory;

    /**
     * Same directory StudentService writes student photos to.
     * Relative paths like /uploads/student-photos/abc.jpg are resolved against this.
     */
    @Value("${student.photo.directory:./uploads/student-photos}")
    private String studentPhotoDirectory;

    // ── Document palette ───────────────────────────────────────────────────
    private static final Color WHITE       = Color.WHITE;
    private static final Color BLACK       = Color.BLACK;
    private static final Color TEXT_DARK   = new Color(26,  26,  26);   // #1a1a1a
    private static final Color TEXT_MID    = new Color(100, 100, 100);  // #646464
    private static final Color BORDER_GRAY = new Color(213, 201, 184);  // #d5c9b8 — parchment border
    private static final Color ROW_ALT     = new Color(245, 240, 232);  // #f5f0e8 — warm zebra
    private static final Color TOTALS_BG   = new Color(245, 240, 232);  // same warm tint for totals

    // ── Indra Academy fixed design palette ────────────────────────────────
    private static final Color PARCHMENT   = new Color(250, 248, 242);  // #faf8f2 — page background
    private static final Color DARK_GREEN  = new Color(30,  61,  30);   // #1e3d1e — headers, titles
    private static final Color GOLD        = new Color(138, 106, 16);   // #8a6a10 — rules, badge, footer

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
        Color   primary         = DARK_GREEN;  // fixed Indra Academy dark green
        boolean showWatermark   = false;
        String  watermarkText   = "";
        String  watermarkType   = "TEXT";
        String  footerText      = "";
        boolean showCgpa        = true;
        boolean showGradePoints = false;
        String  layoutStyle     = "WARM_ELEGANCE"; // fixed — single Indra Academy design
        String  schoolMotto     = "";
        String  examTerm        = "";  // e.g. "Half-Yearly" → shown as "Half-Yearly Examination"
    }

    private BrandingConfig parseBranding(ReportCardTemplateDTO template) {
        BrandingConfig cfg = new BrandingConfig();
        // Always use fixed Indra Academy design — layoutStyle and primary are hardcoded
        if (template == null || template.getBrandingJson() == null
                || template.getBrandingJson().isBlank()) return cfg;
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            @SuppressWarnings("unchecked")
            Map<String, Object> map = om.readValue(template.getBrandingJson(), Map.class);

            // primaryColor and layoutStyle intentionally ignored — design is fixed
            cfg.showWatermark   = Boolean.TRUE.equals(map.get("showWatermark"));
            cfg.watermarkText   = (String) map.getOrDefault("watermarkText", "");
            cfg.watermarkType   = (String) map.getOrDefault("watermarkType", "TEXT");
            cfg.footerText      = (String) map.getOrDefault("footerText", "");
            cfg.schoolMotto     = (String) map.getOrDefault("schoolMotto", "");
            cfg.examTerm        = (String) map.getOrDefault("examTerm", "");

            Object sc  = map.get("showCgpa");
            if (sc  instanceof Boolean) cfg.showCgpa        = (Boolean) sc;
            Object sgp = map.get("showGradePoints");
            if (sgp instanceof Boolean) cfg.showGradePoints = (Boolean) sgp;
        } catch (Exception ignored) {}
        return cfg;
    }

    private boolean isNewLayout(BrandingConfig branding) {
        return "WARM_ELEGANCE".equals(branding.layoutStyle) || "NAVY_SCHOLAR".equals(branding.layoutStyle);
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

            // Always draw parchment background; optionally add watermark on top
            String watermarkText = null;
            byte[] watermarkLogoBytes = null;
            if (branding.showWatermark) {
                if ("LOGO".equalsIgnoreCase(branding.watermarkType)) {
                    watermarkLogoBytes = loadLogoBytes(data.getSchoolLogoUrl());
                    if (watermarkLogoBytes == null) {
                        watermarkText = safe(data.getSchoolName(), "CONFIDENTIAL");
                    }
                } else {
                    watermarkText = (branding.watermarkText != null && !branding.watermarkText.isBlank())
                            ? branding.watermarkText : safe(data.getSchoolName(), "CONFIDENTIAL");
                }
            }
            writer.setPageEvent(new PageSetupEvent(watermarkText, watermarkLogoBytes));

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

    // ── Page setup event: parchment background + optional watermark ──────

    private static class PageSetupEvent extends PdfPageEventHelper {
        private final String text;       // non-null → text watermark
        private final byte[] logoBytes;  // non-null → logo watermark

        PageSetupEvent(String text, byte[] logoBytes) {
            this.text = text;
            this.logoBytes = logoBytes;
        }

        @Override
        public void onStartPage(PdfWriter writer, Document document) {
            // Fill entire page with parchment/cream color
            try {
                PdfContentByte cb = writer.getDirectContentUnder();
                cb.saveState();
                cb.setColorFill(PARCHMENT);
                cb.rectangle(0, 0,
                        document.getPageSize().getWidth(),
                        document.getPageSize().getHeight());
                cb.fill();
                cb.restoreState();
            } catch (Exception ignored) {}
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            if (text == null && logoBytes == null) return;
            try {
                PdfContentByte cb = writer.getDirectContentUnder();
                cb.saveState();
                PdfGState gs = new PdfGState();

                if (logoBytes != null) {
                    gs.setFillOpacity(0.06f);
                    gs.setBlendMode(PdfGState.BM_NORMAL);
                    cb.setGState(gs);
                    Image img = Image.getInstance(logoBytes);
                    float pw = document.getPageSize().getWidth();
                    float ph = document.getPageSize().getHeight();
                    float maxSize = Math.min(pw, ph) * 0.50f;
                    img.scaleToFit(maxSize, maxSize);
                    float x = (pw - img.getScaledWidth())  / 2f;
                    float y = (ph - img.getScaledHeight()) / 2f;
                    img.setAbsolutePosition(x, y);
                    cb.addImage(img);
                } else {
                    gs.setFillOpacity(0.06f);
                    cb.setGState(gs);
                    cb.beginText();
                    cb.setFontAndSize(
                            BaseFont.createFont(BaseFont.HELVETICA, BaseFont.CP1252, BaseFont.NOT_EMBEDDED), 52);
                    cb.setColorFill(new Color(180, 180, 180));
                    cb.showTextAligned(Element.ALIGN_CENTER, text,
                            document.getPageSize().getWidth()  / 2f,
                            document.getPageSize().getHeight() / 2f,
                            45f);
                    cb.endText();
                }
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
        // If a custom header image has been uploaded, use it and skip auto-generation
        if (data.getReportCardHeaderImageUrl() != null && !data.getReportCardHeaderImageUrl().isBlank()) {
            Image headerImg = loadHeaderImage(data.getReportCardHeaderImageUrl());
            if (headerImg != null) {
                // Scale to full page width, preserve aspect ratio
                float pageWidth = doc.right() - doc.left();
                headerImg.scaleToFit(pageWidth, 150);
                headerImg.setAlignment(Image.ALIGN_CENTER);
                doc.add(headerImg);
                // Thin colored divider below
                PdfPTable divider = new PdfPTable(1);
                divider.setWidthPercentage(100);
                divider.setSpacingAfter(6);
                PdfPCell divLine = new PdfPCell(new Phrase(" "));
                divLine.setBorder(Rectangle.BOTTOM);
                divLine.setBorderColor(branding.primary);
                divLine.setBorderWidthBottom(2f);
                divLine.setMinimumHeight(0);
                divider.addCell(divLine);
                doc.add(divider);
                addDocTitleBand(doc, data, branding);
                return;
            }
        }

        // New layout: elegant centered header for WARM_ELEGANCE / NAVY_SCHOLAR
        if (isNewLayout(branding)) {
            addElegantHeader(doc, data, branding);
            return;
        }

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

        // 3. Header table — 2 cols with logo, or 1 col without
        //    (the "REPORT CARD" label is now in the full-width title band below)
        PdfPTable header;
        if (logoImage != null) {
            header = new PdfPTable(new float[]{0.8f, 4.4f});
        } else {
            header = new PdfPTable(1);
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

        // Board type subtitle — e.g. "CBSE Affiliated"
        if (data.getBoardType() != null && !data.getBoardType().isBlank()) {
            String boardLabel = switch (data.getBoardType()) {
                case "CBSE"  -> "CBSE Affiliated";
                case "ICSE"  -> "ICSE Affiliated";
                case "STATE" -> "State Board";
                default      -> "";
            };
            if (!boardLabel.isBlank()) {
                Font boardFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 8.5f, TEXT_MID);
                Paragraph boardPara = new Paragraph(boardLabel, boardFont);
                boardPara.setAlignment(Element.ALIGN_CENTER);
                boardPara.setSpacingAfter(2);
                center.addElement(boardPara);
            }
        }

        // Affiliation number (when present)
        if (data.getAffiliationNumber() != null && !data.getAffiliationNumber().isBlank()) {
            Font affFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 8, TEXT_MID);
            Paragraph affPara = new Paragraph("Affiliation No: " + data.getAffiliationNumber(), affFont);
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
        doc.add(header);

        // Full-width document title band below the school info
        addDocTitleBand(doc, data, branding);
    }

    /**
     * Full-width colored band that unambiguously identifies the document.
     * Shows "REPORT CARD" in large white text, with the academic session and
     * exam/term name (e.g. "Half Yearly") on the line below.
     * Rendered after both the custom header image and the auto-generated header.
     */
    private void addDocTitleBand(Document doc, ReportCardDataDTO data,
                                  BrandingConfig branding) throws DocumentException {
        String layout = branding.layoutStyle;

        if ("WARM_ELEGANCE".equals(layout)) {
            // Gold horizontal rule + widely-spaced "R E P O R T  C A R D" + gold rule
            addHRule(doc, GOLD, 4);
            Font rcFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 13, TEXT_DARK);
            Paragraph rcTitle = new Paragraph("R E P O R T     C A R D", rcFont);
            rcTitle.setAlignment(Element.ALIGN_CENTER);
            rcTitle.setSpacingBefore(5);
            rcTitle.setSpacingAfter(3);
            doc.add(rcTitle);
            // Session line
            String session = buildSessionLine(data, branding);
            if (!session.isBlank()) {
                Font sessionFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 8.5f, TEXT_MID);
                Paragraph sessionPara = new Paragraph(session, sessionFont);
                sessionPara.setAlignment(Element.ALIGN_CENTER);
                sessionPara.setSpacingAfter(5);
                doc.add(sessionPara);
            }
            addHRule(doc, GOLD, 8);
            return;
        }

        if ("NAVY_SCHOLAR".equals(layout)) {
            addHRule(doc, new Color(200, 200, 200), 6);
            Font rcFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 22, branding.primary);
            Paragraph rcTitle = new Paragraph("Report Card", rcFont);
            rcTitle.setAlignment(Element.ALIGN_CENTER);
            rcTitle.setSpacingBefore(4);
            rcTitle.setSpacingAfter(2);
            doc.add(rcTitle);
            addHRuleCentered(doc, branding.primary, 4);
            String session = buildSessionLine(data, branding);
            if (!session.isBlank()) {
                Font sessionFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 7.5f, GOLD);
                Paragraph sessionPara = new Paragraph(session.toUpperCase(), sessionFont);
                sessionPara.setAlignment(Element.ALIGN_CENTER);
                sessionPara.setSpacingAfter(10);
                doc.add(sessionPara);
            }
            return;
        }

        // CLASSIC — solid colored band (original behavior)
        PdfPTable band = new PdfPTable(1);
        band.setWidthPercentage(100);
        band.setSpacingAfter(8);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(branding.primary);
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingTop(9);
        cell.setPaddingBottom(9);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        Font titleFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 15, WHITE);
        Paragraph title = new Paragraph("REPORT CARD", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(3);
        cell.addElement(title);

        String session = buildSessionLine(data);
        if (!session.isBlank()) {
            Font metaFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 9, new Color(210, 220, 230));
            Paragraph metaPara = new Paragraph(session, metaFont);
            metaPara.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(metaPara);
        }

        band.addCell(cell);
        doc.add(band);
    }

    private String buildSessionLine(ReportCardDataDTO data, BrandingConfig branding) {
        StringBuilder meta = new StringBuilder();
        if (data.getSession() != null && !data.getSession().isBlank())
            meta.append("Academic Session ").append(data.getSession());
        // Prefer examTerm from branding; fall back to assessment group name
        String term = (branding.examTerm != null && !branding.examTerm.isBlank())
                ? branding.examTerm
                : (data.getWeightedResult() != null
                        && data.getWeightedResult().getGroupName() != null
                        ? data.getWeightedResult().getGroupName() : "");
        if (!term.isBlank()) {
            if (meta.length() > 0) meta.append(" \u00b7 ");
            meta.append(term);
            if (!term.toLowerCase().contains("exam")) meta.append(" Examination");
        }
        return meta.toString();
    }

    /** Overload for callers that don't have branding context */
    private String buildSessionLine(ReportCardDataDTO data) {
        return buildSessionLine(data, new BrandingConfig());
    }

    private void addHRule(Document doc, Color color, float spacingAfter) throws DocumentException {
        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        rule.setSpacingAfter(spacingAfter);
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(color);
        c.setBorderWidthBottom(1f);
        c.setMinimumHeight(0);
        c.setPadding(0);
        rule.addCell(c);
        doc.add(rule);
    }

    private void addHRuleCentered(Document doc, Color color, float spacingAfter) throws DocumentException {
        PdfPTable rule = new PdfPTable(new float[]{1f, 2f, 1f});
        rule.setWidthPercentage(100);
        rule.setSpacingAfter(spacingAfter);
        rule.addCell(emptyCell());
        PdfPCell c = new PdfPCell(new Phrase(" "));
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(color);
        c.setBorderWidthBottom(2f);
        c.setMinimumHeight(0);
        c.setPadding(0);
        rule.addCell(c);
        rule.addCell(emptyCell());
        doc.add(rule);
    }

    /**
     * Elegant centered header for WARM_ELEGANCE and NAVY_SCHOLAR layouts.
     * Renders: [logo centered] → [school name large centered] → [motto italic] → [affiliation] → [title band]
     */
    private void addElegantHeader(Document doc, ReportCardDataDTO data,
                                   BrandingConfig branding) throws DocumentException {
        // Logo — centered above school name
        Image logoImage = loadLogoImage(data.getSchoolLogoUrl());
        if (logoImage != null) {
            logoImage.scaleToFit(70, 70);
            logoImage.setAlignment(Image.ALIGN_CENTER);
            PdfPTable logoTable = new PdfPTable(1);
            logoTable.setWidthPercentage(100);
            logoTable.setSpacingAfter(4);
            PdfPCell logoCell = new PdfPCell(logoImage, false);
            logoCell.setBorder(Rectangle.NO_BORDER);
            logoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            logoCell.setPaddingTop(12);
            logoCell.setPaddingBottom(4);
            logoTable.addCell(logoCell);
            doc.add(logoTable);
        } else {
            // Initials monogram in a circle (text fallback)
            String name = safe(data.getSchoolName(), "School");
            String[] words = name.trim().split("\\s+");
            String initials = words.length >= 2
                    ? ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase()
                    : name.substring(0, Math.min(2, name.length())).toUpperCase();
            Font initialsFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 22, branding.primary);
            PdfPTable logoTable = new PdfPTable(1);
            logoTable.setWidthPercentage(100);
            logoTable.setSpacingAfter(4);
            PdfPCell initCell = new PdfPCell(new Phrase(initials, initialsFont));
            initCell.setBorder(Rectangle.BOX);
            initCell.setBorderColor(branding.primary);
            initCell.setBorderWidth(1.5f);
            initCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            initCell.setFixedHeight(50f);
            initCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            initCell.setPaddingTop(12);
            // make it look more like a circle by matching width = height
            logoTable.setTotalWidth(50f);
            logoTable.setLockedWidth(false);
            initCell.setPaddingLeft(10);
            initCell.setPaddingRight(10);
            logoTable.addCell(initCell);
            // Just add the initials text centered — circle drawing requires PdfContentByte
            PdfPTable monoTable = new PdfPTable(3);
            monoTable.setWidthPercentage(100);
            monoTable.setSpacingAfter(4);
            monoTable.addCell(emptyCell());
            PdfPCell mono = new PdfPCell(new Phrase(initials, initialsFont));
            mono.setBorder(Rectangle.BOX);
            mono.setBorderColor(branding.primary);
            mono.setBorderWidth(1.5f);
            mono.setHorizontalAlignment(Element.ALIGN_CENTER);
            mono.setVerticalAlignment(Element.ALIGN_MIDDLE);
            mono.setFixedHeight(52f);
            mono.setPadding(6);
            monoTable.addCell(mono);
            monoTable.addCell(emptyCell());
            doc.add(monoTable);
        }

        // School name — large, uppercase, primary color
        Font schoolNameFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 19, branding.primary);
        Paragraph schoolName = new Paragraph(
                safe(data.getSchoolName(), "School Name").toUpperCase(), schoolNameFont);
        schoolName.setAlignment(Element.ALIGN_CENTER);
        schoolName.setSpacingAfter(3);
        doc.add(schoolName);

        // Motto (italic, gold)
        if (branding.schoolMotto != null && !branding.schoolMotto.isBlank()) {
            Color mottoColor = GOLD;
            Font mottoFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 9, mottoColor);
            Paragraph motto = new Paragraph(branding.schoolMotto, mottoFont);
            motto.setAlignment(Element.ALIGN_CENTER);
            motto.setSpacingAfter(3);
            doc.add(motto);
        }

        // Affiliation + address
        StringBuilder metaLine = new StringBuilder();
        if (data.getAffiliationNumber() != null && !data.getAffiliationNumber().isBlank())
            metaLine.append("Affiliation No. ").append(data.getAffiliationNumber());
        if (data.getSchoolAddress() != null && !data.getSchoolAddress().isBlank()) {
            if (metaLine.length() > 0) metaLine.append("  \u2022  ");
            metaLine.append(data.getSchoolAddress());
        }
        if (metaLine.length() > 0) {
            Font metaFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 7.5f, TEXT_MID);
            Paragraph meta = new Paragraph(metaLine.toString(), metaFont);
            meta.setAlignment(Element.ALIGN_CENTER);
            meta.setSpacingAfter(6);
            doc.add(meta);
        }

        // Layout-specific title band
        addDocTitleBand(doc, data, branding);
    }

    /**
     * Loads the school logo for embedding in the PDF.
     *
     * Two cases:
     *  1. Full HTTP/HTTPS URL  — fetched over the network (e.g. external CDN).
     *  2. Relative path        — e.g. "/uploads/school-logos/1.png" as stored by SchoolService.
     *                            Resolved against the logoDirectory on the local filesystem;
     *                            this is the same directory SchoolService writes to, so the
     *                            file is always present if the path is non-null.
     *
     * Returns null silently on any error so the header degrades gracefully (2-column layout).
     */
    private Image loadLogoImage(String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank()) return null;
        try {
            if (logoUrl.startsWith("http://") || logoUrl.startsWith("https://")) {
                // ── Remote URL ────────────────────────────────────────────────
                java.net.URL url = new java.net.URL(logoUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    byte[] bytes = conn.getInputStream().readAllBytes();
                    return Image.getInstance(bytes);
                }
                log.debug("School logo HTTP {} for URL: {}", conn.getResponseCode(), logoUrl);
            } else {
                // ── Relative path stored by SchoolService ─────────────────────
                // logoUrl is like "/uploads/school-logos/1.png"
                // Extract just the filename and resolve it against logoDirectory.
                String filename = logoUrl.substring(logoUrl.lastIndexOf('/') + 1);
                java.nio.file.Path filePath = java.nio.file.Paths.get(logoDirectory)
                        .toAbsolutePath()
                        .resolve(filename)
                        .normalize();
                if (java.nio.file.Files.exists(filePath)) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(filePath);
                    return Image.getInstance(bytes);
                }
                log.debug("School logo file not found at: {}", filePath);
            }
        } catch (Exception e) {
            log.debug("School logo could not be loaded ({}): {}", logoUrl, e.getMessage());
        }
        return null;
    }

    /**
     * Returns the raw bytes of the school logo for use in the logo watermark.
     * Same URL-resolution logic as loadLogoImage() but returns bytes so they can
     * be passed into the static WatermarkEvent class.
     */
    private byte[] loadLogoBytes(String logoUrl) {
        if (logoUrl == null || logoUrl.isBlank()) return null;
        try {
            if (logoUrl.startsWith("http://") || logoUrl.startsWith("https://")) {
                java.net.URL url = new java.net.URL(logoUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    return conn.getInputStream().readAllBytes();
                }
            } else {
                String filename = logoUrl.substring(logoUrl.lastIndexOf('/') + 1);
                java.nio.file.Path filePath = java.nio.file.Paths.get(logoDirectory)
                        .toAbsolutePath()
                        .resolve(filename)
                        .normalize();
                if (java.nio.file.Files.exists(filePath)) {
                    return java.nio.file.Files.readAllBytes(filePath);
                }
            }
        } catch (Exception e) {
            log.debug("Logo bytes could not be loaded for watermark ({}): {}", logoUrl, e.getMessage());
        }
        return null;
    }

    /**
     * Loads the custom report card header image.
     * Same URL-resolution logic as loadLogoImage — handles HTTP URLs and relative (/uploads/...) paths.
     * Returns null silently on any error so the caller can fall back to the auto-generated header.
     */
    private Image loadHeaderImage(String headerUrl) {
        if (headerUrl == null || headerUrl.isBlank()) return null;
        try {
            if (headerUrl.startsWith("http://") || headerUrl.startsWith("https://")) {
                java.net.URL url = new java.net.URL(headerUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    byte[] bytes = conn.getInputStream().readAllBytes();
                    return Image.getInstance(bytes);
                }
                log.debug("Report card header HTTP {} for URL: {}", conn.getResponseCode(), headerUrl);
            } else {
                String filename = headerUrl.substring(headerUrl.lastIndexOf('/') + 1);
                java.nio.file.Path filePath = java.nio.file.Paths.get(headerDirectory)
                        .toAbsolutePath()
                        .resolve(filename)
                        .normalize();
                if (java.nio.file.Files.exists(filePath)) {
                    byte[] bytes = java.nio.file.Files.readAllBytes(filePath);
                    return Image.getInstance(bytes);
                }
                log.debug("Report card header file not found at: {}", filePath);
            }
        } catch (Exception e) {
            log.debug("Report card header image could not be loaded ({}): {}", headerUrl, e.getMessage());
        }
        return null;
    }

    // ── STUDENT_INFO ──────────────────────────────────────────────────────
    // Label/value pair table with passport-size photo on the right.

    private void addStudentInfo(Document doc, ReportCardDataDTO data,
                                 BrandingConfig branding) throws DocumentException {
        doc.add(sectionTitleBar("STUDENT INFORMATION", branding.primary));

        // Load student photo (may be null → placeholder rectangle)
        Image studentPhoto = loadStudentPhotoImage(data.getPhotoUrl());

        // 5-column table: label | value | label | value | photo (rowspan 4)
        PdfPTable table = new PdfPTable(new float[]{1.2f, 2f, 1.2f, 2f, 1f});
        table.setWidthPercentage(100);
        table.setSpacingAfter(8);

        // Row 1 — Name + Student ID + photo cell (rowspan 4)
        table.addCell(siLabelCell("Name"));
        table.addCell(siValueCell(safe(data.getStudentName(), "—")));
        table.addCell(siLabelCell("Student ID"));
        table.addCell(siValueCell(safe(data.getStudentId(), "—")));

        // Photo cell spanning all 4 rows
        PdfPCell photoCell = new PdfPCell();
        photoCell.setRowspan(4);
        photoCell.setBorderColor(BORDER_GRAY);
        photoCell.setPadding(4);
        photoCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        photoCell.setVerticalAlignment(Element.ALIGN_TOP);
        if (studentPhoto != null) {
            studentPhoto.scaleToFit(56, 72);
            photoCell.setImage(studentPhoto);
        } else {
            // Passport-size dashed placeholder rectangle
            photoCell.setFixedHeight(90f);
            photoCell.setBackgroundColor(new Color(250, 250, 250));
        }
        table.addCell(photoCell);

        // Rows 2–4
        String classDisplay = (data.getSectionName() != null && !data.getSectionName().isBlank())
                ? safe(data.getClassName(), "—") + " – " + data.getSectionName()
                : safe(data.getClassName(), "—");
        table.addCell(siLabelCell("Class & Section"));
        table.addCell(siValueCell(classDisplay));
        table.addCell(siLabelCell("Session"));
        table.addCell(siValueCell(safe(data.getSession(), "—")));

        table.addCell(siLabelCell("Admission No."));
        table.addCell(siValueCell(safe(data.getStudentId(), "—")));
        table.addCell(siLabelCell("Date of Birth"));
        table.addCell(siValueCell(safe(data.getDateOfBirth(), "—")));

        table.addCell(siLabelCell("Father's Name"));
        table.addCell(siValueCell(safe(data.getFatherName(), "—")));
        table.addCell(siLabelCell("Mother's Name"));
        table.addCell(siValueCell(safe(data.getMotherName(), "—")));

        doc.add(table);
    }

    /**
     * Loads the student photo from disk — same logic as loadLogoImage() but using
     * studentPhotoDirectory. Paths are stored as /uploads/student-photos/abc.jpg.
     * Returns null silently so the caller can render a placeholder instead.
     */
    private Image loadStudentPhotoImage(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) return null;
        try {
            if (photoUrl.startsWith("http://") || photoUrl.startsWith("https://")) {
                java.net.URL url = new java.net.URL(photoUrl);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(3000);
                conn.setReadTimeout(5000);
                conn.connect();
                if (conn.getResponseCode() == 200) {
                    return Image.getInstance(conn.getInputStream().readAllBytes());
                }
            } else {
                String filename = photoUrl.substring(photoUrl.lastIndexOf('/') + 1);
                java.nio.file.Path filePath = java.nio.file.Paths.get(studentPhotoDirectory)
                        .toAbsolutePath().resolve(filename).normalize();
                if (java.nio.file.Files.exists(filePath)) {
                    return Image.getInstance(java.nio.file.Files.readAllBytes(filePath));
                }
                log.debug("Student photo file not found at: {}", filePath);
            }
        } catch (Exception e) {
            log.debug("Student photo could not be loaded ({}): {}", photoUrl, e.getMessage());
        }
        return null;
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

        // Data rows — failed subject rows get italic dark-red text
        for (WeightedGroupResultDTO.MarksTableDTO.SubjectRowDTO row : mt.getSubjectRows()) {
            boolean failed = row.getWeightedPercentage() < 33.0;
            Font subjectFont = failed
                    ? FontFactory.getFont(FontFactory.TIMES_ITALIC, 9, FAIL_COLOR)
                    : F_BODY;

            PdfPCell subCell = new PdfPCell(new Phrase(row.getSubjectName(), subjectFont));
            subCell.setBorderColor(BORDER_GRAY);
            subCell.setPadding(5);
            table.addCell(subCell);

            List<WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO> marks = row.getExamMarks();
            for (int i = 0; i < examCount; i++) {
                WeightedGroupResultDTO.MarksTableDTO.SubjectExamMarkDTO mark =
                        (marks != null && i < marks.size()) ? marks.get(i) : null;
                if (mark == null || mark.getObtained() == null) {
                    table.addCell(tdCellCenter("Ab", true));
                } else {
                    String txt = DF0.format(mark.getObtained()) + "/" + DF0.format(mark.getMax());
                    Font markFont = failed
                            ? FontFactory.getFont(FontFactory.TIMES_ITALIC, 9, FAIL_COLOR)
                            : F_BODY;
                    PdfPCell mc = new PdfPCell(new Phrase(txt, markFont));
                    mc.setHorizontalAlignment(Element.ALIGN_CENTER);
                    mc.setBorderColor(BORDER_GRAY);
                    mc.setPadding(5);
                    table.addCell(mc);
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

        // Grade scale legend — shown for CBSE and LETTER grading systems
        addGradeLegend(doc, data.getGradingSystem(), branding);
    }

    /** Renders a compact one-line grade scale legend below the marks table. */
    private void addGradeLegend(Document doc, String gradingSystem,
                                 BrandingConfig branding) throws DocumentException {
        String[][] rows;
        if ("CBSE".equals(gradingSystem)) {
            rows = new String[][]{
                {"A1","91-100","Outstanding"}, {"A2","81-90","Excellent"},
                {"B1","71-80","Very Good"},    {"B2","61-70","Good"},
                {"C1","51-60","Satisfactory"}, {"C2","41-50","Average"},
                {"D", "33-40","Needs Impr."},  {"E", "0-32", "Fail"}
            };
        } else if ("LETTER".equals(gradingSystem)) {
            rows = new String[][]{
                {"A+","90-100","Outstanding"}, {"A","80-89","Excellent"},
                {"B+","70-79","Very Good"},    {"B","60-69","Good"},
                {"C+","50-59","Satisfactory"}, {"C","40-49","Average"},
                {"D", "33-39","Needs Impr."},  {"F","0-32", "Fail"}
            };
        } else {
            return; // PERCENTAGE — no letter grade legend needed
        }

        Font legendLabelFont = FontFactory.getFont(FontFactory.TIMES_BOLD,   6.5f, TEXT_MID);
        Font legendBodyFont  = FontFactory.getFont(FontFactory.TIMES_ROMAN,  6.5f, TEXT_MID);

        // Single-row table: "Grading Scale:" | A1 (91-100) Outstanding | A2 ... | ...
        float[] widths = new float[rows.length + 1];
        widths[0] = 1.4f;
        for (int i = 1; i <= rows.length; i++) widths[i] = 1f;

        PdfPTable legend = new PdfPTable(widths);
        legend.setWidthPercentage(100);
        legend.setSpacingAfter(4);

        PdfPCell title = new PdfPCell(new Phrase("Grading Scale:", legendLabelFont));
        title.setBorder(Rectangle.NO_BORDER);
        title.setPaddingLeft(2);
        title.setVerticalAlignment(Element.ALIGN_MIDDLE);
        legend.addCell(title);

        for (String[] r : rows) {
            Phrase p = new Phrase();
            p.add(new Chunk(r[0] + " ", legendLabelFont));
            p.add(new Chunk("(" + r[1] + ") ", legendBodyFont));
            p.add(new Chunk(r[2], legendBodyFont));
            PdfPCell cell = new PdfPCell(p);
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPadding(2);
            cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
            legend.addCell(cell);
        }

        doc.add(legend);
    }

    // ── ASSESSMENT_SUMMARY ────────────────────────────────────────────────
    // Compact bordered summary table — no colored KPI blocks.
    // New layouts (WARM_ELEGANCE / NAVY_SCHOLAR): large-number KPI row.

    private void addAssessmentSummary(Document doc, ReportCardDataDTO data,
                                       BrandingConfig branding) throws DocumentException {
        WeightedGroupResultDTO wr = data.getWeightedResult();
        if (wr == null) return;

        doc.add(sectionTitleBar("ASSESSMENT SUMMARY", branding.primary));

        boolean showCgpa = branding.showCgpa && data.getCgpa() != null;
        String pctStr = DF1.format(wr.getWeightedPercentage()) + "%";
        String grade  = gradeFromPct(wr.getWeightedPercentage(), data.getGradingSystem());
        String rankStr = wr.getRank() > 0 ? String.valueOf(wr.getRank()) : "—";

        if (isNewLayout(branding)) {
            // ── Large-number KPI row ──────────────────────────────────────
            // Columns: Percentage | Grade | Rank [| CGPA]
            int cols = showCgpa ? 4 : 3;
            PdfPTable kpiTable = new PdfPTable(cols);
            kpiTable.setWidthPercentage(100);
            kpiTable.setSpacingAfter(6);

            Font bigFont   = FontFactory.getFont(FontFactory.TIMES_BOLD, 20, branding.primary);
            Font labelFont = FontFactory.getFont(FontFactory.TIMES_ITALIC, 8, new Color(100, 100, 100));

            // helper: one KPI cell (big number above, small label below, right-side divider except last)
            String[] labels = showCgpa
                    ? new String[]{"Overall Percentage", "Grade", "Class Rank", "CGPA"}
                    : new String[]{"Overall Percentage", "Grade", "Class Rank"};
            String[] values = showCgpa
                    ? new String[]{pctStr, grade, rankStr, DF1.format(data.getCgpa())}
                    : new String[]{pctStr, grade, rankStr};

            for (int i = 0; i < cols; i++) {
                PdfPCell kpi = new PdfPCell();
                kpi.setBorder(Rectangle.BOX);
                kpi.setBorderColor(BORDER_GRAY);
                kpi.setPadding(10);
                kpi.setHorizontalAlignment(Element.ALIGN_CENTER);

                Paragraph bigVal = new Paragraph(values[i], bigFont);
                bigVal.setAlignment(Element.ALIGN_CENTER);
                bigVal.setSpacingAfter(3);
                kpi.addElement(bigVal);

                Paragraph lbl = new Paragraph(labels[i].toUpperCase(), labelFont);
                lbl.setAlignment(Element.ALIGN_CENTER);
                kpi.addElement(lbl);

                kpiTable.addCell(kpi);
            }
            doc.add(kpiTable);

        } else {
            // ── Classic compact table ─────────────────────────────────────
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
            PdfPCell pctCell = new PdfPCell(
                new Phrase(pctStr, FontFactory.getFont(FontFactory.TIMES_BOLD, 12, branding.primary)));
            pctCell.setBorderColor(BORDER_GRAY);
            pctCell.setPadding(8);
            summaryTable.addCell(pctCell);

            PdfPCell gradeCell = new PdfPCell(new Phrase(grade, gradeFont(wr.getWeightedPercentage())));
            gradeCell.setBorderColor(BORDER_GRAY);
            gradeCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            gradeCell.setPadding(8);
            summaryTable.addCell(gradeCell);

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
    }

    // ── PROMOTION_STATUS ──────────────────────────────────────────────────
    // Centered stamp-style badge — mirrors Angular's rc-result-stamp.
    // New layouts: circular/oval badge with "PASSED WITH [GRADE] DISTINCTION".

    private void addPromotionStatus(Document doc, ReportCardDataDTO data,
                                     BrandingConfig branding) throws DocumentException {
        WeightedGroupResultDTO wr = data.getWeightedResult();
        if (wr == null) return;

        doc.add(sectionTitleBar("RESULT", branding.primary));

        boolean pass = wr.getWeightedPercentage() >= 33.0;
        Color resultColor = pass ? GOLD : FAIL_COLOR;
        String grade = gradeFromPct(wr.getWeightedPercentage(), data.getGradingSystem());
        String pctStr = DF1.format(wr.getWeightedPercentage()) + "%";

        if (isNewLayout(branding)) {
            // ── Circular-style badge (oval border, centered, multi-line) ──
            // OpenPDF has no border-radius, so we use a tight bordered cell
            // with generous padding to approximate a rounded badge feel.
            PdfPTable outer = new PdfPTable(new float[]{1.5f, 2f, 1.5f});
            outer.setWidthPercentage(100);
            outer.setSpacingAfter(10);
            outer.addCell(emptyCell());

            PdfPCell badge = new PdfPCell();
            badge.setBorder(Rectangle.BOX);
            badge.setBorderColor(resultColor);
            badge.setBorderWidth(2f);
            badge.setPaddingTop(16);
            badge.setPaddingBottom(16);
            badge.setPaddingLeft(12);
            badge.setPaddingRight(12);
            badge.setHorizontalAlignment(Element.ALIGN_CENTER);
            badge.setBackgroundColor(WHITE);

            Font tinyFont    = FontFactory.getFont(FontFactory.TIMES_BOLD, 8, resultColor);
            Font gradeFont2  = FontFactory.getFont(FontFactory.TIMES_BOLD, 22, resultColor);
            Font distFont    = FontFactory.getFont(FontFactory.TIMES_BOLD, 7, resultColor);

            if (pass) {
                Paragraph passedWith = new Paragraph("PASSED WITH", tinyFont);
                passedWith.setAlignment(Element.ALIGN_CENTER);
                passedWith.setSpacingAfter(1);
                badge.addElement(passedWith);

                Paragraph gradePara = new Paragraph(grade, gradeFont2);
                gradePara.setAlignment(Element.ALIGN_CENTER);
                gradePara.setSpacingAfter(1);
                badge.addElement(gradePara);

                Paragraph distinction = new Paragraph("DISTINCTION", distFont);
                distinction.setAlignment(Element.ALIGN_CENTER);
                distinction.setSpacingAfter(5);
                badge.addElement(distinction);
            } else {
                Font failBig = FontFactory.getFont(FontFactory.TIMES_BOLD, 20, FAIL_COLOR);
                Paragraph failPara = new Paragraph("FAIL", failBig);
                failPara.setAlignment(Element.ALIGN_CENTER);
                failPara.setSpacingAfter(5);
                badge.addElement(failPara);
            }

            Font pctSmall = FontFactory.getFont(FontFactory.TIMES_ITALIC, 9, new Color(80, 80, 80));
            Paragraph pctPara = new Paragraph(pctStr, pctSmall);
            pctPara.setAlignment(Element.ALIGN_CENTER);
            badge.addElement(pctPara);

            outer.addCell(badge);
            outer.addCell(emptyCell());
            doc.add(outer);

        } else {
            // ── Classic rectangular stamp ─────────────────────────────────
            PdfPTable outer = new PdfPTable(new float[]{1.2f, 1.6f, 1.2f});
            outer.setWidthPercentage(100);
            outer.setSpacingAfter(8);
            outer.addCell(emptyCell());

            PdfPCell stampCell = new PdfPCell();
            stampCell.setBorder(Rectangle.BOX);
            stampCell.setBorderColor(resultColor);
            stampCell.setBorderWidth(1.5f);
            stampCell.setPadding(10);
            stampCell.setHorizontalAlignment(Element.ALIGN_CENTER);
            stampCell.setBackgroundColor(WHITE);

            Font verdictFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 20, resultColor);
            Paragraph verdictPara = new Paragraph(pass ? "PASS" : "FAIL", verdictFont);
            verdictPara.setAlignment(Element.ALIGN_CENTER);
            verdictPara.setSpacingAfter(3);
            stampCell.addElement(verdictPara);

            String metaText = pctStr + "  \u2022  " + grade;
            Font metaFont = FontFactory.getFont(FontFactory.TIMES_BOLD, 9, resultColor);
            Paragraph metaPara = new Paragraph(metaText, metaFont);
            metaPara.setAlignment(Element.ALIGN_CENTER);
            stampCell.addElement(metaPara);

            outer.addCell(stampCell);
            outer.addCell(emptyCell());
            doc.add(outer);
        }
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
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);

        for (String label : new String[]{"Class Teacher", "Principal"}) {
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

        // Footer — small, gold, centered
        Font footerFont = FontFactory.getFont(FontFactory.TIMES_ROMAN, 7, GOLD);
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
            Math.min(255, (int)(color.getRed()   * 0.08 + 247)),
            Math.min(255, (int)(color.getGreen() * 0.08 + 247)),
            Math.min(255, (int)(color.getBlue()  * 0.08 + 247))
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
