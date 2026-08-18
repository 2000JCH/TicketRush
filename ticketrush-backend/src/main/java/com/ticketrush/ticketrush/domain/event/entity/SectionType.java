package com.ticketrush.ticketrush.domain.event.entity;

/**
 * SEATED: 구역(등급) → 행 → 좌석번호의 사각 격자. 좌석마다 seat 행이 생긴다.
 * STANDING: 개별 좌석 없이 잔여 수량만 관리한다 (decisions.md 1·12번).
 */
public enum SectionType {
    SEATED,
    STANDING
}
