package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PaymentHistoryDTO;
import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.repository.PaymentRepository;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PaymentService {

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private ModelMapper modelMapper;

    public List<PaymentHistoryDTO> getPaymentHistory(String studentId) {
        List<Payment> payments = paymentRepository.findByStudentIdOrderByPaymentDateDesc(studentId);
        return payments.stream()
                .map(payment -> modelMapper.map(payment, PaymentHistoryDTO.class))
                .collect(Collectors.toList());
    }

    public PaymentResponseDTO getPaymentHistoryDetails(String paymentId) {
        Payment payment = paymentRepository.findByPaymentId(paymentId);
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
    }

    public List<PaymentHistoryDTO> getAllPaymentHistory() {
        List<Payment> allPayments = paymentRepository.findAllByOrderByPaymentDateDesc();
        return allPayments.stream()
                .map(payment -> modelMapper.map(payment, PaymentHistoryDTO.class))
                .collect(Collectors.toList());
    }

    public List<PaymentHistoryDTO> getPaymentHistoryByClass(String className) {
        List<Payment> classPayments = paymentRepository.findByClassNameOrderByPaymentDateDesc(className);
        return classPayments.stream()
                .map(payment -> modelMapper.map(payment, PaymentHistoryDTO.class))
                .collect(Collectors.toList());
    }


    public byte[] generatePaymentReceiptPdf(String paymentId) {
        Payment payment = paymentRepository.findByPaymentId(paymentId);
        if (payment == null) return null;

        try (PDDocument document = new PDDocument()) {
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

            PDImageXObject logo = PDImageXObject.createFromByteArray(
                    document,
                    new ClassPathResource("images/logo.png").getInputStream().readAllBytes(),
                    "logo"
            );

            float imageWidth = 100;
            float imageHeight = 100;
            float centerX = (page.getMediaBox().getWidth() - imageWidth) / 2;
            content.drawImage(logo, centerX, y - imageHeight, imageWidth, imageHeight);
            y -= (imageHeight + 35);

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

            y = drawSection(content, "Student Info", boldFont, regularFont, y, width, tableX, rowHeight,
                    new String[][]{
                            {"Student ID", payment.getStudentId()},
                            {"Name", payment.getStudentName()},
                            {"Class", payment.getClassName()},
                            {"Session", payment.getSession()}
                    });

            y -= sectionSpacing;
            y = drawSection(content, "Payment Info", boldFont, regularFont, y, width, tableX, rowHeight,
                    new String[][]{
                            {"Payment ID", payment.getPaymentId()},
                            {"Order ID", payment.getOrderId()},
                            {"Date", payment.getPaymentDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a"))},
                            {"Status", payment.getStatus().toUpperCase()}
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

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            document.save(baos);
            return baos.toByteArray();

        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    private float drawSection(PDPageContentStream content, String title,
                              PDType1Font headerFont, PDType1Font textFont,
                              float y, float width, float x, float rowHeight, String[][] rows) throws IOException {

        int rowsCount = 0;
        for (String[] row : rows) {
            if (row != null) rowsCount++;
        }

        float sectionHeight = rowHeight * (rowsCount + 1); // +1 for header

        // Draw background for header
        content.setNonStrokingColor(210, 210, 210);
        content.addRect(x, y, width, rowHeight);
        content.fill();

        // Draw border rectangle around the entire section
        content.setStrokingColor(0, 0, 0); // black border
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
                content.setNonStrokingColor(255, 255, 255); // white
            } else {
                content.setNonStrokingColor(240, 240, 240); // light grey
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
                content.setFont(headerFont, 11);  // Bold font
            } else {
                content.setFont(textFont, 11);    // Regular font
            }
            content.newLineAtOffset(x + 5, currentY + 5);
            content.showText(row[0]);
            content.endText();

            // Draw value text
            content.beginText();
            if ("Total Amount".equals(row[0]) || "Paid".equals(row[0])) {
                content.setFont(headerFont, 11);  // Bold font
            } else {
                content.setFont(textFont, 11);    // Regular font
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
