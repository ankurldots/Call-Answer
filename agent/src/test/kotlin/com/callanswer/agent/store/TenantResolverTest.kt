package com.callanswer.agent.store

import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.Keys
import com.callanswer.core.tenant.UserId
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.core.spec.style.FunSpec

private class Env(val store: InMemoryItemStore, val resolver: TenantResolver, val svc: NumberAssignmentService)

private fun env(clock: () -> Long): Env {
    val store = InMemoryItemStore()
    val resolver = TenantResolver(store, UnassignedCallLog(store, clock), clock)
    return Env(store, resolver, NumberAssignmentService(store, resolver, clock))
}

private suspend fun profile(e: Env, u: UserId) { UserScopedRepository(u, e.store, InMemoryBlobStore()).createProfileIfAbsent(1) }

class TenantResolverTest : FunSpec({
    val alice = UserId("alice-sub")
    val bob = UserId("bob-sub")
    val n1 = E164("+919800000001")
    var now = 1_700_000_000_000L

    test("assignResolveVariantsCacheAndDeactivate") {
        val e = env { now }
        profile(e, alice); profile(e, bob)
        e.svc.assign("+91 98000-00001", alice, "admin") shouldBe AssignResult.Assigned(n1)
        e.svc.assign("9800000001", bob, "admin") shouldBe AssignResult.Conflict
        e.svc.assign("+919800000002", alice, "admin") shouldBe AssignResult.Conflict
        e.svc.assign("junk", bob, "admin") shouldBe AssignResult.InvalidNumber
        for (v in listOf("+919800000001", "09800000001", "919800000001", "9800000001")) {
            e.resolver.resolve(v) shouldBe TenantResolution.Resolved(alice, n1)
        }
        e.resolver.resolve("+919811111111") shouldBe TenantResolution.OutOfService(OutOfServiceReason.UNASSIGNED)
        e.resolver.resolve("abc") shouldBe TenantResolution.OutOfService(OutOfServiceReason.INVALID_NUMBER)

        // negative entry is cached for 30 s, and Admin writes clear it
        e.svc.assign("+919811111111", bob, "admin").shouldBeInstanceOf<AssignResult.Assigned>()
        e.resolver.resolve("+919811111111") shouldBe TenantResolution.Resolved(bob, E164("+919811111111"))

        // positive entry survives a direct write until expiry
        e.resolver.resolve("+919800000001").shouldBeInstanceOf<TenantResolution.Resolved>()
        e.store.update(ItemKey(Keys.user(alice), Keys.PROFILE_SK), ItemUpdate(set = mapOf("status" to AttrValue.S("DEACTIVATED"))))
        e.resolver.resolve("+919800000001").shouldBeInstanceOf<TenantResolution.Resolved>()
        now += 30_001
        e.resolver.resolve("+919800000001") shouldBe TenantResolution.OutOfService(OutOfServiceReason.USER_INACTIVE)

        e.svc.deactivate(bob) shouldBe true
        e.resolver.resolve("+919811111111") shouldBe TenantResolution.OutOfService(OutOfServiceReason.USER_INACTIVE)
        e.svc.unassign(alice) shouldBe true
        e.resolver.resolve("+919800000001") shouldBe TenantResolution.OutOfService(OutOfServiceReason.UNASSIGNED)
    }

    test("rejectsUserMobileAndRecordsFlowConfirmation") {
        val e = env { now }
        profile(e, alice); profile(e, bob)
        e.store.put(StoreItem(Keys.user(bob), Keys.SETTINGS_SK, mapOf("version" to AttrValue.N(1), "doc" to AttrValue.S("""{"userMobile":"+919822222222"}"""))))
        e.svc.assign("9822222222", alice, "admin") shouldBe AssignResult.NumberIsUserMobile
        e.svc.assign("+919800000001", alice, "admin") shouldBe AssignResult.Assigned(n1)
        e.svc.flowConfirmation(n1) shouldBe FlowConfirmation(false, null, null)
        e.svc.confirmFlow(n1, E164("+919822222222")) shouldBe true
        e.svc.flowConfirmation(n1) shouldBe FlowConfirmation(true, E164("+919822222222"), now)
    }

    test("hintNeverSelectsAndOnlyExposesBooleans") {
        val e = env { now }
        val mobile = E164("+919822222222")
        e.resolver.hint(null, mobile) shouldBe ForwardedFromHint(false, false)
        e.resolver.hint("09822222222", mobile) shouldBe ForwardedFromHint(true, true)
        e.resolver.hint("+919833333333", mobile) shouldBe ForwardedFromHint(true, false)
    }

    test("unassignedLogHasToAndReasonOnly") {
        val e = env { now }
        e.resolver.resolve("+919811111111")
        val items = e.store.snapshot().filter { it.pk.startsWith(UnassignedCallLog.PK_PREFIX) }
        items.size shouldBe 1
        items[0].str("to") shouldBe "+919811111111"
        items[0].str("reason") shouldBe "UNASSIGNED"
        items[0].attrs.keys shouldBe setOf("to", "reason", "atMs")
    }
})
