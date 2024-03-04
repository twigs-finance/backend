package com.wbrawner.twigs.server

import at.favre.lib.crypto.bcrypt.BCrypt
import ch.qos.logback.classic.Level
import com.wbrawner.twigs.*
import com.wbrawner.twigs.db.*
import com.wbrawner.twigs.model.Session
import com.wbrawner.twigs.storage.*
import com.wbrawner.twigs.web.webRoutes
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.response.*
import io.ktor.server.sessions.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

fun main() {
    embeddedServer(
        CIO,
        port = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        module = Application::module
    ).start(wait = true)
}

private const val DATABASE_VERSION = 3

fun Application.module() {
    val dbType = System.getenv("TWIGS_DB_TYPE") ?: "sqlite"
    val dbHost = System.getenv("TWIGS_DB_HOST") ?: "localhost"
    val dbPort = System.getenv("TWIGS_DB_PORT") ?: "5432"
    val dbName = System.getenv("TWIGS_DB_NAME") ?: "twigs"
    val dbUser = System.getenv("TWIGS_DB_USER") ?: "twigs"
    val dbPass = System.getenv("TWIGS_DB_PASS") ?: "twigs"
    val jdbcUrl = when (dbType) {
        "postgresql" -> {
            "jdbc:$dbType://$dbHost:$dbPort/$dbName?stringtype=unspecified"
        }

        "sqlite" -> {
            Class.forName("org.sqlite.JDBC")
            "jdbc:$dbType:$dbName"
        }

        else -> {
            throw RuntimeException("Unsupported DB type: $dbType")
        }
    }
    (LoggerFactory.getLogger("com.zaxxer.hikari") as ch.qos.logback.classic.Logger).level = Level.DEBUG
    HikariDataSource(HikariConfig().apply {
        setJdbcUrl(jdbcUrl)
        username = dbUser
        password = dbPass
    }).also {
        val metadataRepository = JdbcMetadataRepository(it)
        val metadata = runBlocking {
            val metadata = (metadataRepository.findAll().firstOrNull() ?: DatabaseMetadata())
            var version = metadata.version
            while (currentCoroutineContext().isActive && version++ < DATABASE_VERSION) {
                metadataRepository.runMigration(version)
                metadataRepository.save(metadata.copy(version = version))
            }
            if (metadata.salt.isBlank()) {
                metadataRepository.save(
                    metadata.copy(
                        salt = System.getenv("TWIGS_PW_SALT")
                            ?: randomString(16)
                    )
                )
            } else {
                metadata
            }
        }
        moduleWithDependencies(
            emailService = SmtpEmailService(
                from = System.getenv("TWIGS_SMTP_FROM"),
                host = System.getenv("TWIGS_SMTP_HOST"),
                port = System.getenv("TWIGS_SMTP_PORT")?.toIntOrNull(),
                username = System.getenv("TWIGS_SMTP_USER"),
                password = System.getenv("TWIGS_SMTP_PASS"),
            ),
            metadataRepository = JdbcMetadataRepository(it),
            budgetRepository = JdbcBudgetRepository(it),
            categoryRepository = JdbcCategoryRepository(it),
            passwordResetRepository = JdbcPasswordResetRepository(it),
            passwordHasher = { password ->
                String(BCrypt.withDefaults().hash(10, metadata.salt.toByteArray(), password.toByteArray()))
            },
            permissionRepository = JdbcPermissionRepository(it),
            recurringTransactionRepository = JdbcRecurringTransactionRepository(it),
            sessionRepository = JdbcSessionRepository(it),
            transactionRepository = JdbcTransactionRepository(it),
            userRepository = JdbcUserRepository(it)
        )
    }
}

fun Application.moduleWithDependencies(
    emailService: EmailService,
    metadataRepository: MetadataRepository,
    budgetRepository: BudgetRepository,
    categoryRepository: CategoryRepository,
    passwordResetRepository: PasswordResetRepository,
    passwordHasher: PasswordHasher,
    permissionRepository: PermissionRepository,
    recurringTransactionRepository: RecurringTransactionRepository,
    sessionRepository: SessionRepository,
    transactionRepository: TransactionRepository,
    userRepository: UserRepository
) {
    install(CallLogging)
    install(Authentication) {
        session<Session> {
            challenge {
                call.respond(HttpStatusCode.Unauthorized)
            }
            validate { session ->
                application.environment.log.info("Validating session")
                val storedSession = sessionRepository.findAll(session.token)
                    .firstOrNull()
                if (storedSession == null) {
                    application.environment.log.info("Did not find session!")
                    return@validate null
                } else {
                    application.environment.log.info("Found session!")
                }
                return@validate if (twoWeeksFromNow.isAfter(storedSession.expiration)) {
                    sessionRepository.save(storedSession.copy(expiration = twoWeeksFromNow))
                } else {
                    null
                }
            }
        }
    }
    install(Sessions) {
        header<Session>("Authorization") {
            serializer = object : SessionSerializer<Session> {
                override fun deserialize(text: String): Session {
                    this@moduleWithDependencies.environment.log.info("Deserializing session!")
                    return Session(token = text.substringAfter("Bearer "))
                }

                override fun serialize(session: Session): String = session.token
            }
        }
    }
    install(ContentNegotiation) {
        json(json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
            isLenient = true
            allowSpecialFloatingPointValues = true
            allowStructuredMapKeys = true
            prettyPrint = false
            useArrayPolymorphism = true
        })
    }
    install(CORS) {
        allowHost("twigs.wbrawner.com", listOf("http", "https")) // TODO: Make configurable
        allowHost("localhost:4200", listOf("http", "https"))     // TODO: Make configurable
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.Accept)
        allowHeader(HttpHeaders.AcceptEncoding)
        allowHeader(HttpHeaders.AcceptLanguage)
        allowHeader(HttpHeaders.Connection)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Host)
        allowHeader(HttpHeaders.Origin)
        allowHeader(HttpHeaders.AccessControlRequestHeaders)
        allowHeader(HttpHeaders.AccessControlRequestMethod)
        allowHeader("Sec-Fetch-Dest")
        allowHeader("Sec-Fetch-Mode")
        allowHeader("Sec-Fetch-Site")
        allowHeader("sec-ch-ua")
        allowHeader("sec-ch-ua-mobile")
        allowHeader("sec-ch-ua-platform")
        allowHeader(HttpHeaders.UserAgent)
        allowHeader("DNT")
        allowCredentials = true
    }
    budgetRoutes(budgetRepository, permissionRepository)
    categoryRoutes(categoryRepository, permissionRepository)
    recurringTransactionRoutes(recurringTransactionRepository, permissionRepository)
    transactionRoutes(transactionRepository, permissionRepository)
    userRoutes(emailService, passwordResetRepository, permissionRepository, sessionRepository, userRepository, passwordHasher)
    webRoutes()
    launch {
        val jobs = listOf(
            SessionCleanupJob(sessionRepository),
            RecurringTransactionProcessingJob(recurringTransactionRepository, transactionRepository)
        )
        while (currentCoroutineContext().isActive) {
            jobs.forEach { it.run() }
            delay(TimeUnit.HOURS.toMillis(1))
        }
    }
}

interface Job {
    suspend fun run()
}
