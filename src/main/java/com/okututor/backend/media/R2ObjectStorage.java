package com.okututor.backend.media;

import com.okututor.backend.common.config.AppProperties;
import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Cloudflare R2 через S3-совместимый API.
 * Ключи содержат UUID -> объекты immutable, поэтому Cache-Control
 * public, max-age=31536000, immutable безопасен для CDN.
 */
@Component
@ConditionalOnProperty(prefix = "app.media", name = "provider", havingValue = "r2")
public class R2ObjectStorage implements ObjectStorage {

    private final S3Client s3;
    private final String bucket;
    private final String publicBaseUrl;

    public R2ObjectStorage(AppProperties properties) {
        var r2 = properties.getMedia().getR2();
        if (isBlank(r2.getAccountId()) || isBlank(r2.getAccessKeyId())
                || isBlank(r2.getSecretAccessKey()) || isBlank(r2.getBucket())) {
            throw new IllegalStateException(
                    "app.media.provider=r2 требует R2_ACCOUNT_ID, R2_ACCESS_KEY_ID, "
                            + "R2_SECRET_ACCESS_KEY и R2_BUCKET");
        }
        this.bucket = r2.getBucket();
        this.publicBaseUrl = r2.getPublicBaseUrl();
        this.s3 = S3Client.builder()
                .region(Region.of("auto"))
                .endpointOverride(URI.create(
                        "https://%s.r2.cloudflarestorage.com".formatted(r2.getAccountId())))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(r2.getAccessKeyId(), r2.getSecretAccessKey())))
                .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create())
                .build();
    }

    @Override
    public StoredObject upload(String key, byte[] data, String contentType) {
        s3.putObject(PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType(contentType)
                        .cacheControl("public, max-age=31536000, immutable")
                        .build(),
                RequestBody.fromBytes(data));
        return new StoredObject(key, publicUrl(key), data.length);
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    @Override
    public boolean exists(String key) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public String publicUrl(String key) {
        if (isBlank(publicBaseUrl)) {
            throw new IllegalStateException("R2_PUBLIC_BASE_URL обязателен при provider=r2");
        }
        return publicBaseUrl + "/" + key;
    }

    private static boolean isBlank(String v) {
        return v == null || v.isBlank();
    }
}
