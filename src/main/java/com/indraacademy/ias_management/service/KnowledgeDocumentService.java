package com.indraacademy.ias_management.service;

import com.indraacademy.ias_management.dto.KnowledgeDocumentDTO;
import com.indraacademy.ias_management.dto.KnowledgeSearchResultDTO;
import com.indraacademy.ias_management.entity.KnowledgeChunk;
import com.indraacademy.ias_management.entity.KnowledgeDocument;
import com.indraacademy.ias_management.repository.KnowledgeChunkRepository;
import com.indraacademy.ias_management.repository.KnowledgeDocumentRepository;
import com.indraacademy.ias_management.repository.KnowledgeSearchRepository;
import com.indraacademy.ias_management.util.SecurityUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class KnowledgeDocumentService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDocumentService.class);

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md");
    private static final long MAX_FILE_SIZE = 20L * 1024 * 1024; // 20 MB
    // Matches tools/knowledge_base.py's _TOP_K, which always sends an explicit
    // topK — this is just a consistent fallback if some future caller doesn't.
    private static final int TOP_K_DEFAULT = 3;
    private static final int TOP_K_MAX = 20;

    @Value("${knowledge.upload.directory:./uploads/knowledge-base}")
    private String uploadDirectory;

    /** Same Python service used for chat — see AiProxyController for the identical pattern. */
    @Value("${ai.service.url:http://localhost:8001}")
    private String aiServiceUrl;

    @Value("${ai.internal.secret}")
    private String aiInternalSecret;

    /**
     * Relevance floor for search results — see KnowledgeSearchRepository.search()'s
     * javadoc for why this exists. 0.35 is an evidence-based starting point, not a
     * universal constant: measured directly against real text-embedding-3-small
     * output on a real uploaded test document, genuinely-answering chunks scored
     * 0.48-0.56 while topically-unrelated chunks scored 0.29-0.34. Expect to retune
     * this as more real school documents accumulate — externalized as a property
     * specifically so retuning doesn't require a redeploy.
     */
    @Value("${knowledge.search.min-similarity:0.35}")
    private double minSimilarity;

    @Autowired private KnowledgeDocumentRepository documentRepository;
    @Autowired private KnowledgeChunkRepository chunkRepository;
    @Autowired private KnowledgeSearchRepository searchRepository;
    @Autowired private SecurityUtil securityUtil;

    // RestTemplate is fine here — infrequent, admin-triggered, and latency-bound by
    // text-extraction + embedding calls anyway, same reasoning as AiProxyController's /chat.
    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * Stores the file, creates the document row (status=PROCESSING), then synchronously
     * calls Python to extract/chunk/embed and persists the resulting chunks. Deliberately
     * NOT wrapped in a single @Transactional — the Python call is a slow external HTTP
     * request and holding a DB transaction open across it would tie up a connection for
     * no reason; each write below commits on its own via the repository.
     */
    public KnowledgeDocumentDTO uploadDocument(MultipartFile file, String title, String category) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A file is required.");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size exceeds the 20 MB limit.");
        }
        String originalFilename = StringUtils.cleanPath(Objects.requireNonNull(file.getOriginalFilename()));
        String ext = extensionOf(originalFilename);
        if (!ALLOWED_EXTENSIONS.contains(ext)) {
            throw new IllegalArgumentException("Only PDF, DOCX, TXT, and MD files are allowed.");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Title is required.");
        }

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not read the uploaded file.");
        }

        Long schoolId = securityUtil.getSchoolId();

        KnowledgeDocument document = new KnowledgeDocument();
        document.setSchoolId(schoolId);
        document.setTitle(title.trim());
        document.setCategory(category == null || category.isBlank() ? "GENERAL" : category.trim().toUpperCase());
        document.setOriginalFilename(originalFilename);
        document.setStatus("PROCESSING");
        document.setUploadedByAdminId(securityUtil.getUsername());
        document.setStoragePath("");
        document = documentRepository.save(document);

        try {
            Path dir = Paths.get(uploadDirectory, String.valueOf(schoolId)).toAbsolutePath().normalize();
            Files.createDirectories(dir);
            Path target = dir.resolve(document.getId() + "." + ext);
            Files.write(target, fileBytes);
            document.setStoragePath(target.toString());
            document = documentRepository.save(document);
        } catch (Exception e) {
            log.error("Failed to store knowledge base file for schoolId={}: {}", schoolId, e.getMessage());
            document.setStatus("FAILED");
            document.setErrorMessage("Could not save the uploaded file.");
            documentRepository.save(document);
            return toDTO(document);
        }

        processDocument(document, fileBytes);
        return toDTO(document);
    }

    @SuppressWarnings("unchecked")
    private void processDocument(KnowledgeDocument document, byte[] fileBytes) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("X-Internal-Secret", aiInternalSecret);

            String filename = document.getOriginalFilename();
            ByteArrayResource fileResource = new ByteArrayResource(fileBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", fileResource);

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

            Map<String, Object> response = restTemplate.postForObject(
                    aiServiceUrl + "/kb/process-document", requestEntity, Map.class);

            if (response == null || !"ready".equals(response.get("status"))) {
                String error = response != null ? String.valueOf(response.get("error")) : "No response from AI service.";
                document.setStatus("FAILED");
                document.setErrorMessage(error);
                documentRepository.save(document);
                log.warn("Knowledge base processing failed for documentId={}: {}", document.getId(), error);
                return;
            }

            List<Map<String, Object>> chunks = (List<Map<String, Object>>) response.get("chunks");
            for (Map<String, Object> chunkData : chunks) {
                int chunkIndex = ((Number) chunkData.get("chunkIndex")).intValue();
                String text = (String) chunkData.get("text");
                Object pageNumberRaw = chunkData.get("pageNumber");
                Integer pageNumber = pageNumberRaw != null ? ((Number) pageNumberRaw).intValue() : null;
                List<Number> embeddingRaw = (List<Number>) chunkData.get("embedding");
                List<Float> embedding = embeddingRaw.stream().map(Number::floatValue).collect(Collectors.toList());

                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setDocumentId(document.getId());
                chunk.setSchoolId(document.getSchoolId());
                chunk.setChunkIndex(chunkIndex);
                chunk.setChunkText(text);
                chunk.setPageNumber(pageNumber);
                chunk = chunkRepository.save(chunk);
                searchRepository.saveEmbedding(chunk.getId(), embedding);
            }

            document.setStatus("READY");
            document.setErrorMessage(null);
            documentRepository.save(document);
            log.info("Knowledge base document {} processed: {} chunks for school {}",
                    document.getId(), chunks.size(), document.getSchoolId());

        } catch (Exception e) {
            log.error("Knowledge base processing call failed for documentId={}", document.getId(), e);
            document.setStatus("FAILED");
            document.setErrorMessage("Processing failed: AI service unavailable.");
            documentRepository.save(document);
        }
    }

    @Transactional(readOnly = true)
    public List<KnowledgeDocumentDTO> listDocuments() {
        Long schoolId = securityUtil.getSchoolId();
        return documentRepository.findBySchoolIdOrderByCreatedAtDesc(schoolId)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public KnowledgeDocument getDocumentForDownload(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        return documentRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));
    }

    @Transactional
    public void deleteDocument(Long id) {
        Long schoolId = securityUtil.getSchoolId();
        KnowledgeDocument document = documentRepository.findByIdAndSchoolId(id, schoolId)
                .orElseThrow(() -> new IllegalArgumentException("Document not found"));

        chunkRepository.deleteByDocumentId(id);

        try {
            if (document.getStoragePath() != null && !document.getStoragePath().isBlank()) {
                Files.deleteIfExists(Paths.get(document.getStoragePath()));
            }
        } catch (Exception e) {
            log.warn("Could not delete file for knowledge document {}: {}", id, e.getMessage());
        }

        documentRepository.delete(document);
        log.info("Deleted knowledge document {} ('{}') for school {}", id, document.getTitle(), schoolId);
    }

    @Transactional(readOnly = true)
    public List<KnowledgeSearchResultDTO> search(List<Float> embedding, Integer topK) {
        Long schoolId = securityUtil.getSchoolId();
        int k = (topK == null || topK <= 0) ? TOP_K_DEFAULT : Math.min(topK, TOP_K_MAX);
        return searchRepository.search(schoolId, embedding, k, minSimilarity);
    }

    private String extensionOf(String filename) {
        int i = filename.lastIndexOf('.');
        return i >= 0 ? filename.substring(i + 1).toLowerCase() : "";
    }

    private KnowledgeDocumentDTO toDTO(KnowledgeDocument d) {
        return new KnowledgeDocumentDTO(d.getId(), d.getTitle(), d.getCategory(), d.getOriginalFilename(),
                d.getStatus(), d.getErrorMessage(), d.getCreatedAt());
    }
}
