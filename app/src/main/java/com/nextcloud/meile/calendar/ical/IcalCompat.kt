/*
 * M9 Box - Meile branded Nextcloud client
 * SPDX-License-Identifier: AGPL-3.0-or-later OR GPL-2.0-only
 *
 * Small helpers over ical4j 4.x (java.time based, immutable lists). Keeps the
 * mappers readable: name based property access, epoch millis conversion and
 * in-place property edits.
 */
package com.nextcloud.meile.calendar.ical

import net.fortuna.ical4j.model.Calendar
import net.fortuna.ical4j.model.Component
import net.fortuna.ical4j.model.Property
import net.fortuna.ical4j.model.component.CalendarComponent
import net.fortuna.ical4j.model.component.VEvent
import net.fortuna.ical4j.model.component.VToDo
import net.fortuna.ical4j.model.property.DateProperty
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.Temporal

internal fun Calendar.events(): List<VEvent> =
    getComponents<CalendarComponent>().filterIsInstance<VEvent>()

internal fun Calendar.todos(): List<VToDo> =
    getComponents<CalendarComponent>().filterIsInstance<VToDo>()

internal fun Calendar.addComponent(component: CalendarComponent) {
    componentList = componentList.add(component)
}

internal fun Calendar.removeComponents(components: Collection<CalendarComponent>) {
    var list = componentList
    for (c in components) list = list.remove(c)
    componentList = list
}

internal fun Component.prop(name: String): Property? = getProperty<Property>(name).orElse(null)

internal fun Component.hasProp(name: String): Boolean = prop(name) != null

internal fun Component.propValue(name: String): String? = prop(name)?.value

internal fun Component.uidValue(): String? = uid.orElse(null)?.value

/** The date/time of a DTSTART, DTEND, DUE, RECURRENCE-ID, COMPLETED... property. */
internal fun Component.temporal(name: String): Temporal? = (prop(name) as? DateProperty<*>)?.date

internal fun Component.isOverride(): Boolean = hasProp(Property.RECURRENCE_ID)

internal fun Component.addProp(property: Property) {
    propertyList = propertyList.add(property)
}

internal fun Component.removeProps(vararg names: String) {
    propertyList = propertyList.removeIf { p -> names.any { it.equals(p.name, ignoreCase = true) } }
}

internal fun Component.replaceProp(property: Property) {
    removeProps(property.name)
    addProp(property)
}

internal fun Component.addChild(child: Component) {
    when (this) {
        is VEvent -> componentList = componentList.add(child)
        is VToDo -> componentList = componentList.add(child)
        else -> error("unsupported container")
    }
}

internal fun Temporal.isDateOnly(): Boolean = this is LocalDate

/** Epoch millis. DATE = UTC midnight, floating DATE-TIME = device zone. */
internal fun Temporal.toEpochMs(): Long = when (this) {
    is Instant -> toEpochMilli()
    is ZonedDateTime -> toInstant().toEpochMilli()
    is OffsetDateTime -> toInstant().toEpochMilli()
    is LocalDateTime -> atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    is LocalDate -> atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    else -> Instant.from(this).toEpochMilli()
}

/** IANA id the Android CalendarProvider understands, or UTC. */
internal fun Temporal.providerZoneId(): String =
    (this as? ZonedDateTime)?.zone?.takeIf { it !is ZoneOffset }?.id ?: "UTC"

internal fun dateOnly(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

internal fun dateTimeIn(ms: Long, zone: ZoneId?): Temporal {
    val instant = Instant.ofEpochMilli(ms)
    return if (zone != null) instant.atZone(zone) else instant
}
