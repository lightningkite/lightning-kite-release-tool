package com.lightningkite.release

import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.core.async.AsyncRequestBody
import software.amazon.awssdk.core.async.AsyncResponseTransformer
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.http.nio.netty.SdkEventLoopGroup
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3AsyncClient
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory

/** A Maven repository addressed by path, e.g. `com/lightningkite/services/basis/maven-metadata.xml`. */
interface MavenRepository : AutoCloseable {
    /** Where a browser reaches this repository's paths. */
    val url: String

    /** Every file's text (null where missing), fetched at once.  The tool only ever reads small text files. */
    fun readAll(paths: List<String>): Map<String, String?>

    fun read(path: String): String? = readAll(listOf(path)).getValue(path)

    /** Uploads straight from disk: artifacts can be large, and a first release stages thousands of them. */
    fun upload(files: Map<String, File>)

    /** Writes the small text files the tool generates itself. */
    fun put(files: Map<String, String>)
}

class DirectoryMavenRepository(val root: File) : MavenRepository {
    override val url: String get() = root.toURI().toString().trimEnd('/')
    override fun readAll(paths: List<String>) = paths.associateWith { root.resolve(it).takeIf { file -> file.isFile }?.readText() }
    override fun upload(files: Map<String, File>) = files.forEach { (path, file) -> file.copyTo(root.resolve(path), overwrite = true) }
    override fun put(files: Map<String, String>) = files.forEach { (path, text) -> root.resolve(path).apply { parentFile.mkdirs() }.writeText(text) }
    override fun close() {}
}

/**
 * [forWriting] needs the `lk` AWS profile and fails right away without it.  Reading falls back to anonymous
 * access, so `lk plan` and local releases work against the public bucket without a login.
 */
class S3MavenRepository(private val bucket: String, forWriting: Boolean) : MavenRepository {
    // Owned here rather than by the client, which would wait a fixed two seconds when shutting down its own.
    private val eventLoop = SdkEventLoopGroup.builder().build()
    private val client = S3AsyncClient.builder()
        .region(Region.US_WEST_2)
        .credentialsProvider(credentials(forWriting))
        // Thousands of small uploads: allow plenty in flight, and let queued ones wait instead of timing out.
        .httpClientBuilder(NettyNioAsyncHttpClient.builder().eventLoopGroup(eventLoop).maxConcurrency(64).connectionAcquisitionTimeout(Duration.ofMinutes(30)))
        .build()

    private fun credentials(required: Boolean): AwsCredentialsProvider {
        val lk = DefaultCredentialsProvider.builder().profileName("lk").build()
        val failure = runCatching { lk.resolveCredentials() }.exceptionOrNull() ?: return lk
        if (required) throw UserError("The `lk` AWS profile has no usable credentials, and uploading to s3://$bucket needs them: ${failure.message}")
        return AnonymousCredentialsProvider.create()
    }

    override val url: String get() = "https://$bucket.s3.amazonaws.com"

    override fun readAll(paths: List<String>): Map<String, String?> = paths
        .associateWith { client.getObject(GetObjectRequest.builder().bucket(bucket).key(it).build(), AsyncResponseTransformer.toBytes()) }
        .mapValues { (_, request) ->
            try {
                request.join().asUtf8String()
            } catch (e: CompletionException) {
                if (e.cause is NoSuchKeyException) null else throw e
            }
        }

    // The content type matters for the Dokka pages, which browsers must render rather than download.
    private fun request(path: String) = PutObjectRequest.builder().bucket(bucket).key(path)
        .contentType(Files.probeContentType(Path.of(path)) ?: "application/octet-stream").build()

    override fun upload(files: Map<String, File>) {
        files.map { (path, file) -> client.putObject(request(path), AsyncRequestBody.fromFile(file)) }.forEach { it.join() }
    }

    override fun put(files: Map<String, String>) {
        files.map { (path, text) -> client.putObject(request(path), AsyncRequestBody.fromString(text)) }.forEach { it.join() }
    }

    override fun close() {
        client.close()
        eventLoop.eventLoopGroup().shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly()
    }
}

fun mavenMetadataVersions(xml: String): List<String> {
    val versions = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream()).getElementsByTagName("version")
    return (0 until versions.length).map { versions.item(it).textContent.trim() }
}

/** [existing] (or nothing) with [version] added; `<latest>` and `<release>` name the newest plain release. */
fun mergeMavenMetadata(existing: String?, groupId: String, artifactId: String, version: String, now: ZonedDateTime): String {
    val versions = (existing?.let { mavenMetadataVersions(it) }.orEmpty() + version).distinct()
    val newest = versions.mapNotNull { Version.parseOrNull(it) }.maxOrNull()?.toString() ?: version
    val lastUpdated = now.withZoneSameInstant(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
    return """
        |<?xml version="1.0" encoding="UTF-8"?>
        |<metadata>
        |  <groupId>$groupId</groupId>
        |  <artifactId>$artifactId</artifactId>
        |  <versioning>
        |    <latest>$newest</latest>
        |    <release>$newest</release>
        |    <versions>
        |${versions.joinToString("\n") { "      <version>$it</version>" }}
        |    </versions>
        |    <lastUpdated>$lastUpdated</lastUpdated>
        |  </versioning>
        |</metadata>
        |""".trimMargin()
}

/** The checksum files Maven repositories keep next to every file, by extension. */
fun mavenChecksums(text: String): Map<String, String> = mapOf("md5" to "MD5", "sha1" to "SHA-1", "sha256" to "SHA-256", "sha512" to "SHA-512")
    .mapValues { (_, algorithm) -> MessageDigest.getInstance(algorithm).digest(text.toByteArray()).joinToString("") { "%02x".format(it) } }

/**
 * Uploads a Maven repository that Gradle published into [staging]: artifacts first, then each
 * `maven-metadata.xml` merged with the remote one, so a version is never listed before its files exist.
 */
fun MavenRepository.uploadStaged(staging: File) {
    val files = staging.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(staging).invariantSeparatorsPath }
    upload(files.filterKeys { !it.substringAfterLast('/').startsWith("maven-metadata") })
    val metadataPaths = files.keys.filter { it.endsWith("/maven-metadata.xml") }
    val existing = readAll(metadataPaths)
    put(metadataPaths.flatMap { path ->
        val directory = path.substringBeforeLast('/')
        val merged = mergeMavenMetadata(
            existing.getValue(path),
            groupId = directory.substringBeforeLast('/').replace('/', '.'),
            artifactId = directory.substringAfterLast('/'),
            version = mavenMetadataVersions(files.getValue(path).readText()).single(),
            now = ZonedDateTime.now(),
        )
        listOf(path to merged) + mavenChecksums(merged).map { (extension, sum) -> "$path.$extension" to sum }
    }.toMap())
}
