package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.PaymentResponseDTO;
import com.indraacademy.ias_management.entity.Payment;
import com.indraacademy.ias_management.repository.PaymentRepository;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    @Autowired private PaymentRepository paymentRepository;
    @Autowired private ModelMapper modelMapper; // Retained, though not used in DTO mapping below

    @Transactional(readOnly = true)
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
                    payment.getLateFees(),
                    payment.getPlatformFee()
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

    @Transactional(readOnly = true)
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

        try {
            String html = buildReceiptHtml(payment);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ITextRenderer renderer = new ITextRenderer();
            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(baos);
            log.info("PDF generated successfully for payment ID: {}", paymentId);
            return baos.toByteArray();
        } catch (Exception e) {
            log.error("Error generating PDF for payment ID: {}", paymentId, e);
            throw new RuntimeException("Failed to generate PDF receipt.", e);
        }
    }

    private String getMonthNamesFromBinary(String monthBinary) {
        if (monthBinary == null || monthBinary.length() != 12) {
            return "N/A";
        }

        String[] monthNames = {
                "April", "May", "June", "July", "August", "September",
                "October", "November", "December", "January", "February", "March"
        };

        List<String> selectedMonths = new ArrayList<>();
        for (int i = 0; i < monthBinary.length(); i++) {
            if (monthBinary.charAt(i) == '1') {
                selectedMonths.add(monthNames[i]);
            }
        }

        return selectedMonths.isEmpty() ? "No Month Selected" : String.join(", ", selectedMonths);
    }

    private String buildReceiptHtml(Payment payment) {
        // Embed logo as base64 data URI so Flying Saucer can render it without filesystem access
        String logoDataUri = "";
        try {
            byte[] logoBytes = new ClassPathResource("images/logo.png").getInputStream().readAllBytes();
            logoDataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(logoBytes);
        } catch (IOException e) {
            log.warn("Logo not found, PDF will be generated without it.");
        }

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");
        String paymentDate = payment.getPaymentDate() != null
                ? payment.getPaymentDate().format(fmt) : "N/A";
        String status = payment.getStatus() != null
                ? payment.getStatus().toUpperCase() : "N/A";
        String paymentMode = payment.isPaidManually() ? "Cash / Manual" : "Online (Razorpay)";
        String generatedOn = LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a"));
        String formattedMonths = getMonthNamesFromBinary(payment.getMonth());

        // Build optional fee rows (only include non-zero charges)
        StringBuilder feeRows = new StringBuilder();
        int rowIdx = 0;
        rowIdx = appendFeeRow(feeRows, "Tuition Fee",      payment.getTuitionFee(),      rowIdx);
        rowIdx = appendFeeRow(feeRows, "Bus Fee",          payment.getBusFee(),           rowIdx);
        rowIdx = appendFeeRow(feeRows, "Annual Charges",   payment.getAnnualCharges(),    rowIdx);
        rowIdx = appendFeeRow(feeRows, "Lab Charges",      payment.getLabCharges(),       rowIdx);
        rowIdx = appendFeeRow(feeRows, "ECA / Project",    payment.getEcaProject(),       rowIdx);
        rowIdx = appendFeeRow(feeRows, "Examination Fee",  payment.getExaminationFee(),   rowIdx);
        rowIdx = appendFeeRow(feeRows, "Leave Charges",    payment.getAdditionalCharges(), rowIdx);
        rowIdx = appendFeeRow(feeRows, "Late Fees",        payment.getLateFees(),         rowIdx);
               appendFeeRow(feeRows, "Platform Fee",      payment.getPlatformFee(),      rowIdx);

        String logoHtml = logoDataUri.isEmpty() ? ""
                : "<img src=\"" + logoDataUri + "\" style=\"width: 75pt; height: 75pt;\" alt=\"logo\"/><br/>";

        String statusClass = "SUCCESS".equals(status) ? "status-success" : "status-fail";

        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
             + "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\""
             + " \"http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd\">\n"
             + "<html xmlns=\"http://www.w3.org/1999/xhtml\">\n"
             + "<head>\n"
             + "  <meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\"/>\n"
             + "  <style type=\"text/css\">\n"
             + "    @page { size: A4; margin: 14mm 16mm 14mm 16mm; }\n"
             + "    body  { font-family: Arial, Helvetica, sans-serif; font-size: 9pt;"
             + "            color: #1A1A1A; margin: 0; padding: 0; }\n"
             /* ── Header ─────────────────────────────────────────── */
             + "    .header       { text-align: center; padding-bottom: 8pt;"
             + "                   border-bottom: 3pt solid #C8960C; }\n"
             + "    .school-name  { font-size: 16pt; font-weight: bold; color: #1B3A6B;"
             + "                   margin: 6pt 0 2pt 0; }\n"
             + "    .school-sub   { font-size: 7.5pt; color: #666666; margin: 0; }\n"
             /* ── Title bar ──────────────────────────────────────── */
             + "    .title-bar    { background-color: #1B3A6B; color: #FFFFFF;"
             + "                   text-align: center; padding: 5pt 0 4pt 0; margin: 9pt 0 9pt 0; }\n"
             + "    .title-main   { font-size: 13pt; font-weight: bold;"
             + "                   letter-spacing: 2pt; margin: 0; }\n"
             + "    .title-sub    { font-size: 7.5pt; margin: 2pt 0 0 0; color: #C8D8F0; }\n"
             /* ── Two-column info cards ───────────────────────────── */
             + "    .info-outer   { width: 100%; border-collapse: collapse; margin-bottom: 9pt; }\n"
             + "    .info-card    { width: 49%; vertical-align: top;"
             + "                   border: 1pt solid #BFC9D9; }\n"
             + "    .card-gap     { width: 2%; }\n"
             + "    .card-header-student { background-color: #1B3A6B; color: #FFFFFF;"
             + "                   font-weight: bold; font-size: 8pt; padding: 3pt 7pt; }\n"
             + "    .card-header-payment { background-color: #0D6B6B; color: #FFFFFF;"
             + "                   font-weight: bold; font-size: 8pt; padding: 3pt 7pt; }\n"
             + "    .card-body    { width: 100%; border-collapse: collapse; }\n"
             + "    .card-row td  { padding: 3pt 7pt; font-size: 8.5pt;"
             + "                   border-bottom: 1pt solid #DDE6F2; }\n"
             + "    .lbl          { color: #666666; width: 44%; }\n"
             + "    .val          { font-weight: bold; color: #1A1A1A; }\n"
             + "    .status-success { color: #1B7C2A; font-weight: bold; }\n"
             + "    .status-fail    { color: #C62828; font-weight: bold; }\n"
             /* ── Fee breakdown table ─────────────────────────────── */
             + "    .fee-table    { width: 100%; border-collapse: collapse;"
             + "                   border: 1pt solid #BFC9D9; }\n"
             + "    .fee-th       { background-color: #1B3A6B; color: #FFFFFF;"
             + "                   font-size: 9pt; font-weight: bold; padding: 4pt 9pt; text-align: left; }\n"
             + "    .fee-th-amt   { background-color: #1B3A6B; color: #FFFFFF;"
             + "                   font-size: 9pt; font-weight: bold; padding: 4pt 9pt; text-align: right; }\n"
             + "    .fee-even td  { background-color: #FFFFFF; padding: 3.5pt 9pt;"
             + "                   font-size: 8.5pt; border-bottom: 1pt solid #DDE6F2; }\n"
             + "    .fee-odd  td  { background-color: #F0F4FA; padding: 3.5pt 9pt;"
             + "                   font-size: 8.5pt; border-bottom: 1pt solid #DDE6F2; }\n"
             + "    .amt-col      { text-align: right; font-weight: bold; }\n"
             /* ── Totals ──────────────────────────────────────────── */
             + "    .total-table  { width: 100%; border-collapse: collapse;"
             + "                   border: 1pt solid #BFC9D9; margin-top: 0; }\n"
             + "    .subtotal-row td { background-color: #E8EDF5; padding: 4.5pt 9pt;"
             + "                      font-size: 9pt; font-weight: bold; color: #1B3A6B;"
             + "                      border-bottom: 1pt solid #BFC9D9; }\n"
             + "    .paid-row td  { background-color: #C8960C; color: #FFFFFF;"
             + "                   padding: 5.5pt 9pt; font-size: 10.5pt; font-weight: bold; }\n"
             + "    .right        { text-align: right; }\n"
             /* ── Signature & footer ──────────────────────────────── */
             + "    .sig-area     { text-align: right; margin-top: 24pt;"
             + "                   font-size: 8pt; color: #555555; }\n"
             + "    .footer       { margin-top: 14pt; border-top: 2pt solid #C8960C;"
             + "                   padding-top: 6pt; text-align: center;"
             + "                   font-size: 7pt; color: #999999; }\n"
             + "  </style>\n"
             + "</head>\n"
             + "<body>\n"
             /* ── Header ─────────────────────────────────────────── */
             + "  <div class=\"header\">\n"
             + "    " + logoHtml + "\n"
             + "    <p class=\"school-name\">Indra Academy Sr. Sec. School</p>\n"
             + "    <p class=\"school-sub\">Vill. Dumkabangar Halduchaur, Haldwani &#8211; 263139"
             + " &#160;|&#160; Affiliated to CBSE</p>\n"
             + "  </div>\n"
             /* ── Title bar ──────────────────────────────────────── */
             + "  <div class=\"title-bar\">\n"
             + "    <p class=\"title-main\">FEE RECEIPT</p>\n"
             + "    <p class=\"title-sub\">Month: " + esc(formattedMonths)
             +                        " &#160;|&#160; Session: " + esc(payment.getSession()) + "</p>\n"
             + "  </div>\n"
             /* ── Two-column info cards ───────────────────────────── */
             + "  <table class=\"info-outer\"><tr>\n"
             + "    <td class=\"info-card\">\n"
             + "      <div class=\"card-header-student\">STUDENT INFORMATION</div>\n"
             + "      <table class=\"card-body\"><tbody>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Student ID</td>"
             +           "<td class=\"val\">" + esc(payment.getStudentId()) + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Name</td>"
             +           "<td class=\"val\">" + esc(payment.getStudentName()) + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Class</td>"
             +           "<td class=\"val\">" + esc(payment.getClassName()) + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Session</td>"
             +           "<td class=\"val\">" + esc(payment.getSession()) + "</td></tr>\n"
             + "      </tbody></table>\n"
             + "    </td>\n"
             + "    <td class=\"card-gap\"></td>\n"
             + "    <td class=\"info-card\">\n"
             + "      <div class=\"card-header-payment\">PAYMENT DETAILS</div>\n"
             + "      <table class=\"card-body\"><tbody>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Receipt No.</td>"
             +           "<td class=\"val\">" + esc(payment.getPaymentId()) + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Date &amp; Time</td>"
             +           "<td class=\"val\">" + esc(paymentDate) + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Mode</td>"
             +           "<td class=\"val\">" + paymentMode + "</td></tr>\n"
             + "        <tr class=\"card-row\"><td class=\"lbl\">Status</td>"
             +           "<td class=\"val\"><span class=\"" + statusClass + "\">" + status + "</span></td></tr>\n"
             + "      </tbody></table>\n"
             + "    </td>\n"
             + "  </tr></table>\n"
             /* ── Fee breakdown ───────────────────────────────────── */
             + "  <table class=\"fee-table\"><thead>\n"
             + "    <tr><th class=\"fee-th\">Fee Description</th>"
             +          "<th class=\"fee-th-amt\">Amount (Rs.)</th></tr>\n"
             + "  </thead><tbody>\n"
             + feeRows
             + "  </tbody></table>\n"
             /* ── Totals ──────────────────────────────────────────── */
             + "  <table class=\"total-table\"><tbody>\n"
             + "    <tr class=\"subtotal-row\">"
             +       "<td>Total Fees Charged</td>"
             +       "<td class=\"right\">Rs. " + payment.getAmount() + "</td></tr>\n"
             + "    <tr class=\"paid-row\">"
             +       "<td>Amount Paid</td>"
             +       "<td class=\"right\">Rs. " + payment.getAmountPaid() + "</td></tr>\n"
             + "  </tbody></table>\n"
             /* ── Signature ───────────────────────────────────────── */
             + "  <div class=\"sig-area\">\n"
             + "    <p>Authorised Signatory</p>\n"
             + "    <p>_________________________</p>\n"
             + "    <p>For Indra Academy</p>\n"
             + "  </div>\n"
             /* ── Footer ─────────────────────────────────────────── */
             + "  <div class=\"footer\">\n"
             + "    <p>This is a computer-generated receipt and does not require a physical signature.</p>\n"
             + "    <p>Generated on: " + generatedOn + "</p>\n"
             + "  </div>\n"
             + "</body>\n"
             + "</html>";
    }

    /** Appends a fee row only when amount &gt; 0. Returns the incremented row index. */
    private int appendFeeRow(StringBuilder sb, String label, int amount, int rowIdx) {
        if (amount <= 0) return rowIdx;
        String cls = (rowIdx % 2 == 0) ? "fee-even" : "fee-odd";
        sb.append("    <tr class=\"").append(cls).append("\">")
          .append("<td>").append(esc(label)).append("</td>")
          .append("<td class=\"amt-col\">").append(amount).append("</td>")
          .append("</tr>\n");
        return rowIdx + 1;
    }

    /** Minimal HTML escaping for user-supplied strings. */
    private String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}