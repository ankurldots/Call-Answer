package com.callanswer.agent.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** In-memory [ItemStore] with the conditional-write, transaction, and key-condition behavior of DynamoDB. */
class InMemoryItemStore : ItemStore {
    private val lock = Mutex()
    private val items = HashMap<Pair<String, String>, StoreItem>()

    /** Number of write calls ([put], [update], [delete], [transact]), for tests. */
    @Volatile var writeCount: Int = 0
    /** Test hook: runs before each write and may throw to simulate an outage. */
    @Volatile var beforeWrite: (() -> Unit)? = null

    override suspend fun get(key: ItemKey, consistent: Boolean): StoreItem? = lock.withLock { items[key.pk to key.sk] }

    override suspend fun put(item: StoreItem, condition: Condition) = lock.withLock {
        writeCount++
        beforeWrite?.invoke()
        check(items[item.pk to item.sk], condition)
        items[item.pk to item.sk] = item
        Unit
    }

    override suspend fun update(key: ItemKey, update: ItemUpdate, condition: Condition): StoreItem = lock.withLock {
        writeCount++
        beforeWrite?.invoke()
        check(items[key.pk to key.sk], condition)
        applyUpdate(key, update).also { items[key.pk to key.sk] = it }
    }

    override suspend fun delete(key: ItemKey, condition: Condition) = lock.withLock {
        writeCount++
        beforeWrite?.invoke()
        check(items[key.pk to key.sk], condition)
        items.remove(key.pk to key.sk)
        Unit
    }

    override suspend fun query(query: StoreQuery): QueryPage = lock.withLock {
        val gsi = query.index == IndexName.GSI1
        fun order(i: StoreItem): List<String> = if (gsi) listOf(i.str("gsi1sk")!!, i.pk, i.sk) else listOf(i.sk)
        val matching = items.values.filter { i ->
            val pk = if (gsi) i.str("gsi1pk") else i.pk
            val sk = if (gsi) i.str("gsi1sk") else i.sk
            pk == query.pk && sk != null && when (val c = query.sort) {
                SortCondition.All -> true
                is SortCondition.BeginsWith -> sk.startsWith(c.prefix)
                is SortCondition.Between -> sk >= c.low && sk <= c.high
            }
        }.sortedWith { a, b -> compareOrder(order(a), order(b)) }
            .let { if (query.forward) it else it.reversed() }
        val after = query.startAfter
        val rest = if (after == null) matching else {
            val probe = if (gsi) listOf(after.gsi1sk ?: "", after.pk, after.sk) else listOf(after.sk)
            matching.filter { i ->
                val c = compareOrder(order(i), probe)
                if (query.forward) c > 0 else c < 0
            }
        }
        val limit = query.limit
        if (limit != null && rest.size > limit) {
            val page = rest.take(limit)
            QueryPage(page, page.last().let { ItemKey(it.pk, it.sk, it.str("gsi1pk"), it.str("gsi1sk")) })
        } else QueryPage(rest, null)
    }

    override suspend fun transact(ops: List<TransactOp>) = lock.withLock {
        writeCount++
        beforeWrite?.invoke()
        val snapshot = HashMap(items)
        try {
            for (op in ops) when (op) {
                is TransactOp.Put -> { check(items[op.item.pk to op.item.sk], op.condition); items[op.item.pk to op.item.sk] = op.item }
                is TransactOp.Delete -> { check(items[op.key.pk to op.key.sk], op.condition); items.remove(op.key.pk to op.key.sk) }
                is TransactOp.Update -> { check(items[op.key.pk to op.key.sk], op.condition); items[op.key.pk to op.key.sk] = applyUpdate(op.key, op.update) }
            }
        } catch (e: ConditionFailedException) {
            items.clear(); items.putAll(snapshot)
            throw TransactionCancelledException()
        }
    }

    /** All items, for tests. */
    suspend fun snapshot(): List<StoreItem> = lock.withLock { items.values.toList() }

    private fun compareOrder(x: List<String>, y: List<String>): Int {
        for (n in 0 until minOf(x.size, y.size)) { val r = x[n].compareTo(y[n]); if (r != 0) return r }
        return 0
    }

    private fun applyUpdate(key: ItemKey, update: ItemUpdate): StoreItem {
        val attrs = LinkedHashMap(items[key.pk to key.sk]?.attrs ?: emptyMap())
        update.set.forEach { (k, v) -> attrs[k] = v }
        update.add.forEach { (k, d) -> attrs[k] = AttrValue.N(((attrs[k] as? AttrValue.N)?.value ?: 0L) + d) }
        update.remove.forEach { attrs.remove(it) }
        return StoreItem(key.pk, key.sk, attrs)
    }

    private fun check(existing: StoreItem?, condition: Condition) {
        val ok = when (condition) {
            Condition.None -> true
            Condition.NotExists -> existing == null
            Condition.Exists -> existing != null
            is Condition.AttrEquals -> existing?.attrs?.get(condition.name) == condition.value
            is Condition.AttrAbsentOrLess -> {
                val cur = (existing?.attrs?.get(condition.name) as? AttrValue.N)?.value
                cur == null || cur < condition.value
            }
        }
        if (!ok) throw ConditionFailedException()
    }
}

class InMemoryBlobStore : BlobStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    override suspend fun put(key: String, bytes: ByteArray, contentType: String) { map[key] = bytes }
    override suspend fun get(key: String): ByteArray? = map[key]
    override suspend fun delete(key: String) { map.remove(key) }
    override suspend fun listKeys(prefix: String): List<String> = map.keys.filter { it.startsWith(prefix) }.sorted()
}
