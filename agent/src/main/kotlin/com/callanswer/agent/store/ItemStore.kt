package com.callanswer.agent.store

/** A DynamoDB attribute value. Only strings and numbers are used by the table. */
sealed interface AttrValue {
    data class S(val value: String) : AttrValue
    data class N(val value: Long) : AttrValue
}

data class ItemKey(val pk: String, val sk: String, val gsi1pk: String? = null, val gsi1sk: String? = null)

/** An item of the single table. [attrs] never contains `pk` or `sk`. */
data class StoreItem(val pk: String, val sk: String, val attrs: Map<String, AttrValue> = emptyMap()) {
    init {
        require("pk" !in attrs && "sk" !in attrs) { "pk and sk are not attributes" }
    }
    val key: ItemKey get() = ItemKey(pk, sk)
    fun str(name: String): String? = (attrs[name] as? AttrValue.S)?.value
    fun num(name: String): Long? = (attrs[name] as? AttrValue.N)?.value
}

sealed interface Condition {
    data object None : Condition
    /** The item does not exist (`attribute_not_exists(pk)`). */
    data object NotExists : Condition
    /** The item exists (`attribute_exists(pk)`). */
    data object Exists : Condition
    data class AttrEquals(val name: String, val value: AttrValue) : Condition
}

/** `SET`, `ADD` (numeric, creates the attribute at 0), and `REMOVE` actions. An update creates a missing item. */
data class ItemUpdate(
    val set: Map<String, AttrValue> = emptyMap(),
    val add: Map<String, Long> = emptyMap(),
    val remove: Set<String> = emptySet(),
)

sealed interface SortCondition {
    data object All : SortCondition
    data class BeginsWith(val prefix: String) : SortCondition
    data class Between(val low: String, val high: String) : SortCondition
}

enum class IndexName { BASE, GSI1 }

data class StoreQuery(
    val pk: String,
    val sort: SortCondition = SortCondition.All,
    val index: IndexName = IndexName.BASE,
    val forward: Boolean = true,
    val limit: Int? = null,
    val startAfter: ItemKey? = null,
    val consistent: Boolean = false,
)

data class QueryPage(val items: List<StoreItem>, val lastKey: ItemKey?)

sealed interface TransactOp {
    data class Put(val item: StoreItem, val condition: Condition = Condition.None) : TransactOp
    data class Delete(val key: ItemKey, val condition: Condition = Condition.None) : TransactOp
    data class Update(val key: ItemKey, val update: ItemUpdate, val condition: Condition = Condition.None) : TransactOp
}

class ConditionFailedException(message: String = "Condition failed") : RuntimeException(message)
class TransactionCancelledException(message: String = "Transaction cancelled") : RuntimeException(message)

/**
 * The only door to the table. `gsi1` items carry the attributes `gsi1pk` and `gsi1sk`. A conditional write that
 * fails throws [ConditionFailedException]; a transaction that is cancelled throws [TransactionCancelledException].
 */
interface ItemStore {
    suspend fun get(key: ItemKey, consistent: Boolean = true): StoreItem?
    suspend fun put(item: StoreItem, condition: Condition = Condition.None)
    /** Applies [update] and returns the item after the update. */
    suspend fun update(key: ItemKey, update: ItemUpdate, condition: Condition = Condition.None): StoreItem
    suspend fun delete(key: ItemKey, condition: Condition = Condition.None)
    suspend fun query(query: StoreQuery): QueryPage
    suspend fun transact(ops: List<TransactOp>)
}

/** Object storage for large documents. Keys are built by `Keys`. */
interface BlobStore {
    suspend fun put(key: String, bytes: ByteArray, contentType: String)
    suspend fun get(key: String): ByteArray?
    suspend fun delete(key: String)
    suspend fun listKeys(prefix: String): List<String>
}

/** Queries every page. */
suspend fun ItemStore.queryAll(query: StoreQuery): List<StoreItem> {
    val out = ArrayList<StoreItem>()
    var q = query
    while (true) {
        val page = query(q)
        out += page.items
        val last = page.lastKey ?: return out
        if (query.limit != null && out.size >= query.limit) return out.take(query.limit)
        q = q.copy(startAfter = last)
    }
}
