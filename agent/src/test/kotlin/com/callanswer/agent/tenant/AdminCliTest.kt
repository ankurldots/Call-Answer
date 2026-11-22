package com.callanswer.agent.tenant

import com.callanswer.agent.store.InMemoryItemStore
import com.callanswer.agent.store.NumberAssignmentService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class AdminCliTest : FunSpec({
    class H {
        val store = InMemoryItemStore()
        val o = ByteArrayOutputStream()
        val e = ByteArrayOutputStream()
        val calls = mutableListOf<Pair<String, String>>()
        val cli = AdminCli(NumberAssignmentService(store), store, { p, m -> calls += p to m; 0 }, PrintStream(o), PrintStream(e)) { 1L }
        fun run(vararg a: String) = cli.run(a.toList())
    }

    test("bootstrap, assign, list, conflict, unassign, deactivate") {
        val h = H()
        h.run("bootstrap-user", "alice") shouldBe 0
        h.run("bootstrap-user", "alice") shouldBe 0
        h.o.toString() shouldContain "already exists"
        h.run("assign", "+919800000001", "alice") shouldBe 0
        h.run("assign", "+919800000002", "alice") shouldBe 1
        h.run("assign", "junk", "alice") shouldBe 1
        h.run("list") shouldBe 0
        h.o.toString() shouldContain "+919800000001\talice"
        h.run("deactivate", "alice") shouldBe 0
        h.run("deactivate", "nobody") shouldBe 1
        h.run("unassign", "alice") shouldBe 0
        h.run("unassign", "alice") shouldBe 1
    }

    test("dry run changes nothing and create-user delegates") {
        val h = H()
        h.run("--dry-run", "assign", "+919800000001", "alice") shouldBe 0
        h.o.toString() shouldContain "DRY RUN"
        h.run("list") shouldBe 0
        h.o.toString() shouldContain "no assignments"
        h.run("create-user", "a@b.c", "--user-pool-id", "pool1") shouldBe 0
        h.calls shouldBe listOf("pool1" to "a@b.c")
        h.run("bogus") shouldBe 2
    }

    test("aws cli command uses the profile and region and no credentials") {
        val c = AwsCliCognitoAdmin.command("ldots", "ap-northeast-1", "p", "a@b.c")
        c.joinToString(" ") shouldContain "--profile ldots --region ap-northeast-1"
    }
})
