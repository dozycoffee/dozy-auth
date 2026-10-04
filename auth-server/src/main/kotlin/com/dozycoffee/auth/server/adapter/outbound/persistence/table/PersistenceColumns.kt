package com.dozycoffee.auth.server.adapter.outbound.persistence.table

import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.json.jsonb
import org.postgresql.util.PGobject
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.jacksonMapperBuilder

private val jsonMapper: JsonMapper = jacksonMapperBuilder().build()

private val jsonObjectType = object : TypeReference<Map<String, Any?>>() {}

/** jsonb 컬럼. 값은 JSON 객체이며 [Map]으로 다룹니다 (`verification.payload`, `audit_log.detail`). */
internal fun Table.jsonbObject(name: String) =
    jsonb<Map<String, Any?>>(
        name,
        { jsonMapper.writeValueAsString(it) },
        { jsonMapper.readValue(it, jsonObjectType) },
    )

/** PostgreSQL `inet` 컬럼. 값은 주소 문자열입니다 (예: `203.0.113.7`). */
internal fun Table.inet(name: String) = registerColumn(name, InetColumnType())

private class InetColumnType : ColumnType<String>() {
    override fun sqlType(): String = "INET"

    override fun valueFromDB(value: Any): String =
        when (value) {
            is PGobject -> value.value ?: error("inet 값이 비어 있음")
            else -> value.toString()
        }

    override fun notNullValueToDB(value: String): Any =
        PGobject().apply {
            type = "inet"
            this.value = value
        }

    override fun nonNullValueToString(value: String): String = "'$value'"
}
