package com.indraacademy.ias_management.dto;

public class KnowledgeSearchResultDTO {

    private String chunkText;
    private Long documentId;
    private String documentTitle;
    private Integer pageNumber;
    private double similarity;

    public KnowledgeSearchResultDTO() {}

    public KnowledgeSearchResultDTO(String chunkText, Long documentId, String documentTitle,
                                     Integer pageNumber, double similarity) {
        this.chunkText = chunkText;
        this.documentId = documentId;
        this.documentTitle = documentTitle;
        this.pageNumber = pageNumber;
        this.similarity = similarity;
    }

    public String getChunkText() { return chunkText; }
    public void setChunkText(String chunkText) { this.chunkText = chunkText; }

    public Long getDocumentId() { return documentId; }
    public void setDocumentId(Long documentId) { this.documentId = documentId; }

    public String getDocumentTitle() { return documentTitle; }
    public void setDocumentTitle(String documentTitle) { this.documentTitle = documentTitle; }

    public Integer getPageNumber() { return pageNumber; }
    public void setPageNumber(Integer pageNumber) { this.pageNumber = pageNumber; }

    public double getSimilarity() { return similarity; }
    public void setSimilarity(double similarity) { this.similarity = similarity; }
}
