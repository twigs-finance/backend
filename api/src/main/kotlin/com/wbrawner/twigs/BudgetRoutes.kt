package com.wbrawner.twigs

import com.wbrawner.twigs.model.Budget
import com.wbrawner.twigs.model.Permission
import com.wbrawner.twigs.model.UserPermission
import com.wbrawner.twigs.storage.BudgetRepository
import com.wbrawner.twigs.storage.PermissionRepository
import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.util.pipeline.*

fun Application.budgetRoutes(
    budgetRepository: BudgetRepository,
    permissionRepository: PermissionRepository
) {
    suspend fun PipelineContext<Unit, ApplicationCall>.budgetWithPermission(
        budgetId: String,
        permission: Permission,
        block: suspend (Budget) -> Unit
    ) {
        val session = call.principal<Session>()!!
        val userPermission = permissionRepository.findAll(
            userId = session.userId,
            budgetIds = listOf(budgetId)
        ).firstOrNull()
        if (userPermission?.permission?.isNotAtLeast(permission) != true) {
            call.respond(HttpStatusCode.Forbidden)
            return
        }
        block(budgetRepository.findAllByIds(listOf(budgetId)).first())
    }

    routing {
        route("/api/budgets") {
            authenticate(optional = false) {
                get("/") {
                    val session = call.principal<Session>()!!
                    val budgetIds = permissionRepository.findAll(userId = session.userId).map { it.budgetId }
                    val budgets = budgetRepository.findAllByIds(budgetIds).map {
                        BudgetResponse(it, permissionRepository.findAll(budgetIds = listOf(it.id)))
                    }
                    call.respond(budgets)
                }

                get("/{id}") {
                    budgetWithPermission(budgetId = call.parameters["id"]!!, Permission.READ) { budget ->
                        val users = permissionRepository.findAll(budgetIds = listOf(budget.id))
                        call.respond(BudgetResponse(budget, users))
                    }
                }

                post("/{id}") {
                    val session = call.principal<Session>()!!
                    val request = call.receive<BudgetRequest>()
                    if (request.name.isNullOrBlank()) {
                        call.respond(HttpStatusCode.BadRequest, "Name cannot be empty or null")
                        return@post
                    }
                    val budget = budgetRepository.save(
                        Budget(
                            name = request.name,
                            description = request.description
                        )
                    )
                    val users = request.users?.map {
                        permissionRepository.save(
                            UserPermission(
                                budgetId = budget.id,
                                userId = it.user,
                                permission = it.permission
                            )
                        )
                    }?.toMutableSet() ?: mutableSetOf()
                    if (users.none { it.userId == session.userId }) {
                        users.add(
                            permissionRepository.save(
                                UserPermission(
                                    budgetId = budget.id,
                                    userId = session.userId,
                                    permission = Permission.OWNER
                                )
                            )
                        )
                    }
                    call.respond(BudgetResponse(budget, users))
                }

                put("/{id}") {
                    budgetWithPermission(call.parameters["id"]!!, Permission.MANAGE) { budget ->
                        val request = call.receive<BudgetRequest>()
                        val name = request.name ?: budget.name
                        val description = request.description ?: budget.description
                        val users = request.users?.map {
                            permissionRepository.save(UserPermission(budget.id, it.user, it.permission))
                        } ?: permissionRepository.findAll(budgetIds = listOf(budget.id))
                        permissionRepository.findAll(budgetIds = listOf(budget.id)).forEach {
                            if (it.permission != Permission.OWNER && users.none { userPermission -> userPermission.userId == it.userId }) {
                                permissionRepository.delete(it)
                            }
                        }
                        call.respond(
                            BudgetResponse(
                                budgetRepository.save(budget.copy(name = name, description = description)),
                                users
                            )
                        )
                    }
                }

                delete("/{id}") {
                    budgetWithPermission(budgetId = call.parameters["id"]!!, Permission.OWNER) { budget ->
                        budgetRepository.delete(budget)
                        call.respond(HttpStatusCode.NoContent)
                    }
                }
            }
        }
    }
}
