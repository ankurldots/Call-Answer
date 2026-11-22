package com.callanswer.agent.store

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import java.io.File

/** Build rule: only the `store` package may use the DynamoDB or S3 clients. */
class StoreBoundaryTest : FunSpec({
    test("code outside the store package does not use the DynamoDB or S3 clients") {
        val root = File("src/main/kotlin")
        val forbidden = Regex("""software\.amazon\.awssdk\.services\.(dynamodb|s3)\b""")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.parentFile.name != "store" }
            .filter { f -> forbidden.containsMatchIn(f.readText()) }
            .map { it.path }.toList()
        offenders.shouldBeEmpty()
    }
})
