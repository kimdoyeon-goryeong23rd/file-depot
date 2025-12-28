# File Depot

파일 저장 및 문서 처리 파이프라인 서비스

## 주요 기능

### 1. 파일 관리

- Presigned URL 기반 업로드/다운로드
- 일괄 다운로드 (ZIP)
- 파일 삭제 (Soft Delete)

### 2. 문서 처리 파이프라인

- **텍스트 추출**: Parsekit 연동 (Scenario1: Converter → Docling → VLM, Scenario2: Converter → VLM)
- **청킹**: 추출된 텍스트를 지정 크기로 분할
- **임베딩**: VLLM 또는 Luxia 서비스 연동

### 3. 처리 아키텍처

- **즉시 비동기 처리**: 업로드 확인 시 `ProcessingQueue`가 별도 스레드에서 즉시 처리 시작
- **Retry 배치 스케줄러**: 실패하거나 중단된 파일들을 Cron 주기로 재처리
- **고아 파일 정리**: soft-delete된 파일의 실제 삭제

## 프로젝트 구조

```
src/main/java/com/saltlux/filedepot/
├── FileDepotApplication.java
├── common/
│   └── GlobalExceptionHandler.java
├── config/
│   ├── AsyncConfig.java
│   ├── EmbedKitConfig.java
│   ├── FileDepotProperties.java
│   ├── MinioConfig.java
│   └── ParsekitConfig.java
├── controller/
│   └── FileController.java
├── entity/
│   ├── Chunk.java
│   ├── ExtractedContent.java
│   ├── ProcessingStep.java
│   └── StorageItem.java
├── repository/
│   ├── ChunkRepository.java
│   ├── ExtractedContentRepository.java
│   └── StorageItemRepository.java
└── service/
    ├── BatchScheduler.java
    ├── FileService.java
    ├── ProcessingQueue.java
    ├── ProcessingService.java
    ├── StorageClient.java
    └── TextExtractor.java
```

## API

### 파일 업로드

#### 1. 업로드 URL 발급

```http
POST /api/files/prepare-upload
```

**Response**

```json
{
  "success": true,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "uploadUrl": "http://minio:9000/file-depot/550e8400-e29b-41d4-a716-446655440000?X-Amz-Algorithm=...",
    "expiresIn": 3600
  }
}
```

#### 2. 클라이언트에서 Presigned URL로 파일 업로드

```bash
curl -X PUT "${uploadUrl}" \
  -H "Content-Type: application/pdf" \
  --data-binary @document.pdf
```

#### 3. 업로드 확인

```http
POST /api/files/confirm-upload
Content-Type: application/json

{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "fileName": "document.pdf"
}
```

> `fileName`은 선택 사항입니다. 미입력 시 `id`가 파일명으로 사용됩니다.

**Response**

```json
{
  "success": true,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "fileName": "document.pdf",
    "size": 1048576,
    "contentType": "application/pdf",
    "status": "PENDING",
    "createdAt": "2025-01-15T10:30:00Z"
  }
}
```

> 업로드 확인 즉시 비동기 처리가 시작됩니다.

### 파일 조회

#### 메타데이터 조회

```http
GET /api/files/{uuid}?withContent=false
```

| Parameter     | Type    | Default | Description                    |
| ------------- | ------- | ------- | ------------------------------ |
| `withContent` | boolean | false   | true면 추출된 텍스트 내용 포함 |

**Response**

```json
{
  "success": true,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "fileName": "document.pdf",
    "size": 1048576,
    "contentType": "application/pdf",
    "status": "COMPLETED",
    "createdAt": "2025-01-15T10:30:00Z",
    "content": null
  }
}
```

#### 청크 조회

```http
GET /api/files/{uuid}/chunks?withEmbedding=false
```

| Parameter       | Type    | Default | Description             |
| --------------- | ------- | ------- | ----------------------- |
| `withEmbedding` | boolean | false   | true면 임베딩 벡터 포함 |

**Response**

```json
{
  "success": true,
  "data": [
    {
      "id": "1",
      "index": 0,
      "content": "첫 번째 청크 텍스트...",
      "embedding": null
    },
    {
      "id": "2",
      "index": 1,
      "content": "두 번째 청크 텍스트...",
      "embedding": null
    }
  ]
}
```

### 파일 다운로드

#### 다운로드 URL 발급

```http
GET /api/files/{uuid}/download-url
```

**Response**

```json
{
  "success": true,
  "data": {
    "downloadUrl": "http://minio:9000/file-depot/550e8400-e29b-41d4-a716-446655440000?X-Amz-Algorithm=...",
    "expiresIn": 3600
  }
}
```

#### 일괄 다운로드 (ZIP)

```http
POST /api/files/download/batch
Content-Type: application/json

{
  "ids": [
    "550e8400-e29b-41d4-a716-446655440000",
    "550e8400-e29b-41d4-a716-446655440001"
  ]
}
```

**Response**: `application/octet-stream` (files.zip)

### 파일 삭제

```http
POST /api/files/delete
Content-Type: application/json

["550e8400-e29b-41d4-a716-446655440000", "550e8400-e29b-41d4-a716-446655440001"]
```

**Response**

```json
{
  "success": true,
  "data": null
}
```

## 데이터 모델

### StorageItem

