package com.callanswer.agent.store

import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.s3.S3Client

/** Builds the production [ItemStore] and [BlobStore] so that no code outside this package touches the AWS clients. */
object AwsStores {
    fun itemStore(region: String, table: String, profile: String? = null): ItemStore =
        DynamoItemStore(
            DynamoDbClient.builder().region(Region.of(region)).apply {
                if (profile != null) credentialsProvider(ProfileCredentialsProvider.create(profile))
            }.build(),
            table,
        )

    fun blobStore(region: String, bucket: String): BlobStore =
        S3BlobStore(S3Client.builder().region(Region.of(region)).build(), bucket)
    fun recordingStore(region: String, bucket: String, blobs: BlobStore): RecordingStore =
        RecordingStore(blobs, S3RecordingSigner(software.amazon.awssdk.services.s3.presigner.S3Presigner.builder().region(Region.of(region)).build(), bucket))
}
