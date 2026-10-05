package com.dozycoffee.auth.server.support

import io.micrometer.core.instrument.MeterRegistry

/**
 * [name] counter 중 [tags](이름, 값 순서)가 모두 맞는 것의 합. 하나도 없으면 0입니다.
 *
 * 지표 이름과 태그는 configuration.md §10의 문자열 그대로 넘깁니다.
 */
fun MeterRegistry.counted(
    name: String,
    vararg tags: String,
): Double =
    find(name)
        .tags(*tags)
        .counters()
        .sumOf { it.count() }
