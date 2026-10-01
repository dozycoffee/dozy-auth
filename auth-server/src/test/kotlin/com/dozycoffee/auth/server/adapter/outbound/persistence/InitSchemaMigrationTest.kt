package com.dozycoffee.auth.server.adapter.outbound.persistence

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * V1 마이그레이션이 만든 스키마를 실제 PostgreSQL에서 확인합니다 (docs/data-model.md).
 * 각 테스트는 트랜잭션 안에서 실행하고 되돌립니다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InitSchemaMigrationTest {
    private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:18"))
    private lateinit var connection: Connection

    @BeforeAll
    fun migrate() {
        postgres.start()
        Flyway
            .configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .load()
            .migrate()
        connection = DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password)
    }

    @AfterAll
    fun stop() {
        connection.close()
        postgres.stop()
    }

    @Test
    fun `빈 DB에서 마이그레이션이 성공하고 직원 범위의 테이블만 생김`() {
        val tables = strings("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")

        val expected =
            setOf(
                "principal",
                "employee_profile",
                "system_client",
                "password_credential",
                "verification",
                "audience",
                "role",
                "principal_role",
                "refresh_session",
                "audit_log",
                "flyway_schema_history",
            )
        assertEquals(expected, tables.toSet())
    }

    @Test
    fun `principal id는 DB가 UUIDv7으로 만듦`() {
        val id = insertPrincipal()

        assertEquals(7, id.version())
    }

    @Test
    fun `컬럼 이름과 타입이 데이터 모델 문서와 같음`() {
        assertColumns(
            "principal",
            "id:uuid",
            "type:varchar",
            "status:varchar",
            "failed_login_count:int4",
            "locked_until:timestamptz",
            "created_at:timestamptz",
            "updated_at:timestamptz",
            "deactivated_at:timestamptz",
        )
        assertColumns(
            "employee_profile",
            "principal_id:uuid",
            "email:varchar",
            "name:varchar",
            "phone:varchar",
            "address:varchar",
            "created_at:timestamptz",
            "updated_at:timestamptz",
        )
        assertColumns(
            "system_client",
            "principal_id:uuid",
            "client_id:varchar",
            "client_secret_hash:bpchar",
            "name:varchar",
            "secret_rotated_at:timestamptz",
            "created_at:timestamptz",
        )
        assertColumns(
            "password_credential",
            "principal_id:uuid",
            "password_hash:varchar",
            "changed_at:timestamptz",
            "created_at:timestamptz",
        )
        assertColumns(
            "verification",
            "id:int8",
            "principal_id:uuid",
            "purpose:varchar",
            "method:varchar",
            "target:varchar",
            "token_hash:bpchar",
            "payload:jsonb",
            "attempt_count:int4",
            "max_attempts:int4",
            "expires_at:timestamptz",
            "consumed_at:timestamptz",
            "invalidated_at:timestamptz",
            "created_at:timestamptz",
        )
        assertColumns("audience", "id:int8", "code:varchar", "name:varchar", "description:varchar", "created_at:timestamptz")
        assertColumns(
            "role",
            "id:int8",
            "audience_id:int8",
            "code:varchar",
            "name:varchar",
            "description:varchar",
            "is_system:bool",
            "created_by:uuid",
            "created_at:timestamptz",
            "updated_at:timestamptz",
        )
        assertColumns("principal_role", "principal_id:uuid", "role_id:int8", "granted_by:uuid", "granted_at:timestamptz")
        assertColumns(
            "refresh_session",
            "id:uuid",
            "principal_id:uuid",
            "realm:varchar",
            "current_token_hash:bpchar",
            "previous_token_hash:bpchar",
            "rotated_at:timestamptz",
            "created_at:timestamptz",
            "last_used_at:timestamptz",
            "expires_at:timestamptz",
            "absolute_expires_at:timestamptz",
            "revoked_at:timestamptz",
            "revoke_reason:varchar",
            "user_agent:varchar",
            "ip:inet",
        )
        assertColumns(
            "audit_log",
            "id:int8",
            "occurred_at:timestamptz",
            "actor_id:uuid",
            "actor_type:varchar",
            "action:varchar",
            "target_type:varchar",
            "target_id:varchar",
            "detail:jsonb",
            "ip:inet",
            "user_agent:varchar",
        )
    }

    @Test
    fun `초기 데이터로 audience 4개와 system role 2개가 들어감`() {
        val audiences = strings("SELECT code FROM audience")
        val roles = strings("SELECT a.code || ':' || r.code FROM role r JOIN audience a ON a.id = r.audience_id WHERE r.is_system")

        assertEquals(setOf("wms", "catalog", "store", "auth"), audiences.toSet())
        assertEquals(setOf("auth:owner", "auth:admin"), roles.toSet())
    }

    @Test
    fun `system role을 만든 뒤에도 새 role은 id 충돌 없이 추가됨`() {
        rollbackAfter {
            val authId = long("SELECT id FROM audience WHERE code = 'auth'")

            execute("INSERT INTO role (audience_id, code, name) VALUES ($authId, 'partner_reader', 'Partner Reader')")

            // 실패한 insert도 순번을 쓰므로 정확한 값이 아니라 고정 id(1, 2) 뒤인지만 확인한다
            assertTrue(long("SELECT id FROM role WHERE code = 'partner_reader'") > 2)
        }
    }

    @Test
    fun `GOV-10 owner role은 한 명에게만 부여할 수 있음`() {
        rollbackAfter {
            val first = insertPrincipal()
            val second = insertPrincipal()
            val ownerRoleId =
                long("SELECT r.id FROM role r JOIN audience a ON a.id = r.audience_id WHERE a.code = 'auth' AND r.code = 'owner'")
            execute("INSERT INTO principal_role (principal_id, role_id) VALUES ('$first', $ownerRoleId)")

            assertConstraintViolation { execute("INSERT INTO principal_role (principal_id, role_id) VALUES ('$second', $ownerRoleId)") }
        }
    }

    @Test
    fun `GOV-10 admin role은 여러 명에게 부여할 수 있음`() {
        rollbackAfter {
            val adminRoleId =
                long("SELECT r.id FROM role r JOIN audience a ON a.id = r.audience_id WHERE a.code = 'auth' AND r.code = 'admin'")

            execute("INSERT INTO principal_role (principal_id, role_id) VALUES ('${insertPrincipal()}', $adminRoleId)")
            execute("INSERT INTO principal_role (principal_id, role_id) VALUES ('${insertPrincipal()}', $adminRoleId)")

            assertEquals(2, long("SELECT count(*) FROM principal_role WHERE role_id = $adminRoleId"))
        }
    }

    @Test
    fun `이메일은 대소문자를 구분하지 않고 유일`() {
        rollbackAfter {
            execute("INSERT INTO employee_profile (principal_id, email, name) VALUES ('${insertPrincipal()}', 'Kim@Dozy.com', '김')")

            assertConstraintViolation {
                execute("INSERT INTO employee_profile (principal_id, email, name) VALUES ('${insertPrincipal()}', 'kim@dozy.com', '이')")
            }
        }
    }

    @Test
    fun `VER-03 살아 있는 verification은 principal과 purpose마다 하나`() {
        rollbackAfter {
            val principal = insertPrincipal()
            insertVerification(principal, "PASSWORD_RESET", hash('a'))

            assertConstraintViolation { insertVerification(principal, "PASSWORD_RESET", hash('b')) }
        }
    }

    @Test
    fun `VER-03 이전 verification을 무효화하거나 소비한 뒤에는 다시 발급할 수 있음`() {
        rollbackAfter {
            val principal = insertPrincipal()
            insertVerification(principal, "PASSWORD_RESET", hash('a'))
            execute("UPDATE verification SET invalidated_at = now()")
            insertVerification(principal, "PASSWORD_RESET", hash('b'))
            execute("UPDATE verification SET consumed_at = now() WHERE invalidated_at IS NULL")

            insertVerification(principal, "PASSWORD_RESET", hash('c'))
        }
    }

    @Test
    fun `VER-03 다른 purpose의 verification은 함께 살아 있을 수 있음`() {
        rollbackAfter {
            val principal = insertPrincipal()

            insertVerification(principal, "PASSWORD_RESET", hash('a'))
            insertVerification(principal, "OWNER_TRANSFER", hash('b'))
        }
    }

    @Test
    fun `같은 token_hash의 verification은 만들 수 없음`() {
        rollbackAfter {
            insertVerification(insertPrincipal(), "PASSWORD_RESET", hash('a'))

            assertConstraintViolation { insertVerification(insertPrincipal(), "OWNER_TRANSFER", hash('a')) }
        }
    }

    @Test
    fun `정의되지 않은 코드값은 거부`() {
        rollbackAfter {
            assertConstraintViolation { execute("INSERT INTO principal (type, status) VALUES ('ROBOT', 'ACTIVE')") }
            assertConstraintViolation { execute("INSERT INTO principal (type, status) VALUES ('EMPLOYEE', 'active')") }
            assertConstraintViolation { insertVerification(insertPrincipal(), "UNKNOWN", hash('a')) }
        }
    }

    @Test
    fun `DOM-03 audience와 role의 code는 소문자 형식만 허용`() {
        rollbackAfter {
            assertConstraintViolation { execute("INSERT INTO audience (code, name) VALUES ('Bad-Code', 'x')") }
            val authId = long("SELECT id FROM audience WHERE code = 'auth'")
            assertConstraintViolation { execute("INSERT INTO role (audience_id, code, name) VALUES ($authId, '1bad', 'x')") }
        }
    }

    @Test
    fun `같은 audience 안에서 role code는 유일`() {
        rollbackAfter {
            val authId = long("SELECT id FROM audience WHERE code = 'auth'")

            assertConstraintViolation { execute("INSERT INTO role (audience_id, code, name) VALUES ($authId, 'owner', 'x')") }
        }
    }

    @Test
    fun `refresh_session id는 DB가 UUID로 만들고 현재 토큰 해시는 유일`() {
        rollbackAfter {
            val principal = insertPrincipal()
            insertSession(principal, hash('a'))

            assertTrue(uuid("SELECT id FROM refresh_session") != null)
            assertConstraintViolation { insertSession(principal, hash('a')) }
        }
    }

    @Test
    fun `AUD-06 audit_log는 존재하지 않는 principal을 가리켜도 기록됨`() {
        rollbackAfter {
            execute("INSERT INTO audit_log (actor_id, action) VALUES ('${UUID.randomUUID()}', 'LOGIN_SUCCEEDED')")

            assertEquals(1, long("SELECT count(*) FROM audit_log"))
        }
    }

    @Test
    fun `조회에 쓰는 인덱스가 있음`() {
        val indexes = strings("SELECT indexname FROM pg_indexes WHERE schemaname = 'public'").toSet()

        val expected =
            setOf(
                "uq_employee_profile_email",
                "uq_verification_live",
                "uq_principal_role_owner",
                "ix_refresh_session_principal_live",
                "ix_refresh_session_previous_token_hash",
                "ix_audit_log_occurred_at",
                "ix_audit_log_actor",
                "ix_audit_log_target",
            )
        assertTrue(indexes.containsAll(expected), "없는 인덱스: ${expected - indexes}")
    }

    // --- 도구 ---

    private fun execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    private fun strings(sql: String): List<String> =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs -> generateSequence { if (rs.next()) rs.getString(1) else null }.toList() }
        }

    private fun long(sql: String): Long =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

    private fun uuid(sql: String): UUID? =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                rs.next()
                rs.getObject(1, UUID::class.java)
            }
        }

    private fun insertPrincipal(): UUID =
        connection.createStatement().use { st ->
            st.executeQuery("INSERT INTO principal (type, status) VALUES ('EMPLOYEE', 'ACTIVE') RETURNING id").use { rs ->
                rs.next()
                rs.getObject(1, UUID::class.java)
            }
        }

    private fun insertVerification(
        principal: UUID,
        purpose: String,
        tokenHash: String,
    ) = execute(
        "INSERT INTO verification (principal_id, purpose, method, target, token_hash, expires_at) " +
            "VALUES ('$principal', '$purpose', 'EMAIL', 'a@b.c', '$tokenHash', now() + interval '1 day')",
    )

    private fun insertSession(
        principal: UUID,
        tokenHash: String,
    ) = execute(
        "INSERT INTO refresh_session (principal_id, realm, current_token_hash, last_used_at, expires_at, absolute_expires_at) " +
            "VALUES ('$principal', 'INTERNAL', '$tokenHash', now(), now() + interval '1 day', now() + interval '7 day')",
    )

    private fun hash(c: Char) = c.toString().repeat(64)

    private fun assertColumns(
        table: String,
        vararg expected: String,
    ) {
        val actual =
            strings(
                "SELECT column_name || ':' || udt_name FROM information_schema.columns " +
                    "WHERE table_schema = 'public' AND table_name = '$table'",
            )
        assertEquals(expected.toSet(), actual.toSet(), "$table 컬럼")
    }

    private fun assertConstraintViolation(block: () -> Unit) {
        // 실패한 문장 뒤에도 같은 트랜잭션을 쓸 수 있게 savepoint를 둔다
        val savepoint = connection.setSavepoint()
        assertFailsWith<SQLException> { block() }
        connection.rollback(savepoint)
    }

    private fun rollbackAfter(block: () -> Unit) {
        connection.autoCommit = false
        try {
            block()
        } finally {
            connection.rollback()
            connection.autoCommit = true
        }
    }
}