| Field          | Type    | Description                                                           |
| -------------- | ------- | --------------------------------------------------------------------- |
| id             | Long    | Primary Key (Auto Increment)                                          |
| uuid           | String  | 파일 고유 식별자                                                      |
| fileName       | String  | 원본 파일명                                                           |
| contentType    | String  | MIME 타입                                                             |
| size           | Long    | 파일 크기                                                             |
| processingStep | Enum    | 처리 상태 (PENDING, PROCESSING, EXTRACTED, CHUNKED, EMBEDDED, FAILED) |
| createdAt      | Instant | 생성 일시                                                             |
| deleted        | Boolean | 삭제 여부                                                             |
| retryCount     | Integer | 재시도 횟수                                                           |

### Chunk

| Field      | Type   | Description              |
| ---------- | ------ | ------------------------ |
| id         | Long   | Primary Key              |
| uuid       | String | 연결된 StorageItem UUID  |
| chunkIndex | int    | 청크 순서 (0부터 시작)   |
| content    | String | 청크 텍스트              |
| embedding  | byte[] | 임베딩 벡터 (float 배열) |

## 실행 방법

### 개발 환경 (TestContainers 사용)

```bash
./gradlew bootTestRun
```

> `bootRun`은 비활성화되어 있습니다. 개발 시 TestContainers가 MariaDB, MinIO를 자동 시작하는 `bootTestRun`을 사용하세요.

### 테스트

```bash
./gradlew test
```

### 프로덕션

```bash
SPRING_PROFILES_ACTIVE=prod ./gradlew bootRun
```

### Docker Compose

```yaml
services:
  file-depot:
    image: kimdoyeongr23rd/file-depot:0.2.0
    ports:
      - '8080:8080'
      - '8081:8081'
    environment:
      # Spring Profile
      SPRING_PROFILES_ACTIVE: prod

      # Database
      MARIADB_URL: jdbc:mariadb://mariadb:3306/file_depot
      MARIADB_USER: root
      MARIADB_PASSWORD: secret
      DB_POOL_SIZE: 10
      DDL_AUTO: update

      # MinIO
      MINIO_URL: http://minio:9000
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
      MINIO_BUCKET: file-depot

      # Document Processing (optional)
      PARSEKIT_SCENARIO: disabled # disabled, scenario1, scenario2
      # PARSEKIT_CONVERTER_URL: http://converter:3000
      # PARSEKIT_DOCLING_URL: http://docling:5001
      # PARSEKIT_VLM_URL: http://vlm:8000
      # PARSEKIT_VLM_MODEL: Qwen/Qwen2.5-VL-7B-Instruct

      # Embedding (optional)
      EMBEDKIT_PROVIDER: none # none, vllm, luxia
      # EMBEDKIT_VLLM_URL: http://vllm:8000
      # EMBEDKIT_VLLM_MODEL: BAAI/bge-m3

      # Processing
      PROCESSING_BATCH_ENABLED: 'true'
      PROCESSING_MAX_RETRY_COUNT: 3
    depends_on:
      mariadb:
        condition: service_healthy
      minio:
        condition: service_healthy

  mariadb:
    image: mariadb:11.2
    environment:
      MYSQL_ROOT_PASSWORD: secret
      MYSQL_DATABASE: file_depot
    volumes:
      - mariadb_data:/var/lib/mysql
    healthcheck:
      test: ['CMD', 'healthcheck.sh', '--connect', '--innodb_initialized']
      interval: 10s
      timeout: 5s
      retries: 5

  minio:
    image: minio/minio:latest
    command: server /data --console-address ":9001"
    ports:
      - '9000:9000'
      - '9001:9001'
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    volumes:
      - minio_data:/data
    healthcheck:
      test: ['CMD', 'curl', '-f', 'http://localhost:9000/minio/health/live']
      interval: 10s
      timeout: 5s
      retries: 5

volumes:
  mariadb_data:
  minio_data:
```

> MinIO 버킷(`file-depot`)은 애플리케이션 시작 시 자동으로 생성됩니다.

## 환경 변수

`.env.example` 파일을 참고하여 `.env` 파일을 생성하세요.

### 필수 (prod 프로필)

```bash
# Database
MARIADB_URL=jdbc:mariadb://localhost:3306/file_depot
MARIADB_USER=root
MARIADB_PASSWORD=your_password

# MinIO
MINIO_URL=http://localhost:9000
MINIO_ACCESS_KEY=minioadmin
MINIO_SECRET_KEY=minioadmin
MINIO_BUCKET=file-depot
```

### 선택

```bash
# Document Processing
PARSEKIT_SCENARIO=disabled  # disabled, scenario1, scenario2

# Embedding
EMBEDKIT_PROVIDER=none  # none, vllm, luxia
```

## 연관 프로젝트

| 프로젝트          | 설명                             | 저장소                                                                                                  |
| ----------------- | -------------------------------- | ------------------------------------------------------------------------------------------------------- |
| file-depot-api    | File Depot API 정의              | [agent-hanju/file-depot-api](https://github.com/agent-hanju/file-depot-api)                             |
| file-depot-client | File Depot 클라이언트 라이브러리 | [kimdoyeon-goryeong23rd/file-depot-client](https://github.com/kimdoyeon-goryeong23rd/file-depot-client) |
