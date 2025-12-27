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

### 3. 배치 처리

- 비동기 큐 기반 파일 처리
- Cron 스케줄링 (추출, 청킹, 임베딩)
- 고아 파일 정리

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
│   ├── ExtractedContent.java
│   ├── FileContent.java
│   ├── ProcessingStep.java
│   └── StorageItem.java
├── repository/
│   ├── ExtractedContentRepository.java
│   ├── FileContentRepository.java
│   └── StorageItemRepository.java
└── service/
    ├── BatchScheduler.java
    ├── FileService.java
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
  "id": "550e8400-e29b-41d4-a716-446655440000"
}
```

**Response**

```json
{
  "success": true,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "size": 1048576,
    "contentType": "application/pdf",
    "status": "PENDING",
    "createdAt": "2025-01-15T10:30:00Z"
  }
}
```

### 파일 조회

#### 메타데이터 조회

```http
GET /api/files/{uuid}
```

**Response**

```json
{
  "success": true,
  "data": {
    "id": "550e8400-e29b-41d4-a716-446655440000",
    "size": 1048576,
    "contentType": "application/pdf",
    "status": "COMPLETED",
    "createdAt": "2025-01-15T10:30:00Z"
  }
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
| contentType    | String  | MIME 타입                                                             |
| size           | Long    | 파일 크기                                                             |
| processingStep | Enum    | 처리 상태 (PENDING, PROCESSING, EXTRACTED, CHUNKED, EMBEDDED, FAILED) |
| createdAt      | Instant | 생성 일시                                                             |
| deleted        | Boolean | 삭제 여부                                                             |
| retryCount     | Integer | 재시도 횟수                                                           |

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
