package no.nav.fager.infrastruktur

import org.slf4j.LoggerFactory
import org.slf4j.MarkerFactory

inline fun <reified T> T.logger(): org.slf4j.Logger = LoggerFactory.getLogger(T::class.qualifiedName)

val TEAM_LOG_MARKER: org.slf4j.Marker = MarkerFactory.getMarker("TEAM_LOGS")