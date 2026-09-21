package com.indraacademy.ias_management.dto;

/** displayUrl is a freshly-presigned, short-lived GET URL for immediate UI feedback — never
 * persisted; the next normal fetch of the owning entity resolves its own fresh URL the same way
 * (see TeacherService's photo-resolution logic). */
public record UploadCompleteResponse(String objectKey, String displayUrl) {}
