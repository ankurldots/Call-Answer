package com.callanswer.agent.tenant

import com.callanswer.agent.store.AssignResult
import com.callanswer.agent.store.AwsStores
import com.callanswer.agent.store.ItemStore
import com.callanswer.agent.store.NumberAssignmentService
import com.callanswer.agent.store.TenantResolver
import com.callanswer.agent.store.UserScopedRepository
import com.callanswer.agent.store.InMemoryBlobStore
import com.callanswer.core.tenant.UserId
import kotlinx.coroutines.runBlocking
import java.io.PrintStream

/** Creates a Cognito account. The production version runs `aws cognito-idp admin-create-user`. */
fun interface CognitoAdmin {
    fun createUser(userPoolId: String, email: String): Int
}

/** Runs the AWS CLI with the same profile and region, so no credentials pass through this program. */
class AwsCliCognitoAdmin(private val profile: String, private val region: String) : CognitoAdmin {
    override fun createUser(userPoolId: String, email: String): Int =
        ProcessBuilder(command(profile, region, userPoolId, email)).inheritIO().start().waitFor()

    companion object {
        fun command(profile: String, region: String, userPoolId: String, email: String) = listOf(
            "aws", "cognito-idp", "admin-create-user", "--user-pool-id", userPoolId, "--username", email,
            "--user-attributes", "Name=email,Value=$email", "Name=email_verified,Value=true",
            "--profile", profile, "--region", region,
        )
    }
}

/**
 * Admin command line over [NumberAssignmentService]. [run] returns the exit code: 0 success, 1 failure or conflict, 2 usage.
 * With `--dry-run` nothing is read or written and the planned action is printed.
 */
class AdminCli(
    private val service: NumberAssignmentService?,
    private val store: ItemStore?,
    private val cognito: CognitoAdmin?,
    private val out: PrintStream = System.out,
    private val err: PrintStream = System.err,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    fun run(args: List<String>): Int {
        val opts = Options.parse(args) ?: return usage()
        val c = opts.rest
        if (c.isEmpty()) return usage()
        if (opts.dryRun) return dry(opts)
        val svc = service ?: return fail("no store configured")
        return try {
            runBlocking { execute(c, svc, opts) }
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "invalid argument")
        }
    }

    private suspend fun execute(c: List<String>, svc: NumberAssignmentService, opts: Options): Int = when (c[0]) {
        "assign" -> if (c.size != 3) usage() else when (val r = svc.assign(c[1], UserId(c[2]), "cli")) {
            is AssignResult.Assigned -> ok("assigned ${r.e164.value} to ${c[2]}")
            AssignResult.InvalidNumber -> fail("invalid number")
            AssignResult.NumberIsUserMobile -> fail("number equals a User_Mobile")
            AssignResult.Conflict -> fail("conflict: the number or the user is already assigned")
        }
        "unassign" -> if (c.size != 2) usage() else if (svc.unassign(UserId(c[1]))) ok("unassigned ${c[1]}") else fail("user has no assignment")
        "deactivate" -> if (c.size != 2) usage() else if (svc.deactivate(UserId(c[1]))) ok("deactivated ${c[1]}") else fail("user has no profile")
        "list" -> if (c.size != 1) usage() else {
            val all = svc.list()
            if (all.isEmpty()) out.println("no assignments")
            all.forEach { out.println("${it.e164.value}\t${it.userId.sub}\tassignedAt=${it.assignedAtMs}\tassignedBy=${it.assignedBy}") }
            0
        }
        "bootstrap-user" -> if (c.size != 2) usage() else {
            val s = store ?: return fail("no store configured")
            val created = UserScopedRepository(UserId(c[1]), s, InMemoryBlobStore()).createProfileIfAbsent(nowMs())
            ok(if (created) "profile created for ${c[1]}" else "profile already exists for ${c[1]}")
        }
        "create-user" -> if (c.size != 2) usage() else {
            val pool = opts.userPoolId ?: return fail("--user-pool-id or COGNITO_USER_POOL_ID is required")
            val code = (cognito ?: return fail("no Cognito client configured")).createUser(pool, c[1])
            if (code == 0) 0 else fail("aws cognito-idp admin-create-user exited with $code")
        }
        else -> usage()
    }

    private fun dry(o: Options): Int {
        val c = o.rest
        val line = when (c[0]) {
            "assign" -> c.size == 3 && true
            "unassign", "deactivate", "bootstrap-user" -> c.size == 2
            "list" -> c.size == 1
            "create-user" -> c.size == 2
            else -> false
        }
        if (!line) return usage()
        if (c[0] == "create-user") {
            val pool = o.userPoolId ?: return fail("--user-pool-id or COGNITO_USER_POOL_ID is required")
            out.println("DRY RUN: " + AwsCliCognitoAdmin.command(o.profile, o.region, pool, c[1]).joinToString(" "))
        } else {
            out.println("DRY RUN: would run '${c.joinToString(" ")}' with profile=${o.profile} region=${o.region}; nothing was changed")
        }
        return 0
    }

    private fun ok(msg: String): Int { out.println(msg); return 0 }
    private fun fail(msg: String): Int { err.println("error: $msg"); return 1 }
    private fun usage(): Int {
        err.println(USAGE)
        return 2
    }

    data class Options(val profile: String, val region: String, val dryRun: Boolean, val userPoolId: String?, val rest: List<String>) {
        companion object {
            fun parse(args: List<String>): Options? {
                var profile = "ldots"
                var region = "ap-northeast-1"
                var dry = false
                var pool: String? = System.getenv("COGNITO_USER_POOL_ID")
                val rest = ArrayList<String>()
                var i = 0
                while (i < args.size) {
                    when (val a = args[i]) {
                        "--profile" -> profile = args.getOrNull(++i) ?: return null
                        "--region" -> region = args.getOrNull(++i) ?: return null
                        "--user-pool-id" -> pool = args.getOrNull(++i) ?: return null
                        "--dry-run" -> dry = true
                        else -> rest += a
                    }
                    i++
                }
                return Options(profile, region, dry, pool, rest)
            }
        }
    }

    companion object {
        const val USAGE = "usage: callagent-admin [--profile ldots] [--region ap-northeast-1] [--dry-run] " +
            "(assign <number> <sub> | unassign <sub> | deactivate <sub> | list | bootstrap-user <sub> | create-user <email> [--user-pool-id ID])"
    }
}

fun main(args: Array<String>) {
    val opts = AdminCli.Options.parse(args.toList())
    if (opts == null || opts.dryRun) {
        System.exit(AdminCli(null, null, null).run(args.toList()))
    }
    val table = System.getenv("AGENT_TABLE") ?: "call-answer"
    val store = AwsStores.itemStore(opts!!.region, table, opts.profile)
    val service = NumberAssignmentService(store, TenantResolver(store))
    System.exit(AdminCli(service, store, AwsCliCognitoAdmin(opts.profile, opts.region)).run(args.toList()))
}
