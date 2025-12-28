package com.saltlux.filedepot.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import com.saltlux.filedepot.entity.Chunk;
import com.saltlux.filedepot.entity.ExtractedContent;
import com.saltlux.filedepot.entity.ProcessingStep;
import com.saltlux.filedepot.entity.StorageItem;
import com.saltlux.filedepot.repository.ChunkRepository;
import com.saltlux.filedepot.repository.ExtractedContentRepository;
import com.saltlux.filedepot.repository.StorageItemRepository;

import io.minio.StatObjectResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.hanju.filedepot.api.dto.BatchDownloadRequest;
import me.hanju.filedepot.api.dto.ChunkDto;
import me.hanju.filedepot.api.dto.ConfirmUploadRequest;
import me.hanju.filedepot.api.dto.DownloadUrlResponse;
import me.hanju.filedepot.api.dto.StorageItemDto;
import me.hanju.filedepot.api.dto.UploadUrlResponse;
import me.hanju.filedepot.api.enums.ProcessingStatus;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class FileService {

    private final StorageItemRepository storageItemRepository;
    private final ExtractedContentRepository extractedContentRepository;
    private final ChunkRepository chunkRepository;
    private final StorageClient storageClient;

    private static final int PRESIGNED_URL_EXPIRY_SECONDS = 3600;

    public UploadUrlResponse prepareUpload() {
        String uuid = UUID.randomUUID().toString();
        String uploadUrl = storageClient.getPresignedUploadUrl(uuid, PRESIGNED_URL_EXPIRY_SECONDS);

        log.info("Prepared upload: uuid={}", uuid);

        return new UploadUrlResponse(uuid, uploadUrl, PRESIGNED_URL_EXPIRY_SECONDS);
    }

    public StorageItemDto confirmUpload(ConfirmUploadRequest request) {
        StatObjectResponse stat = storageClient.statObject(request.id());

        String fileName = request.fileName();
        if (fileName == null || fileName.isBlank()) {
            fileName = request.id();
        }

        StorageItem item = StorageItem.builder()
                .uuid(request.id())
                .contentType(stat.contentType())
                .size(stat.size())
                .fileName(fileName)
                .build();

        storageItemRepository.save(item);

        log.info("Confirmed upload: id={}, fileName={}", request.id(), fileName);

        return toDto(item);
    }

    @Transactional(readOnly = true)
    public StorageItemDto getFileMetadata(String id, boolean withContent) {
        StorageItem item = storageItemRepository.findByUuidAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + id));

        return toDto(item, withContent);
    }

    @Transactional(readOnly = true)
    public DownloadUrlResponse getDownloadUrl(String uuid) {
        StorageItem item = storageItemRepository.findByUuidAndDeletedFalse(uuid)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + uuid));

        String downloadUrl = storageClient.getPresignedDownloadUrl(item.getUuid(), PRESIGNED_URL_EXPIRY_SECONDS);

        return new DownloadUrlResponse(downloadUrl, PRESIGNED_URL_EXPIRY_SECONDS);
    }

    @Transactional(readOnly = true)
    public List<ChunkDto> getChunks(String id, boolean withEmbedding) {
        storageItemRepository.findByUuidAndDeletedFalse(id)
                .orElseThrow(() -> new IllegalArgumentException("File not found: " + id));

        List<Chunk> chunks = chunkRepository.findByUuidOrderByChunkIndexAsc(id);

        return chunks.stream()
                .map(chunk -> toChunkDto(chunk, withEmbedding))
                .toList();
    }

    public void deleteFiles(List<String> uuids) {
        if (uuids == null || uuids.isEmpty()) {
            return;
        }

        List<String> deletedUuids = new ArrayList<>();
        List<String> failedUuids = new ArrayList<>();

        for (String uuid : uuids) {
            try {
                storageClient.removeObject(uuid);
                deletedUuids.add(uuid);
            } catch (Exception e) {
                log.warn("Failed to remove object from MinIO: uuid={}", uuid, e);
                failedUuids.add(uuid);
            }
        }

        if (!deletedUuids.isEmpty()) {
            storageItemRepository.deleteByUuidIn(deletedUuids);
        }

        if (!failedUuids.isEmpty()) {
            List<StorageItem> failedItems = storageItemRepository.findByUuidInAndDeletedFalse(failedUuids);
            for (StorageItem item : failedItems) {
                item.markAsDeleted();
            }
            storageItemRepository.saveAll(failedItems);
        }

        log.info("Deleted {} files, {} marked for retry", deletedUuids.size(), failedUuids.size());
    }

    public StreamingResponseBody downloadBatch(BatchDownloadRequest request) {
        List<String> ids = request.ids();
        if (ids == null || ids.isEmpty()) {
            throw new IllegalArgumentException("IDs list cannot be empty");
        }

        List<StorageItem> items = storageItemRepository.findByUuidInAndDeletedFalse(ids);

        if (items.isEmpty()) {
            throw new IllegalArgumentException("No files found for the given UUIDs");
        }

        Map<Long, String> idToZipEntryName = resolveNameConflicts(items);

        return outputStream -> {
            try (ZipOutputStream zipOut = new ZipOutputStream(outputStream)) {
                for (StorageItem item : items) {
                    String zipEntryName = idToZipEntryName.get(item.getId());
                    zipOut.putNextEntry(new ZipEntry(zipEntryName));

                    try (InputStream inputStream = storageClient.getObject(item.getUuid())) {
                        inputStream.transferTo(zipOut);
                    }

                    zipOut.closeEntry();
                }

                zipOut.finish();
                log.info("Batch download completed: {} files", items.size());
            } catch (Exception e) {
                log.error("Failed to create ZIP archive", e);
                throw new IOException("Failed to create ZIP archive", e);
            }
        };
    }

    private StorageItemDto toDto(StorageItem item) {
        return toDto(item, false);
    }

    private StorageItemDto toDto(StorageItem item, boolean withContent) {
        String content = null;
        if (withContent) {
            content = extractedContentRepository.findByStorageItemUuid(item.getUuid())
                    .map(ExtractedContent::getContent)
                    .orElse(null);
        }

        return new StorageItemDto(
                item.getUuid(),
                item.getFileName(),
                item.getSize(),
                item.getContentType(),
                toStatus(item.getProcessingStep()),
                item.getCreatedAt(),
                content
        );
    }

    private ProcessingStatus toStatus(ProcessingStep step) {
        return switch (step) {
            case PENDING -> ProcessingStatus.PENDING;
            case PROCESSING -> ProcessingStatus.PROCESSING;
            case EXTRACTED, CHUNKED -> ProcessingStatus.PROCESSING;
            case EMBEDDED -> ProcessingStatus.COMPLETED;
            case FAILED -> ProcessingStatus.FAILED;
        };
    }

    private Map<Long, String> resolveNameConflicts(List<StorageItem> items) {
        Map<Long, String> result = new HashMap<>();
        for (int i = 0; i < items.size(); i++) {
            StorageItem item = items.get(i);
            result.put(item.getId(), item.getUuid());
        }
        return result;
    }

    private ChunkDto toChunkDto(Chunk chunk, boolean withEmbedding) {
        return new ChunkDto(
                chunk.getId().toString(),
                chunk.getChunkIndex(),
                chunk.getExtractedText(),
                withEmbedding ? toEmbeddingList(chunk.getEmbedding()) : null
        );
    }

    private List<Float> toEmbeddingList(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        List<Float> result = new ArrayList<>();
        while (buffer.hasRemaining()) {
            result.add(buffer.getFloat());
        }
        return result;
    }
}
