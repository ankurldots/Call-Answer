package com.callanswer.agent.store

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.dynamodb.DynamoDbClient
import software.amazon.awssdk.services.dynamodb.model.AttributeValue
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException
import software.amazon.awssdk.services.dynamodb.model.Delete
import software.amazon.awssdk.services.dynamodb.model.Put
import software.amazon.awssdk.services.dynamodb.model.ReturnValue
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException
import software.amazon.awssdk.services.dynamodb.model.Update
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest

/** [ItemStore] on a DynamoDB table. Only the `store` package touches the DynamoDB client. */
class DynamoItemStore(private val client: DynamoDbClient, private val table: String) : ItemStore {

    override suspend fun get(key: ItemKey, consistent: Boolean): StoreItem? = io {
        val r = client.getItem { it.tableName(table).key(keyMap(key)).consistentRead(consistent) }
        if (r.hasItem() && r.item().isNotEmpty()) fromMap(r.item()) else null
    }

    override suspend fun put(item: StoreItem, condition: Condition) = io {
        val c = ConditionParts.of(condition)
        try {
            client.putItem { b ->
                b.tableName(table).item(toMap(item))
                c.expression?.let { b.conditionExpression(it) }
                if (c.values.isNotEmpty()) b.expressionAttributeValues(c.values)
            }
        } catch (e: ConditionalCheckFailedException) {
            throw ConditionFailedException()
        }
        Unit
    }

    override suspend fun update(key: ItemKey, update: ItemUpdate, condition: Condition): StoreItem = io {
        val u = UpdateParts.of(update, ConditionParts.of(condition))
        try {
            val r = client.updateItem { b ->
                b.tableName(table).key(keyMap(key)).updateExpression(u.expression)
                    .returnValues(ReturnValue.ALL_NEW)
                if (u.names.isNotEmpty()) b.expressionAttributeNames(u.names)
                if (u.values.isNotEmpty()) b.expressionAttributeValues(u.values)
                u.condition?.let { b.conditionExpression(it) }
            }
            fromMap(r.attributes())
        } catch (e: ConditionalCheckFailedException) {
            throw ConditionFailedException()
        }
    }

    override suspend fun delete(key: ItemKey, condition: Condition) = io {
        val c = ConditionParts.of(condition)
        try {
            client.deleteItem { b ->
                b.tableName(table).key(keyMap(key))
                c.expression?.let { b.conditionExpression(it) }
                if (c.values.isNotEmpty()) b.expressionAttributeValues(c.values)
            }
        } catch (e: ConditionalCheckFailedException) {
            throw ConditionFailedException()
        }
        Unit
    }

    override suspend fun query(query: StoreQuery): QueryPage = io {
        val gsi = query.index == IndexName.GSI1
        val pkName = if (gsi) "gsi1pk" else "pk"
        val skName = if (gsi) "gsi1sk" else "sk"
        val values = HashMap<String, AttributeValue>()
        values[":pk"] = AttributeValue.fromS(query.pk)
        val expr = StringBuilder("#p = :pk")
        when (val c = query.sort) {
            SortCondition.All -> {}
            is SortCondition.BeginsWith -> { expr.append(" AND begins_with(#s, :s)"); values[":s"] = AttributeValue.fromS(c.prefix) }
            is SortCondition.Between -> { expr.append(" AND #s BETWEEN :lo AND :hi"); values[":lo"] = AttributeValue.fromS(c.low); values[":hi"] = AttributeValue.fromS(c.high) }
        }
        val names = mutableMapOf("#p" to pkName)
        if (query.sort != SortCondition.All) names["#s"] = skName
        val r = client.query { b ->
            b.tableName(table).keyConditionExpression(expr.toString())
                .expressionAttributeNames(names).expressionAttributeValues(values)
                .scanIndexForward(query.forward)
            if (gsi) b.indexName("gsi1") else b.consistentRead(query.consistent)
            query.limit?.let { b.limit(it) }
            query.startAfter?.let { b.exclusiveStartKey(startKey(it, gsi)) }
        }
        val last = if (r.hasLastEvaluatedKey() && r.lastEvaluatedKey().isNotEmpty()) {
            val m = r.lastEvaluatedKey()
            ItemKey(m.getValue("pk").s(), m.getValue("sk").s(), m["gsi1pk"]?.s(), m["gsi1sk"]?.s())
        } else null
        QueryPage(r.items().map(::fromMap), last)
    }

