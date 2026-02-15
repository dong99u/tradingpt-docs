# S3 이미지 보안 시스템 - 실용적 구현 가이드

> **Version**: 2.0.0
> **Last Updated**: 2025-01-27
> **Author**: TPT Development Team

---

## 기술 키워드

| 카테고리      | 키워드                                                     |
|-----------|---------------------------------------------------------|
| **문제 유형** | `Security`, `Infrastructure`, `Practical Solution`      |
| **기술 스택** | `AWS S3`, `Presigned URL`, `Spring Boot`, `AWS SDK 2.x` |
| **보안 기법** | `UUID Filename`, `Bucket Policy`, `Presigned URL`       |
| **적용 범위** | `FeedbackRequest`, `FeedbackResponse`, `Lecture`        |

---

> **작성일**: 2025년 11월
> **프로젝트**: TPT-API (Trading Platform API)
> **상태**: 구현 완료

## 목차

1. [배경 및 제약 조건](#1-배경-및-제약-조건)
2. [보안 분석](#2-보안-분석)
3. [최종 결정: 실용적 접근법](#3-최종-결정-실용적-접근법)
4. [구현 현황](#4-구현-현황)
5. [적용 가이드](#5-적용-가이드)
6. [향후 계획](#6-향후-계획)

---

## 1. 배경 및 제약 조건

### 현재 상황

개발 환경에서 S3 URL을 데이터베이스에 직접 저장하고 클라이언트에 그대로 전달하는 방식을 사용 중입니다. 운영 환경 배포를 앞두고 보안 강화가 필요한 상황입니다.

```
현재 방식:
Client ──▶ API Server ──▶ S3 Bucket
   │            │              │
   │       S3 URL 저장 (DB)    │
   │            │              │
   │◀─── S3 Public URL ────   │
   │                           │
   └────── 직접 접근 가능 ────▶│
```

### 현실적 제약 조건

| 제약         | 설명                                |
|------------|-----------------------------------|
| **개발 시간**  | 운영 환경 배포 일정이 촉박                   |
| **인프라 비용** | CloudFront 도입 시 추가 비용 발생          |
| **복잡도**    | CloudFront + OAC 구성에 상당한 설정 작업 필요 |
| **팀 리소스**  | 현재 기능 개발에 집중 필요                   |

### 왜 CloudFront를 지금 도입하지 않는가?

이상적으로는 CloudFront + Origin Access Control(OAC)을 사용하는 것이 최고의 보안을 제공합니다. 하지만 현 시점에서 다음 이유로 보류합니다:

1. **시간**: CloudFront 배포 생성, OAC 설정, 기존 URL 마이그레이션에 최소 1-2주 소요
2. **비용**: 월 고정 비용 발생 (트래픽 적은 초기 단계에서는 비효율)
3. **복잡도**: 캐시 무효화, SSL 인증서, 커스텀 도메인 등 추가 관리 포인트
4. **필요성**: 현재 트래픽 수준에서는 S3 직접 접근도 충분히 빠름

**결론**: 트래픽이 증가하고 서비스가 안정화된 후 CloudFront 도입 검토

---

## 2. 보안 분석

### UUID 파일명으로 URL 추측 공격 방지

현재 시스템은 파일 업로드 시 UUID 기반 파일명을 사용합니다.

```
원본 파일명: trading_screenshot.png
저장된 파일명: feedbacks/2025/01/550e8400-e29b-41d4-a716-446655440000.png
```

**UUID v4 보안성 분석**:

- 총 경우의 수: 2^122 (약 5.3 x 10^36)
- 무작위 추측 확률: 사실상 0%
- 초당 10억 개 URL 시도해도 수십억 년 필요

**결론**: UUID 파일명만으로도 URL 추측 공격은 현실적으로 불가능

### 실제 위협 시나리오

| 위협           | 위험도 | UUID로 방지 | Presigned로 방지 |
|--------------|-----|----------|---------------|
| URL 무작위 추측   | 낮음  | O        | O             |
| URL 유출 후 접근  | 중간  | X        | O             |
| 브라우저 히스토리 노출 | 낮음  | X        | O             |
| 로그 파일 노출     | 낮음  | X        | O             |

### 데이터 민감도 분류

| 유형        | 민감도    | 유출 시 영향       | 권장 방식             |
|-----------|--------|---------------|-------------------|
| 강의 썸네일/배너 | 낮음     | 미미            | Public            |
| 프로필 이미지   | 낮음     | 미미            | Public            |
| 컬럼/리뷰 이미지 | 낮음     | 미미            | Public            |
| **강의 영상** | **높음** | **유료 콘텐츠 유출** | **Presigned URL** |
| 피드백 첨부파일  | 중간     | 개인 거래 정보 노출   | Presigned URL     |
| 결제 영수증    | 높음     | 개인정보 노출       | Presigned URL     |

---

## 3. 최종 결정: 실용적 접근법

### 핵심 전략

```
┌────────────────────────────────────────────────────┐
│              TPT-API 이미지 보안 전략               │
├─────────────────────┬──────────────────────────────┤
│    이미지 유형       │    적용 방식                  │
├─────────────────────┼──────────────────────────────┤
│ 썸네일, 배너, 프로필 │ S3 Public + UUID 파일명       │
│ 강의 영상 (유료)    │ Presigned URL (1시간 만료)    │
│ 피드백 첨부파일      │ Presigned URL (30분 만료)     │
│ 결제 영수증         │ Presigned URL (5분 만료)      │
└─────────────────────┴──────────────────────────────┘
```

### S3 버킷 정책 예시

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "AllowPublicReadForPublicContent",
      "Effect": "Allow",
      "Principal": "*",
      "Action": "s3:GetObject",
      "Resource": [
        "arn:aws:s3:::tpt-bucket/thumbnails/*",
        "arn:aws:s3:::tpt-bucket/profiles/*",
        "arn:aws:s3:::tpt-bucket/banners/*"
      ]
    }
  ]
}
```

**주의**: 강의 영상(`lectures/videos/*`)이나 피드백 첨부파일(`feedbacks/*`) 경로는 Public 정책에서 제외합니다.

### 만료 시간 기준

| 유형     | 만료 시간 | 이유                     |
|--------|-------|------------------------|
| 강의 영상  | 1시간   | 시청 중 만료 방지, 공유 시 빠른 만료 |
| 피드백 첨부 | 30분   | 확인 작업에 충분한 시간          |
| 결제 영수증 | 5분    | 민감 정보, 빠른 확인 후 만료      |

---

## 4. 구현 현황

### 4.1 fileKey 필드 추가 (완료)

Presigned URL 생성을 위해 S3 object key를 저장하는 필드를 추가했습니다.

**FeedbackRequestAttachment.java**:

```java

@Entity
public class FeedbackRequestAttachment extends BaseEntity {

	/** S3 파일 접근용 URL (기존 Public URL 또는 Presigned URL) */
	private String fileUrl;

	/** S3 파일 삭제 및 Presigned URL 생성용 key */
	private String fileKey;

	public static FeedbackRequestAttachment createFrom(
		FeedbackRequest feedbackRequest,
		String fileUrl,
		String fileKey) {
		// ...
	}

	public void changeFile(String fileUrl, String fileKey) {
		this.fileUrl = fileUrl;
		this.fileKey = fileKey;
	}
}
```

**FeedbackResponseAttachment.java**: 동일한 구조로 fileKey 필드 추가 완료

### 4.2 Presigned Download URL 메서드 (완료)

**S3FileService.java**:

```java
public interface S3FileService {

	/**
	 * Presigned 다운로드 URL 생성
	 *
	 * @param objectKey S3 객체 키 (파일 경로)
	 * @param expirationMinutes URL 만료 시간 (분 단위)
	 * @return Presigned URL 및 메타데이터
	 */
	S3PresignedDownloadResult createPresignedDownloadUrl(
		String objectKey,
		int expirationMinutes
	);

	/** 기본 만료 시간 (60분) 사용 */
	default S3PresignedDownloadResult createPresignedDownloadUrl(String objectKey) {
		return createPresignedDownloadUrl(objectKey, 60);
	}
}
```

**S3FileServiceImpl.java**:

```java

@Override
public S3PresignedDownloadResult createPresignedDownloadUrl(
	String objectKey,
	int expirationMinutes) {

	if (!StringUtils.hasText(objectKey)) {
		throw new S3Exception(S3ErrorStatus.INVALID_OBJECT_KEY);
	}

	// 만료 시간 범위 검증 (1분 ~ 7일)
	int validExpiration = Math.max(1, Math.min(expirationMinutes, 10080));
	Duration expiration = Duration.ofMinutes(validExpiration);

	GetObjectRequest getObjectRequest = GetObjectRequest.builder()
		.bucket(bucketName)
		.key(objectKey)
		.build();

	GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
		.signatureDuration(expiration)
		.getObjectRequest(getObjectRequest)
		.build();

	try {
		var presigned = s3Presigner.presignGetObject(presignRequest);
		return S3PresignedDownloadResult.of(
			presigned.url().toString(),
			objectKey,
			expiration
		);
	} catch (AwsServiceException | SdkClientException e) {
		throw new S3Exception(S3ErrorStatus.PRESIGN_FAILED);
	}
}
```

### 4.3 강의 재생 API 적용 (완료 - PR #171)

팀원이 강의 영상 재생 API에 Presigned URL 적용을 완료했습니다.

```java
// LecturePlayResponseDTO에서 Presigned URL 반환
public LecturePlayResponseDTO getLecturePlayUrl(Long lectureId) {
	Lecture lecture = lectureRepository.findById(lectureId)
		.orElseThrow(() -> new LectureException(LectureErrorStatus.LECTURE_NOT_FOUND));

	String videoKey = lecture.getVideoKey();
	S3PresignedDownloadResult presigned = s3FileService
		.createPresignedDownloadUrl(videoKey, 60);  // 1시간 만료

	return LecturePlayResponseDTO.of(presigned.url(), presigned.expiresAt());
}
```

---

## 5. 적용 가이드

### 5.1 피드백 첨부파일에 Presigned URL 적용 예시

```java

@Service
@RequiredArgsConstructor
public class FeedbackResponseQueryServiceImpl implements FeedbackResponseQueryService {

	private final S3FileService s3FileService;

	public FeedbackResponseDetailDTO getFeedbackDetail(Long feedbackId) {
		FeedbackResponse feedback = feedbackRepository.findById(feedbackId)
			.orElseThrow(() -> new FeedbackException(FeedbackErrorStatus.NOT_FOUND));

		// 첨부파일 URL을 Presigned URL로 변환
		List<AttachmentDTO> attachments = feedback.getAttachments().stream()
			.map(attachment -> {
				String presignedUrl = s3FileService
					.createPresignedDownloadUrl(attachment.getFileKey(), 30)
					.url();
				return AttachmentDTO.of(attachment.getId(), presignedUrl);
			})
			.toList();

		return FeedbackResponseDetailDTO.of(feedback, attachments);
	}
}
```

### 5.2 Response DTO에서 Presigned URL 포함

```java

@Getter
@Builder
public class FeedbackAttachmentDTO {

	@Schema(description = "첨부파일 ID")
	private Long id;

	@Schema(description = "파일 다운로드 URL (30분 후 만료)")
	private String downloadUrl;

	@Schema(description = "URL 만료 시각")
	private LocalDateTime expiresAt;

	public static FeedbackAttachmentDTO from(
		FeedbackRequestAttachment attachment,
		S3PresignedDownloadResult presigned) {
		return FeedbackAttachmentDTO.builder()
			.id(attachment.getId())
			.downloadUrl(presigned.url())
			.expiresAt(LocalDateTime.now().plusMinutes(30))
			.build();
	}
}
```

### 5.3 클라이언트 처리 가이드

프론트엔드에서 Presigned URL 처리 시 주의사항:

```javascript
// Presigned URL은 만료 시간이 있으므로 즉시 사용하거나 캐싱 금지
async function downloadFeedbackAttachment(feedbackId) {
    const response = await api.get(`/api/v1/feedbacks/${feedbackId}`);
    const {attachments} = response.data.result;

    // 각 첨부파일 URL은 30분 후 만료
    attachments.forEach(attachment => {
        console.log('Download URL:', attachment.downloadUrl);
        console.log('Expires at:', attachment.expiresAt);
    });
}

// 이미지 태그에서 직접 사용
// <img src={attachment.downloadUrl} />
```

---

## 6. 향후 계획

### 단기 (현재 ~ 1개월)

- [x] fileKey 필드 추가 (FeedbackRequest/Response Attachment)
- [x] Presigned Download URL 메서드 구현
- [x] 강의 재생 API에 Presigned URL 적용
- [ ] 피드백 첨부파일 조회 API에 Presigned URL 적용
- [ ] 결제 영수증 조회 API에 Presigned URL 적용

### 중기 (1 ~ 3개월)

- [ ] S3 버킷 정책 정리 (경로별 Public/Private 분리)
- [ ] 모니터링 대시보드 구성 (S3 접근 로그 분석)
- [ ] URL 생성 성능 모니터링

### 장기 (트래픽 증가 시)

- [ ] CloudFront 도입 검토
- [ ] 이미지 최적화 (WebP 변환, 리사이징)
- [ ] CDN 캐싱 전략 수립

---

## 관련 문서

- [AWS S3 Presigned URL 공식 문서](https://docs.aws.amazon.com/AmazonS3/latest/userguide/using-presigned-url.html)
- [S3FileService 구현](/src/main/java/com/tradingpt/tpt_api/global/infrastructure/s3/service/S3FileServiceImpl.java)
- [FeedbackRequestAttachment 엔티티](/src/main/java/com/tradingpt/tpt_api/domain/feedbackrequest/entity/FeedbackRequestAttachment.java)

---

## 핵심 정리

### 선택한 방식

| 구분     | 방식               | 이유                 |
|--------|------------------|--------------------|
| 공개 이미지 | S3 Public + UUID | 빠른 구현, UUID로 추측 불가 |
| 민감 파일  | Presigned URL    | URL 유출 시에도 만료로 보호  |

### 채택하지 않은 방식

| 방식               | 이유                        |
|------------------|---------------------------|
| CloudFront + OAC | 시간/비용 제약, 트래픽 증가 시 재검토    |
| 모든 파일 Presigned  | 불필요한 복잡도, 공개 이미지는 과잉 보안   |
| S3 완전 Private    | 인증 시스템 복잡도 증가, 현 단계에서 불필요 |

### 보안 수준 평가

| 항목        | Before    | After             |
|-----------|-----------|-------------------|
| URL 추측 공격 | 낮음 (UUID) | 낮음 (UUID)         |
| URL 유출 위험 | 높음        | 낮음 (Presigned 만료) |
| 민감 파일 보호  | 없음        | 적용됨               |
| 구현 복잡도    | 낮음        | 중간                |

---

**작성자**: TPT Development Team
**최종 수정일**: 2025년 01월 27일
**버전**: 2.0.0
