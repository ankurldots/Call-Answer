package com.callanswer.agent.store

import com.callanswer.core.model.Settings
import com.callanswer.core.tenant.E164
import com.callanswer.core.tenant.SettingsUpdate
import com.callanswer.core.tenant.UserId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class SettingsStoreTest : FunSpec({
    val user = UserId("user-a")
    fun fixture(): Pair<SettingsStore, DirectoryRepository> {
        val items = InMemoryItemStore()
        val dir = DirectoryRepository(items)
        return SettingsStore(items, dir) to dir
    }

    test("get returns defaults at version 0 when no item exists") {
        val (s, _) = fixture()
        val r = s.get(user)
        r.version shouldBe 0L
        r.document shouldBe Settings()
    }

    test("update from version 0 saves version 1, then 2") {
        val (s, _) = fixture()
        (s.update(user, 0, Settings(agentName = "A")) as SettingsUpdate.Saved).version shouldBe 1L
        (s.update(user, 1, Settings(agentName = "B")) as SettingsUpdate.Saved).version shouldBe 2L
        s.get(user).document.agentName shouldBe "B"
        s.freeze(user).version shouldBe 2L
    }

    test("stale or ahead expected version returns the stored document and version") {
        val (s, _) = fixture()
        s.update(user, 0, Settings(agentName = "A"))
        val stale = s.update(user, 0, Settings(agentName = "X"))
        stale.shouldBeInstanceOf<SettingsUpdate.Conflict>()
        stale.currentVersion shouldBe 1L
        stale.currentDocument.agentName shouldBe "A"
        s.update(user, 5, Settings(agentName = "Y")).shouldBeInstanceOf<SettingsUpdate.Conflict>()
        s.get(user).document.agentName shouldBe "A"
    }

    test("invalid fields are rejected without writing") {
        val (s, _) = fixture()
        s.update(user, 0, Settings(maxStallingSeconds = 5)) shouldBe SettingsUpdate.Invalid("maxStallingSeconds")
        s.update(user, 0, Settings(userMobile = "12345")) shouldBe SettingsUpdate.Invalid("userMobile")
        s.get(user).version shouldBe 0L
    }

    test("userMobile equal to an assigned number is rejected") {
        val (s, dir) = fixture()
        dir.assign(E164("+919876543210"), UserId("user-b"), 1L, "admin") shouldBe true
        s.update(user, 0, Settings(userMobile = "+919876543210")) shouldBe SettingsUpdate.Invalid("userMobile")
        s.update(user, 0, Settings(userMobile = "+919876543211")).shouldBeInstanceOf<SettingsUpdate.Saved>()
    }

    test("settings of different users are separate") {
        val (s, _) = fixture()
        s.update(user, 0, Settings(agentName = "A"))
        s.get(UserId("user-c")).version shouldBe 0L
    }
})
