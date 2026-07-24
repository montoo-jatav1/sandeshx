package com.sandeshx.config

import io.minio.MinioClient
import io.minio.PutObjectArgs
import io.minio.GetPresignedObjectUrlArgs
import io.minio.http.Method
import io.minio.BucketExistsArgs
import io.minio.MakeBucketArgs
import java.util.concurrent.TimeUnit

object MinioFactory {
    const val BUCKET = "sandeshx-media"

    val client: MinioClient by lazy {
        MinioClient.builder()
            .endpoint(System.getenv("MINIO_ENDPOINT") ?: "http://localhost:9000")
            .credentials(
                System.getenv("MINIO_ACCESS_KEY") ?: "sandeshx",
                System.getenv("MINIO_SECRET_KEY") ?: "sandeshx-secret"
            )
            .build()
    }

    /** ROOT CAUSE of "photos won't upload" / "DP won't set": presigned URLs were always
     *  generated using MINIO_ENDPOINT, which is the address the *backend* uses to reach
     *  MinIO (often "http://localhost:9000" or an internal Docker hostname like
     *  "http://minio:9000"). A phone can't resolve either of those — it needs a URL on
     *  the public internet. Set MINIO_PUBLIC_ENDPOINT to that public address (e.g.
     *  "https://media.yourdomain.com" or your MinIO/S3 provider's public endpoint);
     *  it falls back to MINIO_ENDPOINT so nothing breaks if it's unset, but uploads will
     *  keep failing on real devices until it's set to something phones can actually reach. */
    private val publicClient: MinioClient by lazy {
        val publicEndpoint = System.getenv("MINIO_PUBLIC_ENDPOINT") ?: System.getenv("MINIO_ENDPOINT") ?: "http://localhost:9000"
        MinioClient.builder()
            .endpoint(publicEndpoint)
            .credentials(
                System.getenv("MINIO_ACCESS_KEY") ?: "sandeshx",
                System.getenv("MINIO_SECRET_KEY") ?: "sandeshx-secret"
            )
            .build()
    }

    fun ensureBucket() {
        try {
            val exists = client.bucketExists(BucketExistsArgs.builder().bucket(BUCKET).build())
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build())
            }
        } catch (e: Exception) {
            println("[MinioFactory] MinIO not reachable at startup (image sharing will fail until it's configured): ${e.message}")
        }
    }

    /** Presigned PUT URL — client uploads the image directly to MinIO, backend never touches image bytes.
     *  Uses [publicClient] so the URL handed to the phone is one it can actually reach. */
    fun presignedUploadUrl(objectKey: String): String =
        publicClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.PUT)
                .bucket(BUCKET)
                .`object`(objectKey)
                .expiry(10, TimeUnit.MINUTES)
                .build()
        )

    fun presignedDownloadUrl(objectKey: String): String =
        publicClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(BUCKET)
                .`object`(objectKey)
                .expiry(1, TimeUnit.HOURS)
                .build()
        )
}
