package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.repository.PaymentRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ModelMapper modelMapper; // Retained, though not used in DTO mapping below

    public PaymentResponseDTO getPaymentHistoryDetails(String paymentId) {
        if (paymentId == null || paymentId.trim().isEmpty()) {
            log.warn("Attempted to get payment details with null/empty ID.");
            return null;
        }
        log.info("Fetching payment history details for payment ID: {}", paymentId);

        try {
            Payment payment = paymentRepository.findByPaymentId(paymentId);
            if (payment == null) {
                log.warn("Payment not found with ID: {}", paymentId);
                return null;
            }

            // Using DTO constructor as in original code
            return new PaymentResponseDTO(
                    payment.getStudentId(),
                    payment.getStudentName(),
                    payment.getClassName(),
                    payment.getSession(),
                    payment.getMonth(),
                    payment.getAmount(),
                    payment.getPaymentId(),
                    payment.getOrderId(),
                    payment.getPaymentDate(),
                    payment.getStatus(),
                    payment.getBusFee(),
                    payment.getTuitionFee(),
                    payment.getAnnualCharges(),
                    payment.getLabCharges(),
                    payment.getEcaProject(),
                    payment.getExaminationFee(),
                    payment.getAmountPaid(),
                    payment.getAdditionalCharges(),
                    payment.getLateFees()
            );
        } catch (DataAccessException e) {
            log.error("Data access error fetching payment details for ID: {}", paymentId, e);
            throw new RuntimeException("Could not retrieve payment details due to data access issue", e);
        }
    }

    public Page<Payment> gePaymentHistoryFiltered(String className, String studentId, LocalDate paymentDate, Pageable pageable) {
        log.info("Filtering payment history. Class: {}, Student ID: {}, Date: {}", className, studentId, paymentDate);

        try {
            if (className != null && studentId != null && paymentDate != null) {
                return paymentRepository.findByClassNameAndStudentIdContainingAndPaymentDate(className, studentId, paymentDate, pageable);
            } else if (className != null && studentId != null) {
                return paymentRepository.findByClassNameAndStudentIdContaining(className, studentId, pageable);
            } else if (className != null && paymentDate != null) {
                return paymentRepository.findByClassNameAndPaymentDate(className, paymentDate, pageable);
            } else if (studentId != null && paymentDate != null) {
                return paymentRepository.findByStudentIdContainingAndPaymentDate(studentId, paymentDate, pageable);
            } else if (className != null) {
                return paymentRepository.findByClassName(className, pageable);
            } else if (studentId != null) {
                return paymentRepository.findByStudentIdContaining(studentId, pageable);
            } else if (paymentDate != null) {
                return paymentRepository.findByPaymentDate(paymentDate, pageable);
            } else {
                return paymentRepository.findAll(pageable);
            }
        } catch (DataAccessException e) {
            log.error("Data access error during payment history filtering. Class: {}, Student ID: {}, Date: {}", className, studentId, paymentDate, e);
            throw new RuntimeException("Could not retrieve filtered payment history due to data access issue", e);
        }
    }

    public Page<Payment> getPaymentHistoryByStudentId(String studentId, Pageable pageable){
        if (studentId == null || studentId.trim().isEmpty()) {
            log.warn("Attempted to get payment history with null/empty student ID.");
            return Page.empty(pageable);
        }
        log.info("Fetching payment history for student ID: {}", studentId);

        try {
            return paymentRepository.findByStudentId(studentId, pageable);
        } catch (DataAccessException e) {
            log.error("Data access error fetching payment history for student ID: {}", studentId, e);
            throw new RuntimeException("Could not retrieve payment history by student ID due to data access issue", e);
        }
    }

    public byte[] generatePaymentReceiptPdf(String paymentId) {
        if (paymentId == null || paymentId.trim().isEmpty()) {
            log.error("Cannot generate PDF: Payment ID is null or empty.");
            return null;
        }
        log.info("Starting PDF generation for payment ID: {}", paymentId);

        Payment payment;
        try {
            payment = paymentRepository.findByPaymentId(paymentId);
        } catch (DataAccessException e) {
            log.error("Data access error fetching payment for PDF generation ID: {}", paymentId, e);
            throw new RuntimeException("Could not retrieve payment data for PDF due to data access issue", e);
        }

        if (payment == null) {
            log.warn("Payment not found for PDF generation ID: {}", paymentId);
            return null;
        }

        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            PDPage page = new PDPage();
            document.addPage(page);

            PDPageContentStream content = new PDPageContentStream(document, page);

            PDType1Font boldFont = PDType1Font.HELVETICA_BOLD;
            PDType1Font regularFont = PDType1Font.HELVETICA;

            float margin = 50;
            float y = 750;
            float width = page.getMediaBox().getWidth() - 2 * margin;
            float tableX = margin;
            float rowHeight = 20;
            float sectionSpacing = 15;

            // 1. Load Logo
            try (var logoStream = new ClassPathResource("images/logo.png").getInputStream()) {
                PDImageXObject logo = PDImageXObject.createFromByteArray(
                        document,
                        logoStream.readAllBytes(),
                        "logo"
                );
                float imageWidth = 100;
                float imageHeight = 100;
                float centerX = (page.getMediaBox().getWidth() - imageWidth) / 2;
                content.drawImage(logo, centerX, y - imageHeight, imageWidth, imageHeight);
                y -= (imageHeight + 35);
            } catch (IOException e) {
                log.error("Failed to load or draw logo image.", e);
            }

            // 2. School Name Header
            content.setNonStrokingColor(60, 90, 40);
            content.addRect(margin, y, width, 25);
            content.fill();

            String schoolName = "Indra Academy Sr. Sec. School";
            float fontSize = 18;
            float textWidth = boldFont.getStringWidth(schoolName) / 1000 * fontSize;
            float pageWidth = page.getMediaBox().getWidth();
            float center = (pageWidth - textWidth) / 2;

            content.beginText();
            content.setFont(boldFont, fontSize);
            content.setNonStrokingColor(255,255,255);
            content.newLineAtOffset(center, y + 7);
            content.showText(schoolName);
            content.endText();

            // 3. Fee Receipt Title
            String feeReceiptText = "Fee Receipt";
            float feeFontSize = 17;
            float feeTextWidth = boldFont.getStringWidth(feeReceiptText) / 1000 * feeFontSize;
            pageWidth = page.getMediaBox().getWidth();
            float feeTextX = (pageWidth - feeTextWidth) / 2;
            float feeTextY = y + 7 - 40;

            content.setNonStrokingColor(0, 0, 0);
            content.beginText();
            content.setFont(boldFont, feeFontSize);
            content.newLineAtOffset(feeTextX, feeTextY);
            content.showText(feeReceiptText);
            content.endText();

            float lineY = feeTextY - 3;
            content.setStrokingColor(0, 0, 0);
            content.setLineWidth(1f);
            content.moveTo(feeTextX, lineY);
            content.lineTo(feeTextX + feeTextWidth, lineY);
            content.stroke();

            y -= (rowHeight + sectionSpacing + 30);

            // 4. Draw Sections
            DateTimeFormatter paymentDateFormat = DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a");

            y = drawSection(content, "Student Info", boldFont, regularFont, y, width, tableX, rowHeight,
                    new String[][]{
                            {"Student ID", Objects.toString(payment.getStudentId(), "")},
                            {"Name", Objects.toString(payment.getStudentName(), "")},
                            {"Class", Objects.toString(payment.getClassName(), "")},
                            {"Session", Objects.toString(payment.getSession(), "")}
                    });

            y -= sectionSpacing;
            y = drawSection(content, "Payment Info", boldFont, regularFont, y, width, tableX, rowHeight,
                    new String[][]{
                            {"Payment ID", Objects.toString(payment.getPaymentId(), "")},
                            {"Order ID", Objects.toString(payment.getOrderId(), "")},
                            {"Date", payment.getPaymentDate() != null ? payment.getPaymentDate().format(paymentDateFormat) : "N/A"},
                            {"Status", Objects.toString(payment.getStatus(), "N/A").toUpperCase()}
                    });

            y -= sectionSpacing;
            y = drawSection(content, "Fee Breakdown", boldFont, regularFont, y, width, tableX, rowHeight,
                    new String[][]{
                            {"Tuition Fee", "Rs. " + payment.getTuitionFee()},
                            {"Bus Fee", "Rs. " + payment.getBusFee()},
                            {"Annual Charges", "Rs. " + payment.getAnnualCharges()},
                            {"Lab Charges", "Rs. " + payment.getLabCharges()},
                            {"ECA Project", "Rs. " + payment.getEcaProject()},
                            {"Examination Fee", "Rs. " + payment.getExaminationFee()},
                            payment.getAdditionalCharges() > 0 ? new String[]{"Leave Charges", "Rs. " + payment.getAdditionalCharges()} : null,
                            payment.getLateFees() > 0 ? new String[]{"Late Fees", "Rs. " + payment.getLateFees()} : null
                    });

            y -= sectionSpacing;
            y = drawSection(content, "Total Summary", boldFont, regularFont, y, width, tableX, rowHeight,
                    new String[][]{
                            {"Total Amount", "Rs. " + payment.getAmount()},
                            {"Paid", "Rs. " + payment.getAmountPaid()}
                    });

            content.close();

            document.save(baos);
            log.info("PDF generated successfully for payment ID: {}", paymentId);
            return baos.toByteArray();

        } catch (IOException e) {
            log.error("IOException occurred during PDF generation for payment ID: {}", paymentId, e);
            throw new RuntimeException("Failed to generate PDF receipt due to I/O error.", e);
        } catch (Exception e) {
            log.error("Unexpected error occurred during PDF generation for payment ID: {}", paymentId, e);
            throw new RuntimeException("An unexpected error occurred during PDF generation.", e);
        }
    }

    // Helper method implementation is retained from original code to avoid introducing errors
    private float drawSection(PDPageContentStream content, String title,
                              PDType1Font headerFont, PDType1Font textFont,
                              float y, float width, float x, float rowHeight, String[][] rows) throws IOException {

        int rowsCount = 0;
        for (String[] row : rows) {
            if (row != null) rowsCount++;
        }

        float sectionHeight = rowHeight * (rowsCount + 1);

        // Draw background for header
        content.setNonStrokingColor(210, 210, 210);
        content.addRect(x, y, width, rowHeight);
        content.fill();

        // Draw border rectangle around the entire section
        content.setStrokingColor(0, 0, 0);
        content.setLineWidth(0.5f);
        content.addRect(x, y - sectionHeight + rowHeight, width, sectionHeight);
        content.stroke();

        // Draw header text
        content.beginText();
        content.setNonStrokingColor(0, 0, 0);
        content.setFont(headerFont, 13);
        content.newLineAtOffset(x + 5, y + 5);
        content.showText(title);
        content.endText();

        float currentY = y - rowHeight;
        int color = 0;

        for (String[] row : rows) {
            if (row == null) continue;

            // Draw row background color (alternating)
            if (color % 2 == 0) {
                content.setNonStrokingColor(255, 255, 255);
            } else {
                content.setNonStrokingColor(240, 240, 240);
            }
            content.addRect(x, currentY, width, rowHeight);
            content.fill();

            // Draw horizontal line at top of row
            content.setStrokingColor(0, 0, 0);
            content.setLineWidth(0.3f);
            content.moveTo(x, currentY + rowHeight);
            content.lineTo(x + width, currentY + rowHeight);
            content.stroke();

            // Draw vertical line between columns
            float colDividerX = x + width * 0.4f;
            content.moveTo(colDividerX, currentY);
            content.lineTo(colDividerX, currentY + rowHeight);
            content.stroke();

            // Draw label text
            content.setNonStrokingColor(0, 0, 0);
            content.beginText();
            if ("Total Amount".equals(row[0]) || "Paid".equals(row[0])) {
                content.setFont(headerFont, 11);
            } else {
                content.setFont(textFont, 11);
            }
            content.newLineAtOffset(x + 5, currentY + 5);
            content.showText(row[0]);
            content.endText();

            // Draw value text
            content.beginText();
            if ("Total Amount".equals(row[0]) || "Paid".equals(row[0])) {
                content.setFont(headerFont, 11);
            } else {
                content.setFont(textFont, 11);
            }
            content.newLineAtOffset(colDividerX + 5, currentY + 5);
            content.showText(row[1]);
            content.endText();

            currentY -= rowHeight;
            color++;
        }

        // Draw bottom horizontal line for last row
        content.setStrokingColor(0, 0, 0);
        content.setLineWidth(0.5f);
        content.moveTo(x, currentY + rowHeight);
        content.lineTo(x + width, currentY + rowHeight);
        content.stroke();

        return currentY;
    }
}