    override suspend fun transact(ops: List<TransactOp>) = io {
        val items = ops.map { op ->
            when (op) {
                is TransactOp.Put -> {
                    val c = ConditionParts.of(op.condition)
                    val b = Put.builder().tableName(table).item(toMap(op.item))
                    c.expression?.let { b.conditionExpression(it) }
                    if (c.values.isNotEmpty()) b.expressionAttributeValues(c.values)
                    TransactWriteItem.builder().put(b.build()).build()
                }
                is TransactOp.Delete -> {
                    val c = ConditionParts.of(op.condition)
                    val b = Delete.builder().tableName(table).key(keyMap(op.key))
                    c.expression?.let { b.conditionExpression(it) }
                    if (c.values.isNotEmpty()) b.expressionAttributeValues(c.values)
                    TransactWriteItem.builder().delete(b.build()).build()
                }
                is TransactOp.Update -> {
                    val u = UpdateParts.of(op.update, ConditionParts.of(op.condition))
                    val b = Update.builder().tableName(table).key(keyMap(op.key)).updateExpression(u.expression)
                    if (u.names.isNotEmpty()) b.expressionAttributeNames(u.names)
                    if (u.values.isNotEmpty()) b.expressionAttributeValues(u.values)
                    u.condition?.let { b.conditionExpression(it) }
                    TransactWriteItem.builder().update(b.build()).build()
                }
            }
        }
        try {
            client.transactWriteItems { it.transactItems(items) }
        } catch (e: TransactionCanceledException) {
            throw TransactionCancelledException()
        }
        Unit
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    private fun keyMap(k: ItemKey) = mapOf("pk" to AttributeValue.fromS(k.pk), "sk" to AttributeValue.fromS(k.sk))

    private fun startKey(k: ItemKey, gsi: Boolean): Map<String, AttributeValue> = buildMap {
        putAll(keyMap(k))
        if (gsi) {
            put("gsi1pk", AttributeValue.fromS(requireNotNull(k.gsi1pk)))
            put("gsi1sk", AttributeValue.fromS(requireNotNull(k.gsi1sk)))
        }
    }

    private fun toMap(item: StoreItem): Map<String, AttributeValue> = buildMap {
        put("pk", AttributeValue.fromS(item.pk))
        put("sk", AttributeValue.fromS(item.sk))
        item.attrs.forEach { (k, v) -> put(k, attr(v)) }
    }

    private fun fromMap(m: Map<String, AttributeValue>): StoreItem {
        val attrs = LinkedHashMap<String, AttrValue>()
        m.forEach { (k, v) ->
            if (k == "pk" || k == "sk") return@forEach
            when {
                v.s() != null -> attrs[k] = AttrValue.S(v.s())
                v.n() != null -> attrs[k] = AttrValue.N(v.n().toLong())
            }
        }
        return StoreItem(m.getValue("pk").s(), m.getValue("sk").s(), attrs)
    }

    private class ConditionParts(val expression: String?, val values: Map<String, AttributeValue>, val names: Map<String, String> = emptyMap()) {
        companion object {
            fun of(c: Condition): ConditionParts = when (c) {
                Condition.None -> ConditionParts(null, emptyMap())
                Condition.NotExists -> ConditionParts("attribute_not_exists(pk)", emptyMap())
                Condition.Exists -> ConditionParts("attribute_exists(pk)", emptyMap())
                is Condition.AttrEquals -> ConditionParts("#cond = :cond", mapOf(":cond" to attr(c.value)), mapOf("#cond" to c.name))
            }
        }
    }

    private class UpdateParts(val expression: String, val names: Map<String, String>, val values: Map<String, AttributeValue>, val condition: String?) {
        companion object {
            fun of(u: ItemUpdate, c: ConditionParts): UpdateParts {
                val names = LinkedHashMap<String, String>(c.names)
                val values = LinkedHashMap<String, AttributeValue>(c.values)
                var n = 0
                val sets = u.set.map { (k, v) -> val a = "#a${n}"; val p = ":v${n}"; n++; names[a] = k; values[p] = attr(v); "$a = $p" }
                val adds = u.add.map { (k, d) -> val a = "#a${n}"; val p = ":v${n}"; n++; names[a] = k; values[p] = AttributeValue.fromN(d.toString()); "$a $p" }
                val removes = u.remove.map { k -> val a = "#a${n}"; n++; names[a] = k; a }
                val parts = buildList {
                    if (sets.isNotEmpty()) add("SET " + sets.joinToString(", "))
                    if (adds.isNotEmpty()) add("ADD " + adds.joinToString(", "))
                    if (removes.isNotEmpty()) add("REMOVE " + removes.joinToString(", "))
                }
                require(parts.isNotEmpty()) { "Empty update" }
                return UpdateParts(parts.joinToString(" "), names, values, c.expression)
            }
        }
    }

    private companion object {
        fun attr(v: AttrValue): AttributeValue = when (v) {
            is AttrValue.S -> AttributeValue.fromS(v.value)
            is AttrValue.N -> AttributeValue.fromN(v.value.toString())
        }
    }
}

/** [BlobStore] on S3. Only the `store` package touches the S3 client. */
class S3BlobStore(private val client: S3Client, private val bucket: String) : BlobStore {
    override suspend fun put(key: String, bytes: ByteArray, contentType: String) = withContext(Dispatchers.IO) {
        client.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType(contentType).build(), RequestBody.fromBytes(bytes))
        Unit
    }

    override suspend fun get(key: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            client.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(key).build()).asByteArray()
        } catch (e: NoSuchKeyException) {
            null
        }
    }

    override suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
        Unit
    }

    override suspend fun listKeys(prefix: String): List<String> = withContext(Dispatchers.IO) {
        client.listObjectsV2Paginator(ListObjectsV2Request.builder().bucket(bucket).prefix(prefix).build())
            .contents().map { it.key() }
    }
}
